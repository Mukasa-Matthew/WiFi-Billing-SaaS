package com.wifibilling.controller;

import com.wifibilling.model.Transaction;
import com.wifibilling.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Platform-wide payment lookup for superadmin support: the "this client says
 * they paid" search. Matches phone fragments, voucher codes and every
 * reference id we or the gateway know — across all businesses at once.
 */
@RestController
@RequestMapping("/api/admin/transactions")
@RequiredArgsConstructor
public class AdminTransactionsController {

    private final TransactionRepository transactionRepository;

    public record TxRow(Long id, Long tenantId, String businessName, String routerName,
                        String phone, BigDecimal amount, String currency, String network,
                        String status, String voucherCode, String reference,
                        String externalReference, String providerTransactionId,
                        Long gatewayFee, Long netAmount, Long platformFee,
                        OffsetDateTime createdAt) {}

    public record TxPage(List<TxRow> rows, long total, int page, int pages,
                         long successful, long pending, long failed) {}

    @GetMapping
    @Transactional(readOnly = true)
    public TxPage search(@RequestParam(required = false) String query,
                         @RequestParam(required = false) String status,
                         @RequestParam(required = false) Long tenantId,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "25") int size) {
        String q = (query == null || query.isBlank()) ? null : query.trim();
        // Digits-only probe for phone matching — "0789...", "+256 789..." and
        // partials all reduce to their digits so stored formats don't matter.
        String digits = null;
        if (q != null) {
            String d = q.replaceAll("[^0-9]", "");
            // Ugandan numbers get typed as 07xx but stored as +2567xx (or the
            // reverse) — the last 9 digits are the significant part either way.
            if (d.length() > 9) d = d.substring(d.length() - 9);
            if (d.length() >= 3) digits = d;
        }
        String st = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        Page<Transaction> p = transactionRepository.searchAdmin(q, digits, st, tenantId,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)));

        List<TxRow> rows = p.getContent().stream().map(t -> new TxRow(
                t.getId(), t.getTenant().getId(), t.getTenant().getName(),
                t.getRouter() != null ? t.getRouter().getName() : null,
                t.getPhone(), t.getAmount(), t.getCurrency(), t.getNetwork(),
                t.getStatus(), t.getVoucherCode(), t.getReference(),
                t.getExternalReference(), t.getProviderTransactionId(),
                t.getGatewayFee(), t.getNetAmount(), t.getPlatformFee(),
                t.getCreatedAt())).toList();

        long successful = p.getContent().stream().filter(t -> "SUCCESSFUL".equals(t.getStatus())).count();
        long pending = p.getContent().stream().filter(t -> "PENDING".equals(t.getStatus())).count();
        long failed = p.getContent().stream().filter(t -> "FAILED".equals(t.getStatus())).count();

        return new TxPage(rows, p.getTotalElements(), p.getNumber(), p.getTotalPages(),
                successful, pending, failed);
    }
}
