package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Tenant's SaaS subscription — the fee they pay us monthly for using the platform.
 * Completely separate from customer WiFi payments and the tenant wallet.
 */
@Entity
@Table(name = "subscriptions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Subscription {

    public static final String ACTIVE = "ACTIVE";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String CANCELLED = "CANCELLED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, unique = true)
    private Tenant tenant;

    /** Monthly fee in UGX whole shillings (integer minor units — never floats). */
    @Column(nullable = false)
    private Long monthlyFee;

    /** Day-of-month the billing cycle is anchored to (1-31). Renewal months clamp
     *  to the month's length so a Jan-31 start renews Feb-28 then Mar-31. */
    @Column(nullable = false)
    private Integer anchorDay;

    /** Date the subscription was activated (first period start). */
    @Column(nullable = false)
    private LocalDate startDate;

    /** Start of the next billing period — authoritative renewal date. */
    @Column(nullable = false)
    private LocalDate nextRenewalDate;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = ACTIVE;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    private OffsetDateTime updatedAt;

    @PrePersist @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }

    /** One calendar month after `from`, anchored to anchorDay, clamped to the
     *  month's last day (handles Feb 28/29 correctly). */
    public LocalDate renewalAfter(LocalDate from) {
        LocalDate candidate = from.plusMonths(1);
        int day = Math.min(anchorDay, candidate.lengthOfMonth());
        return LocalDate.of(candidate.getYear(), candidate.getMonth(), day);
    }
}
