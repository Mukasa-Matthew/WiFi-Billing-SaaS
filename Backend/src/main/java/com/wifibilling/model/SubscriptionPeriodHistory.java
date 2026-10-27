package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Audit log of every subscription period belonging to a tenant:
 * Trial, Paid Renewal, Complimentary Extension, or Admin Override.
 */
@Entity
@Table(name = "subscription_period_history", indexes = {
    @Index(name = "idx_sub_hist_tenant", columnList = "tenant_id"),
    @Index(name = "idx_sub_hist_start", columnList = "period_start")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionPeriodHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    @Column(name = "plan_name", nullable = false, length = 80)
    private String planName;

    @Column(name = "period_type", nullable = false, length = 32)
    private String periodType; // FREE_TRIAL, PAID_SUBSCRIPTION, COMPLIMENTARY_EXTENSION, ADMIN_OVERRIDE

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "duration_days", nullable = false)
    private Integer durationDays;

    @Column(name = "amount_charged", nullable = false)
    private Long amountCharged;

    @Column(nullable = false, length = 20)
    private String status; // ACTIVE, COMPLETED, EXPIRED, CANCELLED

    @Column(name = "payment_date")
    private OffsetDateTime paymentDate;

    @Column(name = "payment_reference", length = 64)
    private String paymentReference;

    @Column(length = 255)
    private String note;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
