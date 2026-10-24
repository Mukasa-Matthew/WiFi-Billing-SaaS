package com.wifibilling.controller;

import com.wifibilling.model.Subscription;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Tenant-facing read-only subscription view. Tenants can see their plan, billing
 * period, amount due, and payment history — but can never change pricing,
 * periods, or payment status (admin-only).
 */
@RestController
@RequestMapping("/api/subscription")
@RequiredArgsConstructor
public class TenantSubscriptionController {

    private final SubscriptionService service;

    @GetMapping
    public Map<String, Object> view(@AuthenticationPrincipal TenantPrincipal p) {
        LocalDate today = LocalDate.now();
        Subscription sub;
        try {
            sub = service.getForTenant(p.getTenantId());
        } catch (ResponseStatusException e) {
            return Map.of("subscribed", false);
        }
        service.ensureCurrent(sub, today);
        String status = service.displayStatus(sub, today);
        long outstanding = service.outstandingFor(sub.getId());

        var history = service.paymentHistory(sub.getId()).stream()
                .map(pay -> Map.<String, Object>of(
                        "id", pay.getId(),
                        "amount", pay.getAmount(),
                        "method", pay.getMethod(),
                        "paidAt", pay.getPaidAt().toString()))
                .toList();

        var invoices = service.billingHistory(sub.getId()).stream()
                .map(i -> Map.<String, Object>of(
                        "id", i.getId(),
                        "periodStart", i.getPeriodStart().toString(),
                        "periodEnd", i.getPeriodEnd().toString(),
                        "amountDue", i.getAmountDue(),
                        "status", i.getStatus()))
                .toList();

        long daysToRenewal = java.time.temporal.ChronoUnit.DAYS.between(today, sub.getNextRenewalDate());
        String reminder = outstanding > 0 && status.equals("PAST_DUE") ? "Your subscription payment is overdue."
                : outstanding > 0 && daysToRenewal <= 7 ? "Your renewal payment is due soon."
                : "";

        var out = new java.util.HashMap<String, Object>();
        out.put("subscribed", true);
        out.put("status", status);
        out.put("monthlyFee", sub.getMonthlyFee());
        out.put("currency", "UGX");
        out.put("startDate", sub.getStartDate().toString());
        out.put("nextRenewalDate", sub.getNextRenewalDate().toString());
        out.put("daysToRenewal", daysToRenewal);
        out.put("outstanding", outstanding);
        out.put("reminder", reminder);
        out.put("invoices", invoices);
        out.put("payments", history);
        return out;
    }
}
