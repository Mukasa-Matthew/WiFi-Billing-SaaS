package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * Singleton (id=1) internal ledger account for SaaS subscription revenue owned
 * by the platform — never tenant money. Two earnings buckets are kept distinct:
 *  - settledEarnings: backed by funds verified in the platform MarzPay wallet
 *  - externalEarnings: received outside MarzPay (cash/bank) — NOT withdrawable
 *    through MarzPay until deposited and reconciled
 * Balances are integer minor units maintained under the same transaction as the
 * immutable PlatformLedgerEntry rows that justify them.
 */
@Entity
@Table(name = "platform_earnings_account")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PlatformEarningsAccount {

    public static final long ID = 1L;

    public static final String RECON_CLEAN = "CLEAN";
    public static final String RECON_DISCREPANCY = "DISCREPANCY";
    public static final String RECON_UNVERIFIED = "UNVERIFIED";

    @Id
    @Builder.Default
    private Long id = ID;

    @Column(nullable = false) @Builder.Default private Long settledEarnings = 0L;
    @Column(nullable = false) @Builder.Default private Long externalEarnings = 0L;
    @Column(nullable = false) @Builder.Default private Long reservedEarnings = 0L;
    @Column(nullable = false) @Builder.Default private Long totalEarned = 0L;
    @Column(nullable = false) @Builder.Default private Long totalFees = 0L;
    @Column(nullable = false) @Builder.Default private Long totalWithdrawn = 0L;

    /** Last provider-side balance fetched from GET /balance. */
    private Long lastProviderBalance;
    private OffsetDateTime lastSyncedAt;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String reconcileStatus = RECON_UNVERIFIED;

    @Column(length = 500)
    private String reconcileNote;

    @Version
    private Long version;

    private OffsetDateTime updatedAt;
    @PrePersist @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }
}
