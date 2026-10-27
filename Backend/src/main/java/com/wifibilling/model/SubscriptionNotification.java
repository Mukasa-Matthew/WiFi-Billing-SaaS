package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "subscription_notifications", indexes = {
    @Index(name = "idx_sub_notif_tenant", columnList = "tenant_id"),
    @Index(name = "idx_sub_notif_sub_dedup", columnList = "subscription_id, notification_type, billing_cycle_key", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "notification_type", nullable = false, length = 40)
    private String notificationType; // TRIAL_5_DAYS, PAID_5_DAYS, TRIAL_1_DAY, PAID_1_DAY, EXPIRED, GRACE_EXPIRED, PAYMENT_SUCCESS

    @Column(name = "billing_cycle_key", nullable = false, length = 64)
    private String billingCycleKey; // e.g. "trial-2026-11-02" or "cycle-2026-12-02"

    @Column(nullable = false, length = 160)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(length = 20)
    private String severity; // INFO, WARNING, DANGER, SUCCESS

    @Column(nullable = false)
    @Builder.Default
    private Boolean emailSent = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean dismissed = false;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
