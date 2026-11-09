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

    // Lazy self-injection so internal calls still go through the tx proxy.
    // Defaults to the raw instance so plain unit tests (no proxy) still work.
    private PlatformEarningsService self = this;

    @org.springframework.beans.factory.annotation.Autowired
    void setSelf(@org.springframework.context.annotation.Lazy PlatformEarningsService self) {
        this.self = self;
    }

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
    /** Runs the balance reconciliation. Deliberately not transactional: the
     *  provider HTTP call must not hold locks while waiting on the network. */
    public ReconciliationLog reconcile() {
        var in = self.reconcileInputs();
        Long provider = null;
        String error = null;
        if (in.creds() != null) {
            try {
                JsonNode bal = marzPay.getBalance(in.creds(), "UG");
                provider = bal.path("data").path("account").path("available_balance").path("raw").longValue();
            } catch (Exception e) {
                error = e.getMessage();
            }
        }
        return self.applyReconcile(in.expected(), provider, error);
    }

    /** Snapshot of the books used for the compare — short transactional read. */
    @Transactional
    public ReconcileInputs reconcileInputs() {
        PlatformEarningsAccount a = accountForUpdate();
        long tenantLiability = tenantWallets.findAll().stream()
                .mapToLong(w -> w.getAvailableBalance() + w.getReservedBalance()).sum();
        long expected = a.getSettledEarnings() + a.getReservedEarnings() + tenantLiability;
        var creds = gatewayConfig.platformConfigured()
                ? gatewayConfig.credentialsFor(null, TenantGatewayConfig.MODE_PLATFORM) : null;
        return new ReconcileInputs(expected, creds);
    }

    record ReconcileInputs(long expected, com.wifibilling.service.payment.MarzPayClient.Credentials creds) {}

    /** Applies a fetched provider balance (or a fetch failure) in a short transaction. */
    @Transactional
    public ReconciliationLog applyReconcile(long expected, Long provider, String error) {
        PlatformEarningsAccount a = accountForUpdate();
        if (provider == null && error == null) {
            a.setReconcileStatus(PlatformEarningsAccount.RECON_UNVERIFIED);
            a.setReconcileNote("Platform MarzPay credentials not configured");
            return saveLog(null, expected, null, ReconciliationLog.FAILED, "Provider not configured");
        }
        if (error != null) {
            a.setReconcileStatus(PlatformEarningsAccount.RECON_UNVERIFIED);
            a.setReconcileNote("Balance fetch failed: " + error);
            return saveLog(null, expected, null, ReconciliationLog.FAILED, error);
        }
        a.setLastProviderBalance(provider);
        a.setLastSyncedAt(OffsetDateTime.now());

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
    public void scheduledReconcile() {
        if (!leaderLockService.executeIfLeader(LeaderLockService.JOB_PLATFORM_EARNINGS, 300, 30, this::doScheduledReconcile)) return;
    }

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

    /** Deliberately not transactional: the provider calls (reconcile, payout
     *  dispatch) must run with no transaction held so they cannot sit
     *  idle-in-transaction on the network. */
    public PlatformPayout requestPayout(long amount, String phone, Long adminId) {
        if (!payoutsEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Platform payouts are disabled. Enable WITHDRAWALS_PLATFORM_PAYOUTS_ENABLED after verifying the account.");
        }
        if (amount <= 0 || amount > maxPayout) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount outside permitted range");
        }

        // Fresh reconciliation required — never pay out on stale numbers.
        if (self.payoutReconcileStale()) reconcile();

        PlatformPayout p = self.reservePayout(amount, phone, adminId);
        dispatchPayout(p.getId());
        return self.payoutById(p.getId());
    }

    @Transactional(readOnly = true)
    public boolean payoutReconcileStale() {
        return balanceStale(account());
    }

    /** Reserves the payout under the account lock — the transactional part. */
    @Transactional
    public PlatformPayout reservePayout(long amount, String phone, Long adminId) {
        PlatformEarningsAccount a = accountForUpdate();
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
        accounts.save(a);
        return p;
    }

    @Transactional(readOnly = true)
    public PlatformPayout payoutById(Long payoutId) {
        return payouts.findById(payoutId).orElseThrow();
    }

    /** Provider send runs with no transaction held; the result applies in a fresh one. */
    private void dispatchPayout(Long payoutId) {
        var plan = self.payoutDispatchPlan(payoutId);
        JsonNode res;
        try {
            String callbackUrl = props.getPublicBaseUrl() + "/api/payments/marzpay/callback";
            res = marzPay.sendMoney(plan.creds(), plan.amount(), plan.phone(), plan.payoutRef(),
                    "UG", "Platform subscription earnings payout", callbackUrl);
        } catch (Exception e) {
            // A timeout may still have created the payout — never retry blindly.
            self.markPayoutDispatchError(payoutId, "Dispatch error (" + e.getMessage() + ") — awaiting reconciliation");
            log.error("Platform payout {} dispatch failed: {}", plan.payoutRef(), e.getMessage());
            return;
        }
        self.applyPayoutDispatch(payoutId, res);
    }

    @Transactional
    public PayoutDispatchPlan payoutDispatchPlan(Long payoutId) {
        PlatformPayout p = payouts.findById(payoutId).orElseThrow();
        var creds = gatewayConfig.credentialsFor(null, TenantGatewayConfig.MODE_PLATFORM);
        return new PayoutDispatchPlan(p.getAmount(), p.getPhone(), p.getPayoutRef(), creds);
    }

    record PayoutDispatchPlan(Long amount, String phone, String payoutRef,
                              com.wifibilling.service.payment.MarzPayClient.Credentials creds) {}

    /** Applies the provider dispatch response: provider uuid + fee true-up. */
    @Transactional
    public void applyPayoutDispatch(Long payoutId, JsonNode res) {
        PlatformPayout p = payouts.findById(payoutId).orElseThrow();
        PlatformEarningsAccount a = accountForUpdate();
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
        accounts.save(a);
    }

    @Transactional
    public void markPayoutDispatchError(Long payoutId, String reason) {
        payouts.findById(payoutId).ifPresent(p -> {
            p.setFailureReason(reason);
            payouts.save(p);
        });
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

    /**
     * Credits the platform fee taken from a tenant's customer payment. The money is
     * already in the platform MarzPay account (it came in with the collection), so it
     * is settled and withdrawable, like subscription revenue. Idempotent per ref.
     */
    @Transactional
    public boolean creditPlatformFee(long amount, String ref, String note) {
        if (amount <= 0 || ledger.existsByTypeAndExternalRef(PlatformLedgerEntry.EARNING_PLATFORM_FEE, ref)) return false;
        PlatformEarningsAccount a = accountForUpdate();
        a.setSettledEarnings(a.getSettledEarnings() + amount);
        a.setTotalEarned(a.getTotalEarned() + amount);
        record(a, PlatformLedgerEntry.EARNING_PLATFORM_FEE, amount, ref, null, null, note);
        return true;
    }

    /**
     * A tenant payout cost more in provider fees than the tenant had left: the
     * platform pays the difference from its settled earnings instead of leaving the
     * tenant's wallet negative. Keeps reconciliation exact. Idempotent per ref.
     */
    @Transactional
    public void absorbPayoutFeeShortfall(long amount, String ref, String note) {
        if (amount <= 0 || ledger.existsByTypeAndExternalRef(PlatformLedgerEntry.FEE, ref)) return;
        PlatformEarningsAccount a = accountForUpdate();
        a.setSettledEarnings(a.getSettledEarnings() - amount);
        a.setTotalFees(a.getTotalFees() + amount);
        record(a, PlatformLedgerEntry.FEE, amount, ref, null, null, note);
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
