package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * Platform earnings payout to the super admin's mobile money. Same lifecycle
 * discipline as tenant withdrawals: reserve → dispatch → finalize/release,
 * unique UUID payoutRef echoed by MarzPay as transaction.provider_reference.
 */
@Entity
@Table(name = "platform_payouts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PlatformPayout {

    public static final String PROCESSING = "PROCESSING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long amount;            // recipient receives this

    @Column(nullable = false)
    @Builder.Default
    private Long fee = 0L;          // provider payout charge

    @Column(nullable = false)
    private Long totalDeduction;    // amount + fee reserved from settled earnings

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, unique = true, length = 64)
    private String payoutRef;

    @Column(length = 64)
    private String providerUuid;

    @Column(length = 128)
    private String providerTransactionId;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = PROCESSING;

    private Long initiatedBy;

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
