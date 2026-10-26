package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * Tracks a tenant's self-service Mobile Money subscription checkout.
 * Separated strictly from customer hotspot payments.
 */
@Entity
@Table(name = "subscription_checkouts", indexes = {
    @Index(columnList = "tenant_id"),
    @Index(columnList = "reference"),
    @Index(columnList = "external_reference"),
    @Index(columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionCheckout {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUCCESSFUL = "SUCCESSFUL";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private SubscriptionPlan plan;

    /** Unique platform reference (e.g. SUB-UUID). */
    @Column(nullable = false, unique = true, length = 64)
    private String reference;

    /** External transaction / provider reference from payment gateway. */
    @Column(name = "external_reference", length = 128)
    private String externalReference;

    @Column(nullable = false, length = 32)
    private String phone;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_PENDING;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "provider_uuid", length = 128)
    private String providerUuid;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
