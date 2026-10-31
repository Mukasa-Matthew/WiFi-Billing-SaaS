package com.wifibilling.controller;

import com.wifibilling.model.WalletAccount;
import com.wifibilling.model.WalletLedgerEntry;
import com.wifibilling.model.WithdrawalRequest;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.OtpService;
import com.wifibilling.service.WalletService;
import com.wifibilling.service.WithdrawalService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Tenant wallet: balances, auditable ledger activity, and withdrawals against
 * platform-gateway earnings. Strictly scoped to the authenticated tenant.
 */
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final WithdrawalService withdrawalService;
    private final OtpService otpService;
    private final com.wifibilling.repository.TenantRepository tenantRepository;

    @org.springframework.beans.factory.annotation.Value("${withdrawals.otp-required:true}")
    private boolean otpRequired;

    public record WalletView(
            Long available, Long pending, Long reserved,
            Long totalCollected, Long totalFees, Long totalWithdrawn,
            String currency,
            long openWithdrawals,
            WithdrawalService.Limits limits,
            /** Most that can be requested now: amount + estimated fee within available. */
            long maxWithdrawable
    ) {}

    @GetMapping
    public WalletView summary(@AuthenticationPrincipal TenantPrincipal p) {
        WalletAccount w = walletService.wallet(p.getTenantId());
        List<WithdrawalRequest> all = withdrawalService.list(p.getTenantId());
        long open = all.stream().filter(r -> List.of(WithdrawalRequest.REQUESTED,
                WithdrawalRequest.PENDING_APPROVAL, WithdrawalRequest.PROCESSING).contains(r.getStatus())).count();
        return new WalletView(w.getAvailableBalance(), w.getPendingBalance(), w.getReservedBalance(),
                w.getTotalCollected(), w.getTotalFees(), w.getTotalWithdrawn(),
                w.getCurrency(), open, withdrawalService.limits(),
                withdrawalService.maxWithdrawable(w.getAvailableBalance()));
    }

    public record LedgerView(Long id, String type, Long amount,
                             Long availableAfter, Long pendingAfter, Long reservedAfter,
                             String externalRef, Long transactionId, Long withdrawalId,
                             String note, OffsetDateTime createdAt) {}

    @GetMapping("/activity")
    public List<LedgerView> activity(@AuthenticationPrincipal TenantPrincipal p) {
        return walletService.recentActivity(p.getTenantId()).stream()
                .map(e -> new LedgerView(e.getId(), e.getType(), e.getAmount(),
                        e.getAvailableAfter(), e.getPendingAfter(), e.getReservedAfter(),
                        e.getExternalRef(), e.getTransactionId(), e.getWithdrawalId(),
                        e.getNote(), e.getCreatedAt()))
                .toList();
    }

    public record WithdrawalView(Long id, Long amount, Long fee, Long totalDeduction,
                                 String phone, String status, String failureReason, String payoutRef,
                                 OffsetDateTime createdAt, OffsetDateTime completedAt) {}

    @GetMapping("/withdrawals")
    public List<WithdrawalView> withdrawals(@AuthenticationPrincipal TenantPrincipal p) {
        return withdrawalService.list(p.getTenantId()).stream().map(WalletController::toView).toList();
    }

    public record WithdrawalCreate(Long amount, String phone, String otp) {}

    public record OtpResponse(boolean sent, String devCode) {}

    /** Step 1 of withdrawal: email a verification code to the tenant's registered email. */
    @PostMapping("/withdrawals/otp")
    public OtpResponse requestWithdrawalOtp(@AuthenticationPrincipal TenantPrincipal p) {
        var tenant = tenantRepository.findById(p.getTenantId())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Tenant not found"));
        String devCode = otpService.issue(tenant.getEmail(), com.wifibilling.model.EmailOtp.PURPOSE_WITHDRAWAL,
                tenant.getId(), tenant.getName(),
                "You requested a wallet withdrawal. Enter this code to confirm it.");
        return new OtpResponse(true, devCode);
    }

    /**
     * Idempotent per {@code Idempotency-Key}: resubmitting the same key (a retry after
     * a dropped connection, a double tap) returns the original withdrawal — before the
     * single-use OTP is checked — and never creates a second payout.
     */
    @PostMapping("/withdrawals")
    public WithdrawalView requestWithdrawal(@AuthenticationPrincipal TenantPrincipal p,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                            @RequestBody WithdrawalCreate req) {
        if (req == null || req.amount() == null || req.amount() <= 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "A positive amount is required");
        }
        var earlier = withdrawalService.findByIdempotencyKey(p.getTenantId(), idempotencyKey);
        if (earlier.isEmpty() && otpRequired) {
            otpService.verify(p.getEmail(), com.wifibilling.model.EmailOtp.PURPOSE_WITHDRAWAL, req.otp());
        }
        // A replay still goes through request(), which rejects a changed amount or number.
        return toView(withdrawalService.request(p.getTenantId(), req.amount(), req.phone(), idempotencyKey));
    }

    @PostMapping("/withdrawals/{id}/cancel")
    public WithdrawalView cancel(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id) {
        return toView(withdrawalService.cancel(p.getTenantId(), id));
    }

    private static WithdrawalView toView(WithdrawalRequest w) {
        return new WithdrawalView(w.getId(), w.getAmount(), w.getFee(), w.getTotalDeduction(),
                w.getPhone(), w.getStatus(), w.getFailureReason(), w.getPayoutRef(),
                w.getCreatedAt(), w.getCompletedAt());
    }
}
