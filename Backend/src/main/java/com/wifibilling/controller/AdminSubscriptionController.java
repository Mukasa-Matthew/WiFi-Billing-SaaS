package com.wifibilling.controller;

import com.wifibilling.model.*;
import com.wifibilling.repository.SubscriptionPaymentRepository;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Super-admin subscription management. SaaS fees tenants pay us — completely
 * separate from their customer WiFi revenue and wallets.
 */
@RestController
@RequestMapping("/api/admin/subscriptions")
@PreAuthorize("hasAuthority('ROLE_SUPERADMIN')")
@RequiredArgsConstructor
public class AdminSubscriptionController {

    private final SubscriptionService service;
    private final TenantRepository tenants;
    private final SubscriptionPaymentRepository payments;

    // ---------- overview ----------

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        LocalDate today = LocalDate.now();
        List<Subscription> all = service.all();
        all.forEach(s -> service.ensureCurrent(s, today));
        long active = 0, dueSoon = 0, overdue = 0, suspended = 0, outstanding = 0;
        for (Subscription s : all) {
            String ds = service.displayStatus(s, today);
            outstanding += service.outstandingFor(s.getId());
            switch (ds) {
                case "ACTIVE" -> active++;
                case "PAYMENT_DUE" -> dueSoon++;
                case "PAST_DUE" -> overdue++;
                case "SUSPENDED" -> suspended++;
                default -> {}
            }
        }
        long monthRevenue = payments.sumSince(YearMonth.now().atDay(1).atStartOfDay().atOffset(OffsetDateTime.now().getOffset()));
        return Map.of(
                "totalTenants", tenants.count(),
                "subscribed", all.size(),
                "active", active,
                "dueSoon", dueSoon,
                "overdue", overdue,
                "suspended", suspended,
                "monthRevenue", monthRevenue,
                "outstanding", outstanding,
                "defaultFee", service.defaultFee()
        );
    }

    // ---------- list ----------

    public record SubscriptionRow(Long id, Long tenantId, String businessName, Long monthlyFee,
                                  LocalDate startDate, LocalDate nextRenewalDate,
                                  String status, String displayStatus, Long outstanding) {}

    @GetMapping
    public List<SubscriptionRow> list() {
        LocalDate today = LocalDate.now();
        return service.all().stream().map(s -> {
            service.ensureCurrent(s, today);
            return new SubscriptionRow(s.getId(), s.getTenant().getId(), s.getTenant().getName(),
                    s.getMonthlyFee(), s.getStartDate(), s.getNextRenewalDate(),
                    s.getStatus(), service.displayStatus(s, today), service.outstandingFor(s.getId()));
        }).toList();
    }

    /** Tenants with no subscription yet — for the "create" picker. */
    @GetMapping("/unsubscribed-tenants")
    public List<Map<String, Object>> unsubscribed() {
        var existing = service.all().stream().map(s -> s.getTenant().getId()).toList();
        return tenants.findAll().stream()
                .filter(t -> !existing.contains(t.getId()))
                .filter(t -> !"ROLE_SUPERADMIN".equals(t.getRole()))
                .map(t -> Map.<String, Object>of("id", t.getId(), "name", t.getName()))
                .toList();
    }

    // ---------- mutations ----------

    public record CreateRequest(Long tenantId, Long monthlyFee, LocalDate startDate) {}

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRequest req) {
        if (req == null || req.tenantId() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "tenantId is required");
        }
        var sub = service.createOrUpdate(req.tenantId(), req.monthlyFee(), req.startDate());
        return Map.of("id", sub.getId(), "status", sub.getStatus());
    }

    public record FeeRequest(Long monthlyFee) {}

    @PutMapping("/{id}/fee")
    public Map<String, Object> updateFee(@PathVariable Long id, @RequestBody FeeRequest req) {
        if (req == null || req.monthlyFee() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "monthlyFee is required");
        }
        var sub = service.updateFee(id, req.monthlyFee());
        return Map.of("id", sub.getId(), "monthlyFee", sub.getMonthlyFee());
    }

    @PostMapping("/{id}/suspend")
    public Map<String, String> suspend(@PathVariable Long id) {
        return Map.of("status", service.suspend(id).getStatus());
    }

    @PostMapping("/{id}/reactivate")
    public Map<String, String> reactivate(@PathVariable Long id) {
        return Map.of("status", service.reactivate(id).getStatus());
    }

    @PostMapping("/{id}/cancel")
    public Map<String, String> cancel(@PathVariable Long id) {
        return Map.of("status", service.cancel(id).getStatus());
    }

    public record PaymentRequest(Long invoiceId, Long amount, String method,
                                 String externalRef, String recordRef, String note,
                                 String settlementChannel) {}

    @PostMapping("/{id}/payments")
    public Map<String, Object> recordPayment(@PathVariable Long id,
                                             @RequestBody PaymentRequest req,
                                             @AuthenticationPrincipal TenantPrincipal principal) {
        if (req == null || req.amount() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "amount is required");
        }
        var p = service.recordPayment(id, req.invoiceId(), req.amount(), req.method(),
                req.externalRef(), req.recordRef(), principal.getTenantId(), req.note(),
                req.settlementChannel());
        return Map.of("id", p.getId(), "invoiceId", p.getInvoice().getId());
    }

    // ---------- billing detail ----------

    public record InvoiceRow(Long id, LocalDate periodStart, LocalDate periodEnd,
                             Long amountDue, String status, String paidAt) {}
    public record PaymentRow(Long id, Long amount, String method, String externalRef,
                             String paidAt, String note) {}
    public record BillingDetail(SubscriptionRow subscription, List<InvoiceRow> invoices,
                                List<PaymentRow> payments) {}

    @GetMapping("/{id}/billing")
    public BillingDetail billing(@PathVariable Long id) {
        LocalDate today = LocalDate.now();
        Subscription s = service.get(id);
        service.ensureCurrent(s, today);
        var row = new SubscriptionRow(s.getId(), s.getTenant().getId(), s.getTenant().getName(),
                s.getMonthlyFee(), s.getStartDate(), s.getNextRenewalDate(),
                s.getStatus(), service.displayStatus(s, today), service.outstandingFor(s.getId()));
        var inv = service.billingHistory(id).stream()
                .map(i -> new InvoiceRow(i.getId(), i.getPeriodStart(), i.getPeriodEnd(),
                        i.getAmountDue(), i.getStatus(),
                        i.getPaidAt() != null ? i.getPaidAt().toString() : null))
                .toList();
        var pay = service.paymentHistory(id).stream()
                .map(p -> new PaymentRow(p.getId(), p.getAmount(), p.getMethod(), p.getExternalRef(),
                        p.getPaidAt().toString(), p.getNote()))
                .toList();
        return new BillingDetail(row, inv, pay);
    }

    // ---------- default fee ----------

    public record DefaultFeeRequest(Long fee) {}

    @PutMapping("/default-fee")
    public Map<String, Long> setDefaultFee(@RequestBody DefaultFeeRequest req) {
        if (req == null || req.fee() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "fee is required");
        }
        return Map.of("defaultFee", service.setDefaultFee(req.fee()));
    }
}
