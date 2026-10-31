package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * Immutable wallet ledger entry. Every balance change is justified by exactly one
 * entry traceable to a payment, fee, withdrawal, refund, or authorized adjustment.
 * Entries are never updated or deleted — corrections are new compensating entries.
 */
@Entity
@Table(name = "wallet_ledger_entries", indexes = {
        @Index(columnList = "wallet_id,created_at"),
        @Index(columnList = "wallet_id,type")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WalletLedgerEntry {

    // Types that increase available balance
    public static final String COLLECTION_CREDIT = "COLLECTION_CREDIT";       // net collection settled to wallet
    public static final String WITHDRAWAL_RELEASE = "WITHDRAWAL_RELEASE";     // reserved funds returned after failed payout
    public static final String ADJUSTMENT_CREDIT = "ADJUSTMENT_CREDIT";       // authorized admin credit

    // Pending bucket
    public static final String COLLECTION_PENDING = "COLLECTION_PENDING";     // earned, awaiting settlement
    public static final String SETTLEMENT_RELEASE = "SETTLEMENT_RELEASE";     // pending → available move (debit pending side)

    // Types that decrease available balance
    public static final String FEE_DEBIT = "FEE_DEBIT";                       // informational: fee recorded (not deducted twice — credit is net)
    public static final String WITHDRAWAL_RESERVE = "WITHDRAWAL_RESERVE";     // available → reserved hold
    public static final String WITHDRAWAL_DEBIT = "WITHDRAWAL_DEBIT";         // payout completed: reserved → gone
    public static final String ADJUSTMENT_DEBIT = "ADJUSTMENT_DEBIT";         // authorized admin debit
    public static final String EXTERNAL_WITHDRAWAL = "EXTERNAL_WITHDRAWAL";   // paid out directly from the provider, recorded by an admin
    public static final String RESERVE_CORRECTION = "RESERVE_CORRECTION";     // one-off: stranded reserve cleared, available unchanged

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private WalletAccount wallet;

    @Column(nullable = false, length = 32)
    private String type;

    /** Always positive; direction is implied by type. Integer minor units. */
    @Column(nullable = false)
    private Long amount;

    /** Balance snapshots AFTER this entry — the audit trail. */
    @Column(nullable = false)
    private Long availableAfter;
    @Column(nullable = false)
    private Long pendingAfter;
    @Column(nullable = false)
    private Long reservedAfter;

    /** Unique per (type, externalRef) — idempotency guard so a duplicated event
     *  can never double-credit or double-debit. */
    @Column(name = "external_ref", nullable = false, length = 128)
    private String externalRef;

    /** Originating business object (transaction or withdrawal id). */
    private Long transactionId;
    private Long withdrawalId;

    @Column(length = 255)
    private String note;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
