package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * Super Admin-controlled SaaS subscription plans.
 * Tenants choose from these active plans to pay for platform access.
 */
@Entity
@Table(name = "subscription_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String name;

    /** Subscription fee in UGX whole shillings (e.g. 50,000). */
    @Column(nullable = false)
    private Long price;

    /** Duration unit: MONTHS or DAYS. Default: MONTHS. */
    @Column(name = "duration_unit", nullable = false, length = 16)
    @Builder.Default
    private String durationUnit = "MONTHS";

    /** Duration value: e.g. 1 month, 3 months, 12 months, or 7 days. */
    @Column(name = "duration_value", nullable = false)
    @Builder.Default
    private Integer durationValue = 1;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "display_order")
    @Builder.Default
    private Integer displayOrder = 0;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }
}
