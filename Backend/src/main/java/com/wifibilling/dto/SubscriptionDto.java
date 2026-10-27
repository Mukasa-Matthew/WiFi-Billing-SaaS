package com.wifibilling.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public class SubscriptionDto {

    public record PlanResponse(
            Long id,
            String name,
            Long price,
            String durationUnit,
            Integer durationValue,
            String description,
            Boolean active,
            Integer displayOrder
    ) {}

    public record CreatePlanRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull @Min(1) Long price,
            @Pattern(regexp = "MONTHS|DAYS") String durationUnit,
            @NotNull @Min(1) Integer durationValue,
            @Size(max = 500) String description,
            Boolean active,
            Integer displayOrder
    ) {}

    public record CheckoutRequest(
            Long planId,
            @NotBlank String phone
    ) {}

    public record CheckoutResponse(
            String reference,
            String status,
            Long amount,
            String phone,
            String message
    ) {}

    public record NotificationView(
            Long id,
            String notificationType,
            String title,
            String message,
            String severity,
            Boolean emailSent,
            Boolean dismissed,
            OffsetDateTime createdAt
    ) {}

    public record PeriodHistoryView(
            Long id,
            String planName,
            String periodType, // FREE_TRIAL, PAID_SUBSCRIPTION, COMPLIMENTARY_EXTENSION, ADMIN_OVERRIDE
            String periodStart,
            String periodEnd,
            Integer durationDays,
            Long amountCharged,
            String status,
            String paymentDate,
            String paymentReference,
            String note
    ) {}

    public record PaymentHistoryView(
            Long id,
            String paidAt,
            String reference,
            String externalRef,
            String planName,
            Long amount,
            String phone,
            String method,
            String status, // SUCCESSFUL, PENDING, FAILED, CANCELLED
            String note
    ) {}

    public record TenantView(
            boolean subscribed,
            String status,
            String displayStatus,
            Long planId,
            String planName,
            Long price,
            String currency,
            LocalDate startDate,
            LocalDate nextRenewalDate,
            OffsetDateTime expiresAt,
            OffsetDateTime trialEndsAt,
            OffsetDateTime graceUntil,
            long daysRemaining,
            long hoursRemaining,
            long minutesRemaining,
            boolean inTrial,
            boolean inGrace,
            boolean isExpired,
            boolean isSuspended,
            boolean canAcceptCustomerPayments,
            String reminderMessage,
            List<PlanResponse> availablePlans,
            List<PeriodHistoryView> periodHistory,
            List<PaymentHistoryView> paymentHistory,
            List<InvoiceView> invoices,
            List<NotificationView> notifications
    ) {}

    public record InvoiceView(
            Long id,
            String periodStart,
            String periodEnd,
            Long amountDue,
            String status,
            String paidAt
    ) {}

    public record ManualExtensionRequest(
            @NotNull @Min(1) Integer extensionDays,
            @NotBlank String reason,
            Boolean complimentary
    ) {}

    public record AssignPlanRequest(
            @NotNull Long planId
    ) {}
}
