package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * Immutable platform-earnings ledger entry. Mirrors the tenant wallet ledger
 * discipline: every balance change is justified by one auditable entry with
 * running snapshots and a unique (type, externalRef) idempotency key.
 */
@Entity
@Table(name = "platform_ledger_entries", indexes = {
        @Index(columnList = "created_at"),
        @Index(columnList = "type")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PlatformLedgerEntry {

    // Earnings
    public static final String EARNING_MARZPAY = "EARNING_MARZPAY";     // settled into MarzPay
    public static final String EARNING_EXTERNAL = "EARNING_EXTERNAL";   // received outside MarzPay
    public static final String EXTERNAL_DEPOSIT = "EXTERNAL_DEPOSIT";   // external funds later verified in MarzPay
    public static final String FEE = "FEE";                             // provider/payout fee recorded
    // Payout lifecycle
    public static final String PAYOUT_RESERVE = "PAYOUT_RESERVE";
    public static final String PAYOUT_DEBIT = "PAYOUT_DEBIT";
    public static final String PAYOUT_RELEASE = "PAYOUT_RELEASE";
    // Admin
    public static final String ADJUSTMENT_CREDIT = "ADJUSTMENT_CREDIT";
    public static final String ADJUSTMENT_DEBIT = "ADJUSTMENT_DEBIT";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false)
    private Long settledAfter;
    @Column(nullable = false)
    private Long externalAfter;
    @Column(nullable = false)
    private Long reservedAfter;

    @Column(name = "external_ref", nullable = false, length = 128)
    private String externalRef;

    private Long subscriptionPaymentId;
    private Long payoutId;

    @Column(length = 255)
    private String note;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
