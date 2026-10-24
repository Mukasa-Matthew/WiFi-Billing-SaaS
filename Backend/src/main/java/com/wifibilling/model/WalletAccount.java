package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * Internal tenant wallet — funds owed to the tenant from collections processed
 * through the PLATFORM MarzPay gateway. NOT the tenant's MarzPay wallet.
 * Balances are exact integer minor units (UGX whole shillings) maintained under
 * the same transaction as the immutable WalletLedgerEntry rows that justify them.
 */
@Entity
@Table(name = "wallet_accounts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WalletAccount {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, unique = true)
    private Tenant tenant;

    @Column(nullable = false, length = 8)
    @Builder.Default
    private String currency = "UGX";

    /** Spendable now. */
    @Column(nullable = false)
    @Builder.Default
    private Long availableBalance = 0L;

    /** Earned but not yet settled/available. */
    @Column(nullable = false)
    @Builder.Default
    private Long pendingBalance = 0L;

    /** Held against in-flight withdrawals. */
    @Column(nullable = false)
    @Builder.Default
    private Long reservedBalance = 0L;

    /** Lifetime counters for reporting (not the source of truth for spendable funds). */
    @Column(nullable = false)
    @Builder.Default
    private Long totalCollected = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Long totalFees = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Long totalWithdrawn = 0L;

    /** Optimistic lock — guards the cached balances against concurrent mutation. */
    @Version
    private Long version;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    private OffsetDateTime updatedAt;

    @PrePersist @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }
}
