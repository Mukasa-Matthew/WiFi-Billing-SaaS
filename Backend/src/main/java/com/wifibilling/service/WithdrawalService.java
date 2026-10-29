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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Tenant withdrawal lifecycle against the internal wallet.
 * Reserve → (approval) → send-money → finalize on confirmed completion / release on
 * verified failure. The payout reference is a unique UUID so retries and network
 * timeouts can never produce a second payout — reconciliation confirms the first.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final WithdrawalRequestRepository withdrawals;
    private final WalletService walletService;
    private final GatewayConfigService gatewayConfig;
    private final MarzPayClient marzPay;
    private final PlatformProperties props;
    private final TenantRepository tenantRepository;

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

    public boolean approvalRequired() { return requireApproval; }
    public boolean livePayoutsEnabled() { return livePayoutsEnabled; }

    public record Limits(long min, long max, boolean approvalRequired, boolean livePayouts) {}
    public Limits limits() { return new Limits(minAmount, maxAmount, requireApproval, livePayoutsEnabled); }

    @Transactional(readOnly = true)
    public List<WithdrawalRequest> list(Long tenantId) {
        return withdrawals.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /**
     * Creates a withdrawal request and reserves the funds atomically under the
     * wallet lock — concurrent requests cannot double-spend the same balance.
     */
    @Transactional
    public WithdrawalRequest request(Long tenantId, long amount, String phone) {
        if (amount < minAmount) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount below minimum");
        if (amount > maxAmount) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount above maximum");
        if (phone == null || !phone.replaceAll("[^0-9]", "").matches("(256|0)?7[0-9]{8}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid mobile money number");
        }
        if (withdrawals.countByTenantIdAndStatusIn(tenantId,
                List.of(WithdrawalRequest.REQUESTED, WithdrawalRequest.PENDING_APPROVAL, WithdrawalRequest.PROCESSING)) >= maxOpenPerTenant) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have a withdrawal in progress");
        }

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));

        String payoutRef = UUID.randomUUID().toString();
        // Estimated fee: actual charge comes back from MarzPay and is reconciled.
        // We reserve amount + a conservative fee estimate; the ledger fixes the exact total later.
        long estimatedFee = estimateFee(amount);
        WithdrawalRequest w = WithdrawalRequest.builder()
                .tenant(tenant)
                .amount(amount)
                .fee(estimatedFee)
                .totalDeduction(amount + estimatedFee)
                .phone(toE164(phone))
                .payoutRef(payoutRef)
                .status(requireApproval ? WithdrawalRequest.PENDING_APPROVAL : WithdrawalRequest.REQUESTED)
                .build();
        w = withdrawals.save(w);

        walletService.reserveForWithdrawal(tenantId, w.getTotalDeduction(), "RES:" + payoutRef, w.getId());
        if (!requireApproval && livePayoutsEnabled) {
            dispatchPayout(w);
            w = withdrawals.save(w);
        }
        return w;
    }

    private long estimateFee(long amount) {
        // Conservative buffer: real charge is set from the provider's response.
        return Math.max(500, Math.round(amount * 0.01));
    }

    /** Admin (or auto) approval → attempts the payout. */
    @Transactional
    public WithdrawalRequest approve(Long withdrawalId, Long adminId) {
        WithdrawalRequest w = withdrawals.findById(withdrawalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Withdrawal not found"));
        if (!WithdrawalRequest.PENDING_APPROVAL.equals(w.getStatus()) && !WithdrawalRequest.REQUESTED.equals(w.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Withdrawal is not awaiting approval");
        }
        w.setApprovedBy(adminId);
        w.setApprovedAt(OffsetDateTime.now());
        if (!livePayoutsEnabled) {
            w.setStatus(WithdrawalRequest.PROCESSING);
            return withdrawals.save(w);
        }
        dispatchPayout(w);
        return withdrawals.save(w);
    }

    /** Sends the disbursement via the PLATFORM MarzPay account (payouts only ever
     *  come from platform-held funds — own-gateway money never enters our wallet). */
    private void dispatchPayout(WithdrawalRequest w) {
        var creds = gatewayConfig.credentialsFor(w.getTenant(), TenantGatewayConfig.MODE_PLATFORM);
        String callbackUrl = props.getPublicBaseUrl() + "/api/payments/marzpay/callback";
        try {
            JsonNode res = marzPay.sendMoney(creds, w.getAmount(), w.getPhone(), w.getPayoutRef(),
                    "UG", "WiFi earnings withdrawal", callbackUrl);
            w.setStatus(WithdrawalRequest.PROCESSING);
            var txn = res.path("data").path("transaction");
            if (!txn.path("uuid").isNull()) w.setProviderUuid(txn.path("uuid").asText());
            var wd = res.path("data").path("withdrawal").isMissingNode()
                    ? res.path("data").path("disbursement") : res.path("data").path("withdrawal");
            var charge = wd.path("charge").path("raw");
            if (charge.isNumber() && charge.longValue() != w.getFee()) {
                // Reconcile reservation to the actual provider charge.
                long delta = charge.longValue() - w.getFee();
                w.setFee(charge.longValue());
                w.setTotalDeduction(w.getAmount() + w.getFee());
                if (delta != 0) walletService.adjust(w.getTenant().getId(), -delta, "FEEADJ:" + w.getPayoutRef(),
                        "Payout fee reconciliation");
            }
        } catch (MarzPayClient.MarzPayException e) {
            // A timeout/5xx might still have created the payout — do NOT retry blindly.
            // Mark PROCESSING and let reconciliation confirm the true state.
            w.setStatus(WithdrawalRequest.PROCESSING);
            w.setFailureReason("Dispatch returned HTTP " + e.getStatus() + " — awaiting reconciliation");
            log.error("Payout dispatch for {} returned HTTP {}", w.getPayoutRef(), e.getStatus());
        }
    }

    /** MarzPay disbursement callback — keyed by our payoutRef echoed in provider_reference. */
    @Transactional
    public void processDisbursementCallback(JsonNode payload) {
        var txn = payload.path("transaction");
        String ourRef = txn.path("provider_reference").asText(null);
        String status = txn.path("status").asText("").toLowerCase();
        String uuid = txn.path("uuid").asText(null);
        if (ourRef == null) {
            log.warn("Disbursement callback without provider_reference — ignored");
            return;
        }
        WithdrawalRequest w = withdrawals.findByPayoutRef(ourRef).orElse(null);
        if (w == null) {
            log.warn("Disbursement callback for unknown payout ref {} — ignored", ourRef);
            return;
        }
        if (WithdrawalRequest.COMPLETED.equals(w.getStatus()) || WithdrawalRequest.FAILED.equals(w.getStatus())
                || WithdrawalRequest.CANCELLED.equals(w.getStatus())) {
            return; // already final — idempotent
        }
        if (uuid != null && !uuid.isBlank()) w.setProviderUuid(uuid);
        String providerTx = payload.path("disbursement").path("provider_transaction_id").asText(null);
        if (providerTx != null) w.setProviderTransactionId(providerTx);

        if ("completed".equals(status)) {
            w.setStatus(WithdrawalRequest.COMPLETED);
            w.setCompletedAt(OffsetDateTime.now());
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

    /** Tenant cancels their own not-yet-dispatched request; reservation released. */
    @Transactional
    public WithdrawalRequest cancel(Long tenantId, Long withdrawalId) {
        WithdrawalRequest w = withdrawals.findByIdAndTenantId(withdrawalId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Withdrawal not found"));
        if (!WithdrawalRequest.PENDING_APPROVAL.equals(w.getStatus()) && !WithdrawalRequest.REQUESTED.equals(w.getStatus())) {
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
        WithdrawalRequest w = withdrawals.findById(withdrawalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Withdrawal not found"));
        if (WithdrawalRequest.COMPLETED.equals(w.getStatus()) || WithdrawalRequest.FAILED.equals(w.getStatus())) {
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

    private String toE164(String phone) {
        String clean = phone.replaceAll("[^0-9+]", "");
        if (clean.startsWith("+")) return clean;
        if (clean.startsWith("256")) return "+" + clean;
        if (clean.startsWith("0")) return "+256" + clean.substring(1);
        return "+256" + clean;
    }
}
