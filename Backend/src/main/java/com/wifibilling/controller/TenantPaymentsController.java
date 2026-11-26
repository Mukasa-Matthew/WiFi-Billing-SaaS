package com.wifibilling.controller;

import com.wifibilling.model.Transaction;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.ReportingClock;
import com.wifibilling.util.MobileNetwork;
import com.wifibilling.util.PhoneMatch;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/finance/payments")
@RequiredArgsConstructor
public class TenantPaymentsController {

    /** Hard ceiling, and the default when no limit is given (the original behaviour). */
    static final int MAX_LIMIT = 1000;

    /** Longest date range one report or export may cover. */
    static final int MAX_RANGE_DAYS = 366;
    /** Most rows one CSV export may contain; a wider range must be narrowed. */
    static final int MAX_EXPORT_ROWS = 50_000;
    static final Set<String> STATUS_GROUPS = Set.of("ALL", "SUCCESSFUL", "PENDING", "FAILED");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final TransactionRepository transactions;
    private final ReportingClock clock;

    public record Payment(Long id, String reference, String method, BigDecimal amount, String status, String phone,
                          String voucher, String packageName, String router, OffsetDateTime createdAt, String notes,
                          String mobileMoneyId) {}

    public record Summary(long payments, long successful, BigDecimal totalPaid,
                          OffsetDateTime firstPaidAt, OffsetDateTime lastPaidAt) {}

    public record Search(String phone, Summary summary, List<Payment> payments,
                         int page, int totalPages, long totalElements) {}

    /**
     * Totals over a whole date range (every status). {@code collected} is successful money;
     * {@code mobileMoney} + {@code walkIn} split it by channel — mobile money on any network
     * is never walk-in (same rule as financial analysis).
     */
    public record RangeSummary(BigDecimal collected, BigDecimal mobileMoney, BigDecimal walkIn,
                               long total, long successful, long pending, long failed) {}

    public record Report(LocalDate from, LocalDate to, RangeSummary summary, List<Payment> payments,
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

    /**
     * Payments between two reporting-zone dates (inclusive), one page at a time, with totals
     * over the whole range. Unlike {@link #list} this is not capped at the latest 1000 rows.
     */
    @GetMapping("/report")
    @Transactional(readOnly = true)
    public Report report(@AuthenticationPrincipal TenantPrincipal p,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(defaultValue = "ALL") String status,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "25") int size) {
        String group = checkedRange(from, to, status);
        OffsetDateTime start = clock.startOf(from), end = clock.startOf(to.plusDays(1));
        Page<Transaction> rows = transactions.findInRange(p.getTenantId(), start, end, group,
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(200, size)), NEWEST_FIRST));
        return new Report(from, to, summarize(transactions.rangeFacts(p.getTenantId(), start, end)),
                rows.map(TenantPaymentsController::toPayment).getContent(),
                rows.getNumber(), rows.getTotalPages(), rows.getTotalElements());
    }

    /** Every payment in the range and status group as CSV — all rows, not just one page. */
    @GetMapping("/export")
    @Transactional(readOnly = true)
    public ResponseEntity<String> export(@AuthenticationPrincipal TenantPrincipal p,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "ALL") String status) {
        String group = checkedRange(from, to, status);
        Page<Transaction> rows = transactions.findInRange(p.getTenantId(), clock.startOf(from),
                clock.startOf(to.plusDays(1)), group, PageRequest.of(0, MAX_EXPORT_ROWS, NEWEST_FIRST));
        if (rows.getTotalElements() > MAX_EXPORT_ROWS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Too many payments to export at once. Choose a shorter date range.");
        }
        DateTimeFormatter when = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        StringBuilder csv = new StringBuilder("Date,Reference,Status,Channel,Amount (UGX),Phone,Voucher,Plan,Router,MoMo transaction ID,Notes\r\n");
        for (Transaction t : rows) {
            Payment pay = toPayment(t);
            csv.append(String.join(",",
                    cell(pay.createdAt() == null ? "" : pay.createdAt().atZoneSameInstant(clock.zone()).format(when)),
                    cell(pay.reference()), cell(pay.status()), cell(channelLabel(pay.method())),
                    cell(pay.amount() == null ? "" : pay.amount().toPlainString()),
                    cell(pay.phone()), cell(pay.voucher()), cell(pay.packageName()), cell(pay.router()),
                    cell(pay.mobileMoneyId()), cell(pay.notes()))).append("\r\n");
        }
        String name = "payments-" + from + (from.equals(to) ? "" : "-to-" + to) + (group.equals("ALL") ? "" : "-" + group.toLowerCase()) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body("\uFEFF" + csv); // BOM so Excel opens UTF-8 correctly
    }

    private static String checkedRange(LocalDate from, LocalDate to, String status) {
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The end date is before the start date.");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a range of one year or less.");
        }
        String group = status == null ? "ALL" : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!STATUS_GROUPS.contains(group)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status filter.");
        }
        return group;
    }

    /** Human channel name for exports: "MTN MoMo", "Cash", … */
    static String channelLabel(String method) {
        if (method == null) return "";
        return switch (method) {
            case MobileNetwork.MTN -> "MTN MoMo";
            case MobileNetwork.AIRTEL -> "Airtel Money";
            case MobileNetwork.OTHER_MOMO -> "Mobile money";
            case "CASH" -> "Cash";
            case "BANK" -> "Bank";
            default -> method.charAt(0) + method.substring(1).toLowerCase(java.util.Locale.ROOT);
        };
    }

    static RangeSummary summarize(List<Object[]> facts) {
        BigDecimal collected = BigDecimal.ZERO, mobileMoney = BigDecimal.ZERO;
        long successful = 0, failed = 0;
        for (Object[] f : facts) {
            String status = (String) f[0];
            if ("FAILED".equals(status)) { failed++; continue; }
            if (!"SUCCESSFUL".equals(status)) continue;
            successful++;
            BigDecimal amount = f[1] == null ? BigDecimal.ZERO : (BigDecimal) f[1];
            collected = collected.add(amount);
            if (MobileNetwork.isMobileMoney(MobileNetwork.ofPayment((String) f[2], (String) f[3], (String) f[4]))) {
                mobileMoney = mobileMoney.add(amount);
            }
        }
        long total = facts.size();
        return new RangeSummary(collected, mobileMoney, collected.subtract(mobileMoney),
                total, successful, total - successful - failed, failed);
    }

    /** One quoted CSV cell; a leading = + - @ (or tab/CR) is neutralised so spreadsheets don't run it. */
    static String cell(String value) {
        if (value == null) return "\"\"";
        String v = value.replace("\"", "\"\"");
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v + "\"";
    }

    private static Payment toPayment(Transaction t) {
        return new Payment(t.getId(), t.getReference(),
                com.wifibilling.util.MobileNetwork.ofPayment(t.getNetwork(), t.getPhone(), t.getGatewayMode()),
                t.getAmount(), t.getStatus(), t.getPhone(),
                t.getVoucherCode(),
                t.getPackagePlan() != null ? t.getPackagePlan().getName() : null,
                t.getRouter() != null ? t.getRouter().getName() : null,
                t.getCreatedAt(), t.getNotes(), t.getProviderTransactionId());
    }
}
