package com.wifibilling.controller;

import com.wifibilling.model.Transaction;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.security.TenantPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/finance/payments")
@RequiredArgsConstructor
public class TenantPaymentsController {

    /** Hard ceiling, and the default when no limit is given (the original behaviour). */
    static final int MAX_LIMIT = 1000;

    private final TransactionRepository transactions;

    public record Payment(Long id, String reference, String method, BigDecimal amount, String status, String phone,
                          String voucher, String packageName, String router, OffsetDateTime createdAt, String notes) {}

    /**
     * Newest-first payments for the caller's tenant. Without {@code limit} this
     * returns the latest 1000 exactly as before; with it (1–1000) only that many
     * rows are read, e.g. {@code ?limit=6} for the dashboard's recent list.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public List<Payment> list(@AuthenticationPrincipal TenantPrincipal p,
                              @RequestParam(required = false) Integer limit) {
        List<Transaction> rows = limit == null
                ? transactions.findTop1000ByTenantIdOrderByCreatedAtDesc(p.getTenantId())
                : transactions.findByTenantIdOrderByCreatedAtDesc(p.getTenantId(),
                        PageRequest.of(0, Math.max(1, Math.min(MAX_LIMIT, limit))));
        return rows.stream().map(TenantPaymentsController::toPayment).toList();
    }

    private static Payment toPayment(Transaction t) {
        return new Payment(t.getId(), t.getReference(), t.getNetwork(), t.getAmount(), t.getStatus(), t.getPhone(),
                t.getVoucherCode(),
                t.getPackagePlan() != null ? t.getPackagePlan().getName() : null,
                t.getRouter() != null ? t.getRouter().getName() : null,
                t.getCreatedAt(), t.getNotes());
    }
}
