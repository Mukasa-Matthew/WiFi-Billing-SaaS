package com.wifibilling.controller;

import com.wifibilling.dto.SubscriptionDto;
import com.wifibilling.model.*;
import com.wifibilling.repository.*;
import com.wifibilling.service.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.*;

@RestController
@RequestMapping("/api/admin/subscriptions")
@PreAuthorize("hasAuthority('ROLE_SUPERADMIN')")
@RequiredArgsConstructor
public class AdminSubscriptionController {

    private final SubscriptionService service;
    private final SubscriptionPlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionInvoiceRepository invoices;
    private final SubscriptionPaymentRepository payments;
    private final SubscriptionNotificationRepository notifications;
    private final TenantRepository tenants;

    // ---------- overview ----------

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        List<Subscription> all = service.all();
        all.forEach(service::refreshLifecycle);

        long active = 0, trialing = 0, grace = 0, suspended = 0, expiringSoon = 0;
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime soon = now.plusDays(5);

        for (Subscription s : all) {
            switch (s.getStatus()) {
                case Subscription.STATUS_ACTIVE -> active++;
                case Subscription.STATUS_TRIALING -> trialing++;
                case Subscription.STATUS_GRACE_PERIOD -> grace++;
                case Subscription.STATUS_SUSPENDED -> suspended++;
                default -> {}
            }
            if (s.getExpiresAt() != null && s.getExpiresAt().isAfter(now) && s.getExpiresAt().isBefore(soon)) {
                expiringSoon++;
            }
        }

