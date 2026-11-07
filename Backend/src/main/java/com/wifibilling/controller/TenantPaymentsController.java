package com.wifibilling.controller;

import com.wifibilling.model.Transaction;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.util.PhoneMatch;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
                          String voucher, String packageName, String router, OffsetDateTime createdAt, String notes,
                          String mobileMoneyId) {}

    public record Summary(long payments, long successful, BigDecimal totalPaid,
                          OffsetDateTime firstPaidAt, OffsetDateTime lastPaidAt) {}

    public record Search(String phone, Summary summary, List<Payment> payments,
                         int page, int totalPages, long totalElements) {}

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

    /**
     * Every payment ever made from one phone number, any status, newest first, a page
     * at a time, with totals over all of them. The number may be typed in any format.
     */
    @GetMapping("/search")
    @Transactional(readOnly = true)
    public Search search(@AuthenticationPrincipal TenantPrincipal p,
                         @RequestParam String phone,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "50") int size) {
        PhoneMatch match = PhoneMatch.of(phone).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a phone number with at least 7 digits."));
        Page<Transaction> rows = transactions.searchByPhone(p.getTenantId(), match.exact(), match.suffix(),
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(200, size)),
                        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        Object[] s = transactions.phoneSummary(p.getTenantId(), match.exact(), match.suffix()).get(0);
        Summary summary = new Summary(((Number) s[0]).longValue(), ((Number) s[1]).longValue(),
                s[2] instanceof BigDecimal paid ? paid : new BigDecimal(String.valueOf(s[2])),
                (OffsetDateTime) s[3], (OffsetDateTime) s[4]);
        return new Search(match.display(), summary, rows.map(TenantPaymentsController::toPayment).getContent(),
                rows.getNumber(), rows.getTotalPages(), rows.getTotalElements());
    }

    private static Payment toPayment(Transaction t) {
        return new Payment(t.getId(), t.getReference(), t.getNetwork(), t.getAmount(), t.getStatus(), t.getPhone(),
                t.getVoucherCode(),
                t.getPackagePlan() != null ? t.getPackagePlan().getName() : null,
                t.getRouter() != null ? t.getRouter().getName() : null,
                t.getCreatedAt(), t.getNotes(), t.getProviderTransactionId());
    }
}
