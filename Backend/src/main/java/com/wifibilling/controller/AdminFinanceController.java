package com.wifibilling.controller;

import com.wifibilling.model.*;
import com.wifibilling.repository.*;
import com.wifibilling.service.WithdrawalService;
import com.wifibilling.service.payment.MarzPayClient;
import com.wifibilling.service.GatewayConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Super-admin financial oversight: platform-managed collections, tenant wallet
 * liabilities, provider balance, and withdrawal review. Read-mostly; the only
 * mutations are the withdrawal state transitions (approve/fail) which are
 * auditable ledger events.
 */
@RestController
@RequestMapping("/api/admin/finance")
@PreAuthorize("hasAuthority('ROLE_SUPERADMIN')")
@RequiredArgsConstructor
public class AdminFinanceController {

    private final TransactionRepository transactions;
    private final WalletAccountRepository wallets;
    private final WithdrawalService withdrawalService;
    private final GatewayConfigService gatewayConfig;
    private final MarzPayClient marzPay;
    private final TenantRepository tenantRepository;

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        var all = wallets.findAll();
        long totalLiability = all.stream().mapToLong(WalletAccount::getAvailableBalance).sum();
        long totalReserved = all.stream().mapToLong(WalletAccount::getReservedBalance).sum();
        long totalCollected = all.stream().mapToLong(WalletAccount::getTotalCollected).sum();
        long totalFees = all.stream().mapToLong(WalletAccount::getTotalFees).sum();
        long totalWithdrawn = all.stream().mapToLong(WalletAccount::getTotalWithdrawn).sum();

        long pendingWithdrawals = withdrawalService.adminList(
                List.of(WithdrawalRequest.PENDING_APPROVAL, WithdrawalRequest.REQUESTED, WithdrawalRequest.PROCESSING))
                .size();

        // Actual provider balance — best-effort (requires IP whitelist on MarzPay).
        Long providerBalance = null;
        String providerError = null;
        if (gatewayConfig.platformConfigured()) {
            try {
                var res = marzPay.getBalance(
                        gatewayConfig.credentialsFor(null, TenantGatewayConfig.MODE_PLATFORM), "UG");
                providerBalance = res.path("data").path("account").path("available_balance")
                        .path("raw").longValue();
            } catch (Exception e) {
                providerError = e.getMessage();
            }
        }

        return Map.of(
                "walletCount", all.size(),
                "tenantLiabilities", totalLiability,
                "reservedFunds", totalReserved,
                "totalCollected", totalCollected,
                "totalFees", totalFees,
                "totalWithdrawn", totalWithdrawn,
                "pendingWithdrawals", pendingWithdrawals,
                "providerBalance", providerBalance == null ? -1 : providerBalance,
                "providerError", providerError == null ? "" : providerError,
                "platformConfigured", gatewayConfig.platformConfigured()
        );
    }

    public record WalletRow(Long tenantId, String businessName, Long available, Long pending,
                            Long reserved, Long totalCollected, Long totalWithdrawn) {}

    @GetMapping("/wallets")
    public List<WalletRow> walletList() {
        return wallets.findAll().stream().map(w -> {
            var t = w.getTenant();
            return new WalletRow(t.getId(), t.getName(), w.getAvailableBalance(), w.getPendingBalance(),
                    w.getReservedBalance(), w.getTotalCollected(), w.getTotalWithdrawn());
        }).toList();
    }

    public record AdminWithdrawalRow(Long id, Long tenantId, String businessName, Long amount, Long fee,
                                     Long totalDeduction, String phone, String status,
                                     String failureReason, String createdAt) {}

    @GetMapping("/withdrawals")
    public List<AdminWithdrawalRow> withdrawals(@RequestParam(defaultValue = "") String status) {
        List<String> statuses = status.isBlank()
                ? List.of(WithdrawalRequest.PENDING_APPROVAL, WithdrawalRequest.REQUESTED,
                          WithdrawalRequest.PROCESSING, WithdrawalRequest.COMPLETED,
                          WithdrawalRequest.FAILED, WithdrawalRequest.CANCELLED)
                : List.of(status.split(","));
        return withdrawalService.adminList(statuses).stream().map(w -> {
            var t = w.getTenant();
            return new AdminWithdrawalRow(w.getId(), t.getId(), t.getName(), w.getAmount(), w.getFee(),
                    w.getTotalDeduction(), w.getPhone(), w.getStatus(), w.getFailureReason(),
                    w.getCreatedAt() != null ? w.getCreatedAt().toString() : null);
        }).toList();
    }

    @PostMapping("/withdrawals/{id}/approve")
    public Map<String, String> approve(@PathVariable Long id) {
        var w = withdrawalService.approve(id, null);
        return Map.of("status", w.getStatus());
    }

    public record FailRequest(String reason) {}

    @PostMapping("/withdrawals/{id}/fail")
    public Map<String, String> fail(@PathVariable Long id, @RequestBody(required = false) FailRequest req) {
        var w = withdrawalService.fail(id, req != null ? req.reason() : "Rejected by administrator");
        return Map.of("status", w.getStatus());
    }
}
