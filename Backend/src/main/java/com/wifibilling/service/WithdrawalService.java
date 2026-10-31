package com.wifibilling.service;

import tools.jackson.databind.JsonNode;
import com.wifibilling.config.PlatformProperties;
import com.wifibilling.model.*;
import com.wifibilling.repository.*;
import com.wifibilling.service.payment.MarzPayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Tenant withdrawal lifecycle against the internal wallet.
 *
 * <ol>
 *   <li>Request: under the wallet lock, check idempotency and amount + fee against
 *       the balance, then save the withdrawal and reserve the funds. Commits before
 *       any money moves.</li>
 *   <li>Dispatch: an atomic claim marks it PROCESSING exactly once, then the payout
 *       is sent outside any transaction. Nothing after the provider call can roll
 *       the withdrawal back.</li>
 *   <li>Outcome: the dispatch response, provider callbacks and the stuck-payout
 *       sweeper each lock the withdrawal row and apply only what is still missing,
 *       so they are safe in any order and any number of times.</li>
 * </ol>
 *
 * Funds are released only when the provider confirms a failure, or by an admin.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private static final List<String> OPEN = List.of(WithdrawalRequest.REQUESTED,
            WithdrawalRequest.PENDING_APPROVAL, WithdrawalRequest.PROCESSING);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final WithdrawalRequestRepository withdrawals;
    private final WalletService walletService;
    private final GatewayConfigService gatewayConfig;
    private final MarzPayClient marzPay;
    private final PlatformProperties props;
    private final TenantRepository tenantRepository;
    private final PlatformEarningsService platformEarnings;
    private final PlatformTransactionManager txManager;
    private LeaderLockService leaderLockService = new LeaderLockService();

    @Autowired(required = false)
    public void setLeaderLockService(LeaderLockService leaderLockService) {
        this.leaderLockService = leaderLockService;
    }

    @Value("${withdrawals.require-approval:true}")
    private boolean requireApproval;
    @Value("${withdrawals.live-payouts:false}")
    private boolean livePayoutsEnabled;
    @Value("${withdrawals.min-amount:1000}")
    private long minAmount;
    @Value("${withdrawals.max-amount:5000000}")
    private long maxAmount;
    @Value("${withdrawals.max-open-per-tenant:1}")
    private long maxOpenPerTenant;
    @Value("${withdrawals.fee-percent:3}")
    private long feePercent;
    @Value("${withdrawals.recheck-after-seconds:120}")
    private long recheckAfterSeconds;

    public boolean approvalRequired() { return requireApproval; }
    public boolean livePayoutsEnabled() { return livePayoutsEnabled; }

    public record Limits(long min, long max, boolean approvalRequired, boolean livePayouts, long feePercent) {}
    public Limits limits() { return new Limits(minAmount, maxAmount, requireApproval, livePayoutsEnabled, feePercent); }

    @Transactional(readOnly = true)
    public List<WithdrawalRequest> list(Long tenantId) {
        return withdrawals.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    // ---------- request ----------

    /** An earlier request with this Idempotency-Key, if any — lets a retry skip OTP re-verification. */
    @Transactional(readOnly = true)
    public Optional<WithdrawalRequest> findByIdempotencyKey(Long tenantId, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        return key == null ? Optional.empty() : withdrawals.findByTenantIdAndIdempotencyKey(tenantId, key);
    }

    /**
     * Creates a withdrawal and reserves amount + estimated fee, then (in auto mode)
     * sends it. Idempotent per key: a retry returns the original withdrawal and can
     * never create or pay a second one.
     */
    public WithdrawalRequest request(Long tenantId, long amount, String phone, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        if (amount < minAmount) throw badRequest("The minimum withdrawal is " + ugx(minAmount) + ".");
        if (amount > maxAmount) throw badRequest("The maximum withdrawal is " + ugx(maxAmount) + ".");
        if (phone == null || !phone.replaceAll("[^0-9]", "").matches("(256|0)?7[0-9]{8}")) {
            throw badRequest("Enter a valid mobile money number");
        }
        String phoneE164 = toE164(phone);

        WithdrawalRequest w;
        try {
            w = inNewTransaction(() -> {
                // The wallet lock serializes every withdrawal request of this tenant, so
                // the idempotency, open-count and balance checks below cannot race.
                WalletAccount wallet = walletService.walletForUpdate(tenantId);
                if (key != null) {
                    var earlier = withdrawals.findByTenantIdAndIdempotencyKey(tenantId, key);
                    if (earlier.isPresent()) return replay(earlier.get(), amount, phoneE164);
                }
                requireReconciledProvider();
                if (withdrawals.countByTenantIdAndStatusIn(tenantId, OPEN) >= maxOpenPerTenant) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have a withdrawal in progress");
                }
                long fee = estimateFee(amount);
                if (amount + fee > wallet.getAvailableBalance()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            insufficientBalanceMessage(amount, fee, wallet.getAvailableBalance()));
                }
                Tenant tenant = tenantRepository.findById(tenantId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
                WithdrawalRequest created = withdrawals.save(WithdrawalRequest.builder()
                        .tenant(tenant)
                        .amount(amount)
                        .fee(fee)
                        .totalDeduction(amount + fee)
                        .phone(phoneE164)
                        .payoutRef(UUID.randomUUID().toString())
                        .idempotencyKey(key)
                        .status(requireApproval ? WithdrawalRequest.PENDING_APPROVAL : WithdrawalRequest.REQUESTED)
                        .build());
                walletService.reserveForWithdrawal(tenantId, created.getTotalDeduction(),
                        "RES:" + created.getPayoutRef(), created.getId());
                return created;
            });
        } catch (DataIntegrityViolationException e) {
            // Unique-index backstop for the same key arriving through another node.
            if (key == null) throw e;
            w = withdrawals.findByTenantIdAndIdempotencyKey(tenantId, key)
                    .map(earlier -> replay(earlier, amount, phoneE164))
                    .orElseThrow(() -> e);
        }

        // A replay re-enters here too: the claim makes dispatch a no-op once sent.
        if (!requireApproval && livePayoutsEnabled) return dispatch(w.getId());
        return w;
    }

    private WithdrawalRequest replay(WithdrawalRequest earlier, long amount, String phoneE164) {
        if (earlier.getAmount() != amount || !earlier.getPhone().equals(phoneE164)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This withdrawal was already submitted with a different amount or number. Start a new withdrawal instead.");
        }
        return earlier;
    }

    /** Largest amount whose amount + estimated fee fits within {@code available}. */
    public long maxWithdrawable(long available) {
        long a = Math.min(maxAmount, Math.floorDiv(available * 100, 100 + feePercent));
        while (a > 0 && a + estimateFee(a) > available) a--;
        return Math.max(0, a);
    }

    String insufficientBalanceMessage(long amount, long fee, long available) {
        String msg = "Insufficient balance: " + ugx(amount) + " plus the " + ugx(fee) + " gateway fee comes to "
                + ugx(amount + fee) + ", but your available balance is " + ugx(available) + ".";
        long max = maxWithdrawable(available);
        return max >= minAmount
                ? msg + " The most you can withdraw right now is " + ugx(max) + "."
                : msg + " That isn't enough to cover the minimum withdrawal and its fee yet.";
    }

    long estimateFee(long amount) {
        // Must cover the real charge: any shortfall is settled after the money has left.
        return Math.max(500, (amount * feePercent + 99) / 100);
    }

    /**
     * Every tenant payout draws on the one shared platform MarzPay balance. While
     * that balance disagrees with our books (e.g. money taken out on the MarzPay
     * dashboard), a payout could spend another tenant's money — so none go out.
     */
    private void requireReconciledProvider() {
        if (PlatformEarningsAccount.RECON_DISCREPANCY.equals(platformEarnings.account().getReconcileStatus())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Withdrawals are temporarily paused while platform balances are being reconciled. Please try again later.");
        }
    }

    // ---------- approval & dispatch ----------

    /** Admin approval → sends the payout (or marks it for manual payout when live payouts are off). */
    public WithdrawalRequest approve(Long withdrawalId, Long adminId) {
        requireReconciledProvider();
        WithdrawalRequest w = inNewTransaction(() -> {
            WithdrawalRequest locked = withdrawals.findByIdForUpdate(withdrawalId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Withdrawal not found"));
            if (!WithdrawalRequest.PENDING_APPROVAL.equals(locked.getStatus())
                    && !WithdrawalRequest.REQUESTED.equals(locked.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Withdrawal is not awaiting approval");
            }
            locked.setApprovedBy(adminId);
            locked.setApprovedAt(OffsetDateTime.now());
            if (!livePayoutsEnabled) locked.setStatus(WithdrawalRequest.PROCESSING);
            return withdrawals.save(locked);
        });
        return livePayoutsEnabled ? dispatch(w.getId()) : w;
    }

    /**
     * Sends one payout via the PLATFORM MarzPay account (payouts only ever come from
     * platform-held funds — own-gateway money never enters our wallet). The claim
     * commits first, so exactly one caller sends it and the withdrawal is durably
     * PROCESSING before money moves; the provider call runs outside any transaction.
     */
    WithdrawalRequest dispatch(Long withdrawalId) {
        if (PlatformEarningsAccount.RECON_DISCREPANCY.equals(platformEarnings.account().getReconcileStatus())) {
            // Stays REQUESTED with funds reserved; an admin approves it once reconciled.
            log.warn("Payout {} held: platform reconciliation discrepancy", withdrawalId);
            return withdrawals.findById(withdrawalId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Withdrawal not found"));
        }
        boolean claimed = Boolean.TRUE.equals(inNewTransaction(() -> withdrawals.claimDispatch(withdrawalId, OffsetDateTime.now()) == 1));
        WithdrawalRequest w = withdrawals.findById(withdrawalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Withdrawal not found"));
        if (!claimed) return w; // already sent, final, or not dispatchable

        JsonNode response = null;
        String uncertain = null;
        try {
            var creds = gatewayConfig.credentialsFor(null, TenantGatewayConfig.MODE_PLATFORM);
            String callbackUrl = props.getPublicBaseUrl() + "/api/payments/marzpay/callback";
            response = marzPay.sendMoney(creds, w.getAmount(), w.getPhone(), w.getPayoutRef(),
                    "UG", "WiFi earnings withdrawal", callbackUrl);
        } catch (MarzPayClient.MarzPayException e) {
            // A timeout/5xx might still have created the payout — never retry blindly.
            uncertain = "Dispatch returned HTTP " + e.getStatus() + " — awaiting provider confirmation";
            log.error("Payout dispatch for {} returned HTTP {}", w.getPayoutRef(), e.getStatus());
        } catch (RuntimeException e) {
            uncertain = "Dispatch outcome unknown — awaiting provider confirmation";
            log.error("Payout dispatch for {} failed with an unknown outcome", w.getPayoutRef(), e);
        }

        final JsonNode res = response;
        final String note = uncertain;
        try {
            return inNewTransaction(() -> recordDispatch(withdrawalId, res, note));
        } catch (RuntimeException e) {
            // The withdrawal is committed as PROCESSING; callbacks and the sweeper finish it.
            log.error("Recording dispatch outcome for {} failed; left for reconciliation", w.getPayoutRef(), e);
            return withdrawals.findById(withdrawalId).orElse(w);
        }
    }

    private WithdrawalRequest recordDispatch(Long withdrawalId, JsonNode res, String uncertain) {
        WithdrawalRequest w = withdrawals.findByIdForUpdate(withdrawalId).orElseThrow();
        if (uncertain != null) {
            if (WithdrawalRequest.PROCESSING.equals(w.getStatus())) w.setFailureReason(uncertain);
            return withdrawals.save(w);
        }
        var txn = res.path("data").path("transaction");
        if (w.getProviderUuid() == null && txn.path("uuid").isString()) w.setProviderUuid(txn.path("uuid").asString());
        var wd = res.path("data").path("withdrawal").isMissingNode()
                ? res.path("data").path("disbursement") : res.path("data").path("withdrawal");
        var charge = wd.path("charge").path("raw");
        if (charge.isNumber()) applyActualFee(w, charge.longValue());
        return withdrawals.save(w);
    }

    /**
     * Brings the withdrawal and wallet in line with the provider's actual charge.
     * Caller holds the withdrawal row lock. Never throws on balance — the money has
     * already gone — and is a no-op once applied.
     */
    private void applyActualFee(WithdrawalRequest w, long actualFee) {
        long delta = actualFee - w.getFee();
        if (delta == 0) return;
        Long tenantId = w.getTenant().getId();
        String ref = "FEEADJ:" + w.getPayoutRef();
        if (WithdrawalRequest.COMPLETED.equals(w.getStatus())) {
            walletService.settleCompletedPayoutFee(tenantId, delta, ref, w.getId());
        } else if (WithdrawalRequest.PROCESSING.equals(w.getStatus())) {
            walletService.adjustReservation(tenantId, delta, ref, w.getId());
        } else {
            log.warn("Fee for {} reported after it ended {}; not applied", w.getPayoutRef(), w.getStatus());
            return;
        }
        w.setFee(actualFee);
        w.setTotalDeduction(w.getAmount() + actualFee);
    }

    // ---------- provider outcome ----------

    /** MarzPay disbursement callback — keyed by our payoutRef echoed in provider_reference. */
    @Transactional
    public void processDisbursementCallback(JsonNode payload) {
        var txn = payload.path("transaction");
        String ourRef = txn.path("provider_reference").asString(null);
        String status = txn.path("status").asString("").toLowerCase();
        String uuid = txn.path("uuid").asString(null);
        if (ourRef == null) {
            log.warn("Disbursement callback without provider_reference — ignored");
            return;
        }
        String providerTx = payload.path("disbursement").path("provider_transaction_id").asString(null);
        applyProviderOutcome(ourRef, status, uuid, providerTx);
    }

    /** Applies a provider-reported payout status. Caller supplies the transaction. */
    private void applyProviderOutcome(String payoutRef, String status, String uuid, String providerTx) {
        WithdrawalRequest w = withdrawals.findByPayoutRefForUpdate(payoutRef).orElse(null);
        if (w == null) {
            // Every payout we send is committed first, so this is not one of ours.
            log.error("Disbursement outcome for unknown payout ref {} (status {}) — investigate", payoutRef, status);
            return;
        }
        if (!WithdrawalRequest.PROCESSING.equals(w.getStatus())) {
            return; // final already (idempotent), or never sent
        }
        if (uuid != null && !uuid.isBlank()) w.setProviderUuid(uuid);
        if (providerTx != null) w.setProviderTransactionId(providerTx);

        if ("completed".equals(status) || "successful".equals(status)) {
            w.setStatus(WithdrawalRequest.COMPLETED);
            w.setCompletedAt(OffsetDateTime.now());
            w.setFailureReason(null);
            walletService.finalizeWithdrawal(w.getTenant().getId(), w.getTotalDeduction(),
                    "PAY:" + w.getPayoutRef(), w.getId());
        } else if ("failed".equals(status) || "cancelled".equals(status) || "reversed".equals(status)) {
            w.setStatus(WithdrawalRequest.FAILED);
            w.setCompletedAt(OffsetDateTime.now());
            w.setFailureReason("Provider status: " + status);
            walletService.releaseWithdrawal(w.getTenant().getId(), w.getTotalDeduction(),
                    "REL:" + w.getPayoutRef(), w.getId());
        }
        withdrawals.save(w);
    }

    /** Re-checks sent payouts the provider hasn't confirmed, through the callback path. */
    @Scheduled(fixedDelayString = "${withdrawals.recheck-interval-ms:120000}")
    public void scheduledPayoutRecheck() {
        leaderLockService.executeIfLeader(LeaderLockService.JOB_WITHDRAWAL_RECHECK, 300, 60, this::recheckStuckPayouts);
    }

    void recheckStuckPayouts() {
        var stuck = withdrawals.findTop50ByStatusAndDispatchedAtBeforeOrderByDispatchedAtAsc(
                WithdrawalRequest.PROCESSING, OffsetDateTime.now().minusSeconds(recheckAfterSeconds));
        if (stuck.isEmpty() || !gatewayConfig.platformConfigured()) return;
        var creds = gatewayConfig.credentialsFor(null, TenantGatewayConfig.MODE_PLATFORM);
        for (WithdrawalRequest w : stuck) {
            try {
                JsonNode res = marzPay.getTransaction(creds, w.getPayoutRef());
                JsonNode payload = res.has("data") && res.get("data").isObject() ? res.get("data") : res;
                var txn = payload.path("transaction");
                String status = txn.path("status").asString("").toLowerCase();
                String uuid = txn.path("uuid").asString(null);
                String providerTx = payload.path("disbursement").path("provider_transaction_id").asString(null);
                inNewTransaction(() -> { applyProviderOutcome(w.getPayoutRef(), status, uuid, providerTx); return null; });
            } catch (MarzPayClient.MarzPayException e) {
                if (e.isNotFound()) {
                    // No provider record: unresolved, never auto-released or re-sent.
                    flag(w.getId(), "Provider has no record of this payout — needs manual review");
                    log.warn("Provider has no record of payout {} — left for manual review", w.getPayoutRef());
                } else {
                    log.warn("Payout recheck for {} failed: {}", w.getPayoutRef(), e.getMessage());
                }
            } catch (RuntimeException e) {
                log.warn("Payout recheck for {} failed: {}", w.getPayoutRef(), e.getMessage());
            }
        }
    }

    private void flag(Long withdrawalId, String reason) {
        inNewTransaction(() -> withdrawals.findByIdForUpdate(withdrawalId)
                .filter(w -> WithdrawalRequest.PROCESSING.equals(w.getStatus()))
                .map(w -> { w.setFailureReason(reason); return withdrawals.save(w); })
                .orElse(null));
    }

    // ---------- cancel / fail ----------

    /** Tenant cancels their own not-yet-dispatched request; reservation released. */
    @Transactional
    public WithdrawalRequest cancel(Long tenantId, Long withdrawalId) {
        WithdrawalRequest w = withdrawals.findByIdForUpdate(withdrawalId)
                .filter(found -> found.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Withdrawal not found"));
        if (w.getDispatchedAt() != null || (!WithdrawalRequest.PENDING_APPROVAL.equals(w.getStatus())
                && !WithdrawalRequest.REQUESTED.equals(w.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only unprocessed withdrawals can be cancelled");
        }
        w.setStatus(WithdrawalRequest.CANCELLED);
        w.setCompletedAt(OffsetDateTime.now());
        walletService.releaseWithdrawal(tenantId, w.getTotalDeduction(), "REL:" + w.getPayoutRef(), w.getId());
        return withdrawals.save(w);
    }

    /** Admin marks a payout failed after manual investigation; releases reservation. */
    @Transactional
    public WithdrawalRequest fail(Long withdrawalId, String reason) {
        WithdrawalRequest w = withdrawals.findByIdForUpdate(withdrawalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Withdrawal not found"));
        if (WithdrawalRequest.COMPLETED.equals(w.getStatus()) || WithdrawalRequest.FAILED.equals(w.getStatus())
                || WithdrawalRequest.CANCELLED.equals(w.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Withdrawal already final");
        }
        w.setStatus(WithdrawalRequest.FAILED);
        w.setFailureReason(reason);
        w.setCompletedAt(OffsetDateTime.now());
        walletService.releaseWithdrawal(w.getTenant().getId(), w.getTotalDeduction(), "REL:" + w.getPayoutRef(), w.getId());
        return withdrawals.save(w);
    }

    public List<WithdrawalRequest> adminList(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return withdrawals.findAllByOrderByCreatedAtDesc();
        }
        return withdrawals.findAllByStatusIn(statuses);
    }

    // ---------- helpers ----------

    /** Runs {@code work} in its own transaction that commits on return. */
    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> work.get());
    }

    static String normalizeKey(String key) {
        if (key == null || key.isBlank()) return null;
        String k = key.trim();
        if (!IDEMPOTENCY_KEY.matcher(k).matches()) {
            throw badRequest("Idempotency-Key must be 8–64 letters, digits, '-' or '_'");
        }
        return k;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String ugx(long amount) {
        return String.format("UGX %,d", amount);
    }

    private String toE164(String phone) {
        String clean = phone.replaceAll("[^0-9+]", "");
        if (clean.startsWith("+")) return clean;
        if (clean.startsWith("256")) return "+" + clean;
        if (clean.startsWith("0")) return "+256" + clean.substring(1);
        return "+256" + clean;
    }
}
