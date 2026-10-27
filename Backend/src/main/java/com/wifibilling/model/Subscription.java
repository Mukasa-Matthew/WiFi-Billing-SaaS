package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Tenant's SaaS subscription — the fee they pay to use the platform.
 * Completely separate from customer WiFi payments and the tenant wallet.
 */
@Entity
@Table(name = "subscriptions", indexes = {
    @Index(columnList = "status"),
    @Index(columnList = "expires_at")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Subscription {

    public static final String STATUS_TRIALING = "TRIALING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_GRACE_PERIOD = "GRACE_PERIOD";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, unique = true)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private SubscriptionPlan plan;

    /** Fee in UGX whole shillings (integer minor units). */
    @Column(nullable = false)
    private Long monthlyFee;

    /** Day-of-month the billing cycle is anchored to (1-31). */
    @Column(nullable = false)
    @Builder.Default
    private Integer anchorDay = 1;

    /** Date the subscription or trial started. */
    @Column(nullable = false)
    private LocalDate startDate;

    /** Start of next billing period / renewal date. */
    @Column(nullable = false)
    private LocalDate nextRenewalDate;

    /** Authoritative timestamp when current trial or paid period expires. */
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    /** Start of trial (if granted). */
    @Column(name = "trial_starts_at")
    private OffsetDateTime trialStartsAt;

    /** Expiry of trial (7 calendar days). */
    @Column(name = "trial_ends_at")
    private OffsetDateTime trialEndsAt;

    /** 2 calendar days renewal window after trial or subscription expiry. */
    @Column(name = "grace_until")
    private OffsetDateTime graceUntil;

    /** Tracks whether a free 7-day trial was ever granted. */
    @Column(name = "trial_granted", nullable = false)
    @Builder.Default
    private Boolean trialGranted = false;

    /** Primary lifecycle state: TRIALING, ACTIVE, GRACE_PERIOD, SUSPENDED, CANCELLED. */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_TRIALING;

    /** Date of last reminder sent to prevent duplicate notifications in the same cycle. */
    @Column(name = "last_reminder_at")
    private OffsetDateTime lastReminderAt;

    @Column(name = "last_reminder_type", length = 32)
    private String lastReminderType;

    /** Optional audit note on manual overrides/complimentary extensions. */
    @Column(name = "admin_note", length = 500)
    private String adminNote;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    private OffsetDateTime updatedAt;

    @PrePersist @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }

    public LocalDate renewalAfter(LocalDate from) {
        LocalDate candidate = from.plusMonths(1);
        int day = Math.min(anchorDay, candidate.lengthOfMonth());
        return LocalDate.of(candidate.getYear(), candidate.getMonth(), day);
    }
}
