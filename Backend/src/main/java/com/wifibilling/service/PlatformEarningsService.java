package com.wifibilling.service;

import tools.jackson.databind.JsonNode;
import com.wifibilling.config.PlatformProperties;
import com.wifibilling.model.*;
import com.wifibilling.repository.*;
import com.wifibilling.service.payment.MarzPayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Platform-owned subscription earnings: internal ledger, provider balance
 * reconciliation, and super-admin payouts. The withdrawable amount is always
 * the LOWER of (confirmed MarzPay-backed earnings) and (actual provider balance
 * minus all tenant liabilities and a safety buffer) — tenant money can never be
 * paid out as platform earnings.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformEarningsService {

    private final PlatformEarningsAccountRepository accounts;
    private final PlatformLedgerEntryRepository ledger;
    private final PlatformPayoutRepository payouts;
    private final ReconciliationLogRepository reconLogs;
    private final WalletAccountRepository tenantWallets;
    private final MarzPayClient marzPay;
    private final GatewayConfigService gatewayConfig;
    private final PlatformProperties props;
    private LeaderLockService leaderLockService = new LeaderLockService();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLeaderLockService(LeaderLockService leaderLockService) {
        this.leaderLockService = leaderLockService;
    }

    @Value("${platform.payouts.enabled:false}")
    private boolean payoutsEnabled;
    @Value("${platform.reconcile.tolerance:1000}")
    private long tolerance;
    @Value("${platform.reconcile.buffer:5000}")
    private long safetyBuffer;
    @Value("${platform.balance.max-staleness-minutes:10}")
    private long maxStalenessMinutes;
    @Value("${platform.payout.max-amount:5000000}")
    private long maxPayout;

    // ---------- account ----------

    private PlatformEarningsAccount accountForUpdate() {
        return accounts.findForUpdate().orElseGet(() ->
                accounts.saveAndFlush(PlatformEarningsAccount.builder().build()));
    }

    @Transactional(readOnly = true)
    public PlatformEarningsAccount account() {
        return accounts.findById(PlatformEarningsAccount.ID).orElseGet(PlatformEarningsAccount::new);
    }

    // ---------- earnings credits ----------

    /**
     * Credits subscription revenue. Idempotent per payment id — a retried call
     * can never double-credit. Only MARZPAY-channel receipts add to the settled
     * (withdrawable) bucket; external receipts are tracked separately.
     */
    @Transactional
    public boolean creditSubscriptionPayment(SubscriptionPayment payment, String channel) {
        PlatformEarningsAccount a = accountForUpdate();
        String ref = "SUBPAY:" + payment.getId();
        if ("MARZPAY".equalsIgnoreCase(channel)) {
            if (ledger.existsByTypeAndExternalRef(PlatformLedgerEntry.EARNING_MARZPAY, ref)) return false;
            a.setSettledEarnings(a.getSettledEarnings() + payment.getAmount());
            a.setTotalEarned(a.getTotalEarned() + payment.getAmount());
            record(a, PlatformLedgerEntry.EARNING_MARZPAY, payment.getAmount(), ref,
                    payment.getId(), null, "Subscription payment " + payment.getId());
            return true;
        }
        if (ledger.existsByTypeAndExternalRef(PlatformLedgerEntry.EARNING_EXTERNAL, ref)) return false;
        a.setExternalEarnings(a.getExternalEarnings() + payment.getAmount());
        a.setTotalEarned(a.getTotalEarned() + payment.getAmount());
        record(a, PlatformLedgerEntry.EARNING_EXTERNAL, payment.getAmount(), ref,
                payment.getId(), null, "External subscription payment " + payment.getId());
        return true;
    }

    // ---------- reconciliation ----------

    /**
     * Fetches the real MarzPay balance and compares it to the expected position:
     * platform settled+reserved earnings + all tenant wallet liabilities.
     * Differences are logged and flagged — never auto-corrected.
     */
    @Transactional
    public ReconciliationLog reconcile() {
        PlatformEarningsAccount a = accountForUpdate();
        long tenantLiability = tenantWallets.findAll().stream()
                .mapToLong(w -> w.getAvailableBalance() + w.getReservedBalance()).sum();
        long expected = a.getSettledEarnings() + a.getReservedEarnings() + tenantLiability;

        if (!gatewayConfig.platformConfigured()) {
            a.setReconcileStatus(PlatformEarningsAccount.RECON_UNVERIFIED);
            a.setReconcileNote("Platform MarzPay credentials not configured");
            return saveLog(null, expected, null, ReconciliationLog.FAILED, "Provider not configured");
        }
        Long provider = null;
        try {
            var creds = gatewayConfig.credentialsFor(null, TenantGatewayConfig.MODE_PLATFORM);
            JsonNode bal = marzPay.getBalance(creds, "UG");
            provider = bal.path("data").path("account").path("available_balance").path("raw").longValue();
            a.setLastProviderBalance(provider);
            a.setLastSyncedAt(OffsetDateTime.now());
        } catch (Exception e) {
            a.setReconcileStatus(PlatformEarningsAccount.RECON_UNVERIFIED);
            a.setReconcileNote("Balance fetch failed: " + e.getMessage());
            return saveLog(null, expected, null, ReconciliationLog.FAILED, e.getMessage());
        }

        long diff = provider - expected;
        if (Math.abs(diff) <= tolerance) {
            a.setReconcileStatus(PlatformEarningsAccount.RECON_CLEAN);
            a.setReconcileNote(null);
            return saveLog(provider, expected, diff, ReconciliationLog.CLEAN, null);
        }
        if (diff < 0) {
            // Provider holds LESS than our records expect — an external debit
            // (dashboard withdrawal, provider fee, chargeback) we didn't record.
            a.setReconcileStatus(PlatformEarningsAccount.RECON_DISCREPANCY);
            a.setReconcileNote("Provider balance is UGX " + (-diff) + " below expected — possible external withdrawal or unrecorded debit. Investigate before paying out.");
            return saveLog(provider, expected, diff, ReconciliationLog.EXTERNAL_DEBIT,
                    "Provider shortfall " + (-diff));
        }
        a.setReconcileStatus(PlatformEarningsAccount.RECON_DISCREPANCY);
        a.setReconcileNote("Provider balance is UGX " + diff + " ABOVE expected — unrecorded credit. Classify before paying out.");
        return saveLog(provider, expected, diff, ReconciliationLog.DISCREPANCY, "Excess " + diff);
    }

    @Scheduled(fixedDelayString = "${platform.reconcile.interval-ms:300000}") // every 5 min
    @Transactional
    public void scheduledReconcile() {
        if (!leaderLockService.executeIfLeader(LeaderLockService.JOB_PLATFORM_EARNINGS, 300, 30, this::doScheduledReconcile)) return;
    }

    @Transactional
    public void doScheduledReconcile() {
        if (!gatewayConfig.platformConfigured()) return;
        try { reconcile(); } catch (Exception e) {
            log.warn("Scheduled reconciliation failed: {}", e.getMessage());
        }
    }

    private ReconciliationLog saveLog(Long provider, long expected, Long diff, String status, String note) {
        return reconLogs.save(ReconciliationLog.builder()
                .providerBalance(provider).expectedBalance(expected)
                .difference(diff).status(status).note(note).build());
    }

    public boolean balanceStale(PlatformEarningsAccount a) {
        return a.getLastSyncedAt() == null ||
                Duration.between(a.getLastSyncedAt(), OffsetDateTime.now()).toMinutes() > maxStalenessMinutes;
    }

    /** Maximum safe withdrawal — lower of confirmed earnings and provider funds
     *  remaining after protecting all tenant liabilities plus a safety buffer. */
    @Transactional(readOnly = true)
    public long safeWithdrawable() {
        PlatformEarningsAccount a = account();
        if (a.getLastProviderBalance() == null) return 0;
        long tenantLiability = tenantWallets.findAll().stream()
                .mapToLong(w -> w.getAvailableBalance() + w.getReservedBalance()).sum();
        long providerHeadroom = a.getLastProviderBalance() - tenantLiability - a.getReservedEarnings() - safetyBuffer;
        return Math.max(0, Math.min(a.getSettledEarnings(), providerHeadroom));
    }

    // ---------- payouts ----------

    @Transactional
    public PlatformPayout requestPayout(long amount, String phone, Long adminId) {
        if (!payoutsEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Platform payouts are disabled. Enable WITHDRAWALS_PLATFORM_PAYOUTS_ENABLED after verifying the account.");
        }
        if (amount <= 0 || amount > maxPayout) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount outside permitted range");
        }
        PlatformEarningsAccount a = accountForUpdate();

        // Fresh reconciliation required — never pay out on stale numbers.
        if (balanceStale(a)) {
            reconcile();
            a = accountForUpdate();
        }
        if (PlatformEarningsAccount.RECON_DISCREPANCY.equals(a.getReconcileStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Reconciliation discrepancy — " + (a.getReconcileNote() != null ? a.getReconcileNote() : "investigate before withdrawing"));
        }
        if (a.getLastProviderBalance() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Provider balance unavailable — cannot verify safe withdrawal amount");
        }
        long tenantLiability = tenantWallets.findAll().stream()
                .mapToLong(w -> w.getAvailableBalance() + w.getReservedBalance()).sum();
        long headroom = a.getLastProviderBalance() - tenantLiability - a.getReservedEarnings() - safetyBuffer;
        long maxSafe = Math.min(a.getSettledEarnings(), headroom);
        long feeEstimate = Math.max(500, Math.round(amount * 0.01));
        if (amount + feeEstimate > maxSafe) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Amount exceeds the safe withdrawable balance of UGX " + Math.max(0, maxSafe - feeEstimate));
        }

        String ref = UUID.randomUUID().toString();
        PlatformPayout p = PlatformPayout.builder()
                .amount(amount).fee(feeEstimate).totalDeduction(amount + feeEstimate)
                .phone(toE164(phone)).payoutRef(ref).initiatedBy(adminId)
                .status(PlatformPayout.PROCESSING).build();
        p = payouts.save(p);

        // Atomic reservation under the account lock.
        a.setSettledEarnings(a.getSettledEarnings() - p.getTotalDeduction());
        a.setReservedEarnings(a.getReservedEarnings() + p.getTotalDeduction());
        record(a, PlatformLedgerEntry.PAYOUT_RESERVE, p.getTotalDeduction(), "PRES:" + ref,
                null, p.getId(), "Earnings payout reserved");

        dispatchPayout(a, p);
        accounts.save(a);
        return p;
    }

    private void dispatchPayout(PlatformEarningsAccount a, PlatformPayout p) {
        try {
            var creds = gatewayConfig.credentialsFor(null, TenantGatewayConfig.MODE_PLATFORM);
            String callbackUrl = props.getPublicBaseUrl() + "/api/payments/marzpay/callback";
            JsonNode res = marzPay.sendMoney(creds, p.getAmount(), p.getPhone(), p.getPayoutRef(),
                    "UG", "Platform subscription earnings payout", callbackUrl);
            var txn = res.path("data").path("transaction");
            if (!txn.path("uuid").isNull()) p.setProviderUuid(txn.path("uuid").asText());
            var wd = res.path("data").path("withdrawal").isMissingNode()
                    ? res.path("data").path("disbursement") : res.path("data").path("withdrawal");
            var charge = wd.path("charge").path("raw");
            if (charge.isNumber() && charge.longValue() != p.getFee()) {
                long delta = charge.longValue() - p.getFee();
                p.setFee(charge.longValue());
                p.setTotalDeduction(p.getAmount() + p.getFee());
                if (delta > 0) { // true-up the reservation
                    a.setSettledEarnings(a.getSettledEarnings() - delta);
                    a.setReservedEarnings(a.getReservedEarnings() + delta);
                    record(a, PlatformLedgerEntry.PAYOUT_RESERVE, delta, "PRESADJ:" + p.getPayoutRef(),
                            null, p.getId(), "Payout fee reconciliation");
                } else {
                    a.setReservedEarnings(a.getReservedEarnings() + delta); // delta negative
                    a.setSettledEarnings(a.getSettledEarnings() - delta);
                    record(a, PlatformLedgerEntry.PAYOUT_RELEASE, -delta, "PRESADJ:" + p.getPayoutRef(),
                            null, p.getId(), "Payout fee reconciliation");
                }
            }
            payouts.save(p);
        } catch (Exception e) {
            // A timeout may still have created the payout — never retry blindly.
            p.setFailureReason("Dispatch error (" + e.getMessage() + ") — awaiting reconciliation");
            payouts.save(p);
            log.error("Platform payout {} dispatch failed: {}", p.getPayoutRef(), e.getMessage());
        }
    }

    /** MarzPay disbursement callback for a platform payout. */
    @Transactional
    public boolean processPayoutCallback(JsonNode payload) {
        var txn = payload.path("transaction");
        String ourRef = txn.path("provider_reference").asText(null);
        if (ourRef == null) return false;
        PlatformPayout p = payouts.findByPayoutRef(ourRef).orElse(null);
        if (p == null) return false;
        if (PlatformPayout.COMPLETED.equals(p.getStatus()) || PlatformPayout.FAILED.equals(p.getStatus())) {
            return true; // already final — idempotent
        }
        String status = txn.path("status").asText("").toLowerCase();
        String uuid = txn.path("uuid").asText(null);
        if (uuid != null && !uuid.isBlank()) p.setProviderUuid(uuid);
        String providerTx = payload.path("disbursement").path("provider_transaction_id").asText(null);
        if (providerTx != null) p.setProviderTransactionId(providerTx);

        PlatformEarningsAccount a = accountForUpdate();
        if ("completed".equals(status)) {
            p.setStatus(PlatformPayout.COMPLETED);
            p.setCompletedAt(OffsetDateTime.now());
            a.setReservedEarnings(Math.max(0, a.getReservedEarnings() - p.getTotalDeduction()));
            a.setTotalWithdrawn(a.getTotalWithdrawn() + p.getTotalDeduction());
            a.setTotalFees(a.getTotalFees() + p.getFee());
            record(a, PlatformLedgerEntry.PAYOUT_DEBIT, p.getTotalDeduction(), "PDEB:" + p.getPayoutRef(),
                    null, p.getId(), "Earnings payout completed");
        } else if ("failed".equals(status) || "cancelled".equals(status) || "reversed".equals(status)) {
            p.setStatus(PlatformPayout.FAILED);
            p.setFailureReason("Provider status: " + status);
            p.setCompletedAt(OffsetDateTime.now());
            a.setReservedEarnings(Math.max(0, a.getReservedEarnings() - p.getTotalDeduction()));
            a.setSettledEarnings(a.getSettledEarnings() + p.getTotalDeduction());
            record(a, PlatformLedgerEntry.PAYOUT_RELEASE, p.getTotalDeduction(), "PREL:" + p.getPayoutRef(),
                    null, p.getId(), "Reserved earnings released after payout failure");
        }
        payouts.save(p);
        return true;
    }

    public List<PlatformPayout> recentPayouts() { return payouts.findTop100ByOrderByCreatedAtDesc(); }
    public List<PlatformLedgerEntry> recentActivity() { return ledger.findTop200ByOrderByCreatedAtDesc(); }
    public List<ReconciliationLog> recentReconciliations() { return reconLogs.findTop20ByOrderByCreatedAtDesc(); }
    public boolean payoutsEnabled() { return payoutsEnabled; }
    public long safetyBuffer() { return safetyBuffer; }

    private void record(PlatformEarningsAccount a, String type, long amount, String ref,
                        Long subPaymentId, Long payoutId, String note) {
        accounts.save(a);
        ledger.save(PlatformLedgerEntry.builder()
                .type(type).amount(amount)
                .settledAfter(a.getSettledEarnings())
                .externalAfter(a.getExternalEarnings())
                .reservedAfter(a.getReservedEarnings())
                .externalRef(ref)
                .subscriptionPaymentId(subPaymentId)
                .payoutId(payoutId)
                .note(note).build());
    }

    private String toE164(String phone) {
        String clean = phone.replaceAll("[^0-9+]", "");
        if (clean.startsWith("+")) return clean;
        if (clean.startsWith("256")) return "+" + clean;
        if (clean.startsWith("0")) return "+256" + clean.substring(1);
        return "+256" + clean;
    }
}
