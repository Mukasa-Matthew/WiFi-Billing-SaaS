package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * Tenant withdrawal against available wallet balance. Lifecycle:
 * REQUESTED → PENDING_APPROVAL (if approval required) → PROCESSING → COMPLETED | FAILED | CANCELLED.
 * Funds are RESERVED at request time and either debited (success) or released (failure).
 * The payout reference is a unique UUID; MarzPay echoes it as transaction.provider_reference.
 */
@Entity
@Table(name = "withdrawal_requests", indexes = {
        @Index(columnList = "tenant_id,created_at"),
        @Index(columnList = "tenant_id,status")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WithdrawalRequest {

    public static final String REQUESTED = "REQUESTED";
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String PROCESSING = "PROCESSING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    /** Amount the tenant receives (payout amount). */
    @Column(nullable = false)
    private Long amount;

    /** Disbursement fee charged by MarzPay (from create response / callback). */
    @Column(nullable = false)
    @Builder.Default
    private Long fee = 0L;

    /** amount + fee — total wallet deduction. */
    @Column(nullable = false)
    private Long totalDeduction;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String network = "UNKNOWN";

    /** Our idempotent payout reference (UUID) — sent to MarzPay as `reference`. */
    @Column(nullable = false, unique = true, length = 64)
    private String payoutRef;

    /** MarzPay transaction UUID (data.transaction.uuid). */
    @Column(length = 64)
    private String providerUuid;

    /** Telco transaction id (disbursement.provider_transaction_id). */
    @Column(length = 128)
    private String providerTransactionId;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = REQUESTED;

    /** Admin who approved (null = auto-approved flow). */
    private Long approvedBy;
    private OffsetDateTime approvedAt;

    @Column(length = 500)
    private String failureReason;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    private OffsetDateTime updatedAt;
    private OffsetDateTime completedAt;

    @Version
    private Long version;

    @PrePersist @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }
}