        long monthRevenue = payments.sumSince(YearMonth.now().atDay(1).atStartOfDay().atOffset(OffsetDateTime.now().getOffset()));
        return Map.of(
                "totalTenants", tenants.count(),
                "subscribed", all.size(),
                "active", active,
                "trialing", trialing,
                "grace", grace,
                "suspended", suspended,
                "expiringSoon", expiringSoon,
                "monthRevenue", monthRevenue,
                "defaultFee", service.defaultFee()
        );
    }

    // ---------- list ----------

    public record SubscriptionRow(
            Long id,
            Long tenantId,
            String businessName,
            Long planId,
            String planName,
            Long monthlyFee,
            LocalDate startDate,
            LocalDate nextRenewalDate,
            OffsetDateTime expiresAt,
            OffsetDateTime trialEndsAt,
            OffsetDateTime graceUntil,
            String status,
            String adminNote
    ) {}

    @GetMapping
    public List<SubscriptionRow> list() {
        return service.all().stream().map(s -> {
            s = service.refreshLifecycle(s);
            String planName = s.getPlan() != null ? s.getPlan().getName() : "Custom";
            Long planId = s.getPlan() != null ? s.getPlan().getId() : null;
            return new SubscriptionRow(
                    s.getId(),
                    s.getTenant().getId(),
                    s.getTenant().getName(),
                    planId,
                    planName,
                    s.getMonthlyFee(),
                    s.getStartDate(),
                    s.getNextRenewalDate(),
                    s.getExpiresAt(),
                    s.getTrialEndsAt(),
                    s.getGraceUntil(),
                    s.getStatus(),
                    s.getAdminNote()
            );
        }).toList();
    }

    // ---------- plan management ----------

    @GetMapping("/plans")
    public List<SubscriptionDto.PlanResponse> listPlans() {
        return plans.findAllByOrderByDisplayOrderAscPriceAsc().stream()
                .map(p -> new SubscriptionDto.PlanResponse(
                        p.getId(), p.getName(), p.getPrice(), p.getDurationUnit(),
                        p.getDurationValue(), p.getDescription(), p.getActive(), p.getDisplayOrder()))
                .toList();
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionDto.PlanResponse createPlan(@Valid @RequestBody SubscriptionDto.CreatePlanRequest req) {
        SubscriptionPlan plan = SubscriptionPlan.builder()
                .name(req.name().trim())
                .price(req.price())
                .durationUnit(req.durationUnit() != null ? req.durationUnit() : "MONTHS")
                .durationValue(req.durationValue() != null ? req.durationValue() : 1)
                .description(req.description())
                .active(req.active() != null ? req.active() : true)
                .displayOrder(req.displayOrder() != null ? req.displayOrder() : 0)
                .build();
        plan = plans.save(plan);
        return new SubscriptionDto.PlanResponse(
                plan.getId(), plan.getName(), plan.getPrice(), plan.getDurationUnit(),
                plan.getDurationValue(), plan.getDescription(), plan.getActive(), plan.getDisplayOrder());
    }

    @PutMapping("/plans/{id}")
    public SubscriptionDto.PlanResponse updatePlan(@PathVariable Long id, @Valid @RequestBody SubscriptionDto.CreatePlanRequest req) {
        SubscriptionPlan plan = plans.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));
        plan.setName(req.name().trim());
        plan.setPrice(req.price());
        if (req.durationUnit() != null) plan.setDurationUnit(req.durationUnit());
        if (req.durationValue() != null) plan.setDurationValue(req.durationValue());
        plan.setDescription(req.description());
        if (req.active() != null) plan.setActive(req.active());
        if (req.displayOrder() != null) plan.setDisplayOrder(req.displayOrder());
        plan = plans.save(plan);
        return new SubscriptionDto.PlanResponse(
                plan.getId(), plan.getName(), plan.getPrice(), plan.getDurationUnit(),
                plan.getDurationValue(), plan.getDescription(), plan.getActive(), plan.getDisplayOrder());
    }

    @PostMapping("/{id}/assign-plan")
    public SubscriptionRow assignPlan(@PathVariable Long id, @Valid @RequestBody SubscriptionDto.AssignPlanRequest req) {
        Subscription s = service.assignPlan(id, req.planId());
        String planName = s.getPlan() != null ? s.getPlan().getName() : "Custom";
        return new SubscriptionRow(
                s.getId(), s.getTenant().getId(), s.getTenant().getName(), s.getPlan() != null ? s.getPlan().getId() : null,
                planName, s.getMonthlyFee(), s.getStartDate(), s.getNextRenewalDate(),
                s.getExpiresAt(), s.getTrialEndsAt(), s.getGraceUntil(), s.getStatus(), s.getAdminNote()
        );
    }

    // ---------- manual extension / override ----------

    @PostMapping("/{id}/extend")
    public Map<String, Object> manualExtension(
            @PathVariable Long id,
            @Valid @RequestBody SubscriptionDto.ManualExtensionRequest req) {
        Subscription sub = service.grantManualExtension(id, req.extensionDays(), req.reason(), Boolean.TRUE.equals(req.complimentary()));
        return Map.of(
                "status", sub.getStatus(),
                "expiresAt", sub.getExpiresAt().toString(),
                "adminNote", sub.getAdminNote() != null ? sub.getAdminNote() : ""
        );
    }

    // ---------- billing details ----------

    public record InvoiceRow(Long id, LocalDate periodStart, LocalDate periodEnd, Long amountDue, String status, String paidAt) {}
    public record PaymentRow(Long id, Long amount, String method, String externalRef, String paidAt, String note) {}
    public record NotificationRow(Long id, String notificationType, String title, String message, String severity, Boolean emailSent, OffsetDateTime createdAt) {}
    public record BillingDetail(SubscriptionRow subscription, List<InvoiceRow> invoices, List<PaymentRow> payments, List<NotificationRow> notifications) {}

    @GetMapping("/{id}/billing")
    public BillingDetail billing(@PathVariable Long id) {
        Subscription s = service.get(id);
        s = service.refreshLifecycle(s);
        String planName = s.getPlan() != null ? s.getPlan().getName() : "Custom";
        var row = new SubscriptionRow(
                s.getId(), s.getTenant().getId(), s.getTenant().getName(), s.getPlan() != null ? s.getPlan().getId() : null,
                planName, s.getMonthlyFee(), s.getStartDate(), s.getNextRenewalDate(),
                s.getExpiresAt(), s.getTrialEndsAt(), s.getGraceUntil(), s.getStatus(), s.getAdminNote()
        );
        var inv = invoices.findAllBySubscriptionIdOrderByPeriodStartDesc(id).stream()
                .map(i -> new InvoiceRow(i.getId(), i.getPeriodStart(), i.getPeriodEnd(), i.getAmountDue(), i.getStatus(), i.getPaidAt() != null ? i.getPaidAt().toString() : null))
                .toList();
        var pay = payments.findAllBySubscriptionIdOrderByPaidAtDesc(id).stream()
                .map(p -> new PaymentRow(p.getId(), p.getAmount(), p.getMethod(), p.getExternalRef(), p.getPaidAt().toString(), p.getNote()))
                .toList();
        var notifs = notifications.findAllBySubscriptionIdOrderByCreatedAtDesc(id).stream()
                .map(n -> new NotificationRow(n.getId(), n.getNotificationType(), n.getTitle(), n.getMessage(), n.getSeverity(), n.getEmailSent(), n.getCreatedAt()))
                .toList();
        return new BillingDetail(row, inv, pay, notifs);
    }
}
