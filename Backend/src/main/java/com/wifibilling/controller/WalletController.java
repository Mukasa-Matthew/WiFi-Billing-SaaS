package com.wifibilling.controller;

import com.wifibilling.model.WalletAccount;
import com.wifibilling.model.WalletLedgerEntry;
import com.wifibilling.model.WithdrawalRequest;
import com.wifibilling.security.TenantPrincipal;
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

    public record WalletView(
            Long available, Long pending, Long reserved,
            Long totalCollected, Long totalFees, Long totalWithdrawn,
            String currency,
            long openWithdrawals,
            WithdrawalService.Limits limits
    ) {}

    @GetMapping
    public WalletView summary(@AuthenticationPrincipal TenantPrincipal p) {
        WalletAccount w = walletService.wallet(p.getTenantId());
        List<WithdrawalRequest> all = withdrawalService.list(p.getTenantId());
        long open = all.stream().filter(r -> List.of(WithdrawalRequest.REQUESTED,
                WithdrawalRequest.PENDING_APPROVAL, WithdrawalRequest.PROCESSING).contains(r.getStatus())).count();
        return new WalletView(w.getAvailableBalance(), w.getPendingBalance(), w.getReservedBalance(),
                w.getTotalCollected(), w.getTotalFees(), w.getTotalWithdrawn(),
                w.getCurrency(), open, withdrawalService.limits());
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
                                 String phone, String status, String failureReason,
                                 OffsetDateTime createdAt, OffsetDateTime completedAt) {}

    @GetMapping("/withdrawals")
    public List<WithdrawalView> withdrawals(@AuthenticationPrincipal TenantPrincipal p) {
        return withdrawalService.list(p.getTenantId()).stream().map(WalletController::toView).toList();
    }

    public record WithdrawalCreate(Long amount, String phone) {}

    @PostMapping("/withdrawals")
    public WithdrawalView requestWithdrawal(@AuthenticationPrincipal TenantPrincipal p,
                                            @RequestBody WithdrawalCreate req) {
        if (req == null || req.amount() == null || req.amount() <= 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "A positive amount is required");
        }
        return toView(withdrawalService.request(p.getTenantId(), req.amount(), req.phone()));
    }

    @PostMapping("/withdrawals/{id}/cancel")
    public WithdrawalView cancel(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id) {
        return toView(withdrawalService.cancel(p.getTenantId(), id));
    }

    private static WithdrawalView toView(WithdrawalRequest w) {
        return new WithdrawalView(w.getId(), w.getAmount(), w.getFee(), w.getTotalDeduction(),
                w.getPhone(), w.getStatus(), w.getFailureReason(), w.getCreatedAt(), w.getCompletedAt());
    }
}
