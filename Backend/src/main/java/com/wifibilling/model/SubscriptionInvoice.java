package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One billing period's charge. Unique per (subscription, period_start) so a
 * period can never be double-invoiced. amountDue is frozen at issue time —
 * a later price change never rewrites issued invoices.
 */
@Entity
@Table(name = "subscription_invoices",
        uniqueConstraints = @UniqueConstraint(columnNames = {"subscription_id", "period_start"}),
        indexes = {
            @Index(columnList = "tenant_id,period_start"),
            @Index(columnList = "status,period_end")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubscriptionInvoice {

    public static final String UNPAID = "UNPAID";
    public static final String PAID = "PAID";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false)
    private LocalDate periodStart;

    @Column(nullable = false)
    private LocalDate periodEnd;

    /** Frozen at issue: the fee that applied for this period. */
    @Column(nullable = false)
    private Long amountDue;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = UNPAID;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    private OffsetDateTime paidAt;
}
