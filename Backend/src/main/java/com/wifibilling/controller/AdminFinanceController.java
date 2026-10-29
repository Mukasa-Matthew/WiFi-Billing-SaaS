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
    private final com.wifibilling.service.PlatformEarningsService platformEarnings;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

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
    public List<AdminWithdrawalRow> withdrawals(@RequestParam(defaultValue = "") String status,
                                               @RequestParam(required = false) Long tenantId) {
        List<String> statuses = status.isBlank()
                ? List.of(WithdrawalRequest.PENDING_APPROVAL, WithdrawalRequest.REQUESTED,
                          WithdrawalRequest.PROCESSING, WithdrawalRequest.COMPLETED,
                          WithdrawalRequest.FAILED, WithdrawalRequest.CANCELLED)
                : List.of(status.split(","));
        var stream = (tenantId != null
                ? withdrawalService.list(tenantId)
                : withdrawalService.adminList(statuses)).stream();

        if (tenantId != null && !status.isBlank()) {
            stream = stream.filter(w -> statuses.contains(w.getStatus()));
        }

        return stream.map(w -> {
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

    // ---------- platform subscription earnings ----------

    @GetMapping("/platform")
    public Map<String, Object> platform() {
        var a = platformEarnings.account();
        long tenantAvail = wallets.findAll().stream().mapToLong(WalletAccount::getAvailableBalance).sum();
        long tenantReserved = wallets.findAll().stream().mapToLong(WalletAccount::getReservedBalance).sum();
        var out = new java.util.HashMap<String, Object>();
        out.put("settledEarnings", a.getSettledEarnings());
        out.put("externalEarnings", a.getExternalEarnings());
        out.put("reservedEarnings", a.getReservedEarnings());
        out.put("totalEarned", a.getTotalEarned());
        out.put("totalFees", a.getTotalFees());
        out.put("totalWithdrawn", a.getTotalWithdrawn());
        out.put("providerBalance", a.getLastProviderBalance() == null ? -1 : a.getLastProviderBalance());
        out.put("lastSyncedAt", a.getLastSyncedAt() == null ? "" : a.getLastSyncedAt().toString());
        out.put("reconcileStatus", a.getReconcileStatus());
        out.put("reconcileNote", a.getReconcileNote() == null ? "" : a.getReconcileNote());
        out.put("balanceStale", platformEarnings.balanceStale(a));
        out.put("tenantAvailable", tenantAvail);
        out.put("tenantReserved", tenantReserved);
        out.put("safeWithdrawable", platformEarnings.safeWithdrawable());
        out.put("safetyBuffer", platformEarnings.safetyBuffer());
        out.put("payoutsEnabled", platformEarnings.payoutsEnabled());
        return out;
    }

    @PostMapping("/platform/reconcile")
    public Map<String, Object> reconcileNow() {
        var log = platformEarnings.reconcile();
        return Map.of(
                "providerBalance", log.getProviderBalance() == null ? -1 : log.getProviderBalance(),
                "expectedBalance", log.getExpectedBalance(),
                "difference", log.getDifference() == null ? 0 : log.getDifference(),
                "status", log.getStatus()
        );
    }

    public record PlatformPayoutRow(Long id, Long amount, Long fee, Long totalDeduction,
                                    String phone, String status, String payoutRef,
                                    String providerTransactionId, String failureReason,
                                    String createdAt, String completedAt) {}

    @GetMapping("/platform/payouts")
    public List<PlatformPayoutRow> platformPayouts() {
        return platformEarnings.recentPayouts().stream().map(p ->
                new PlatformPayoutRow(p.getId(), p.getAmount(), p.getFee(), p.getTotalDeduction(),
                        p.getPhone(), p.getStatus(), p.getPayoutRef(), p.getProviderTransactionId(),
                        p.getFailureReason(),
                        p.getCreatedAt() != null ? p.getCreatedAt().toString() : null,
                        p.getCompletedAt() != null ? p.getCompletedAt().toString() : null))
                .toList();
    }

    @GetMapping("/platform/reconciliation")
    public List<Map<String, Object>> reconciliationLog() {
        return platformEarnings.recentReconciliations().stream().map(l -> Map.<String, Object>of(
                "providerBalance", l.getProviderBalance() == null ? -1 : l.getProviderBalance(),
                "expectedBalance", l.getExpectedBalance(),
                "difference", l.getDifference() == null ? 0 : l.getDifference(),
                "status", l.getStatus(),
                "note", l.getNote() == null ? "" : l.getNote(),
                "createdAt", l.getCreatedAt().toString()
        )).toList();
    }

    public record PayoutRequest(Long amount, String phone, String password) {}

    /** Step-up auth: the super admin must re-enter their password to move money. */
    @PostMapping("/platform/payouts")
    public Map<String, Object> requestPayout(@RequestBody PayoutRequest req,
                                             @org.springframework.security.core.annotation.AuthenticationPrincipal
                                             com.wifibilling.security.TenantPrincipal principal) {
        if (req == null || req.amount() == null || req.phone() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "amount and phone are required");
        }
        var admin = tenantRepository.findById(principal.getTenantId())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "Admin not found"));
        if (req.password() == null || !passwordEncoder.matches(req.password(), admin.getPasswordHash())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Incorrect password");
        }
        var p = platformEarnings.requestPayout(req.amount(), req.phone(), principal.getTenantId());
        return Map.of("id", p.getId(), "status", p.getStatus(), "payoutRef", p.getPayoutRef());
    }
}
