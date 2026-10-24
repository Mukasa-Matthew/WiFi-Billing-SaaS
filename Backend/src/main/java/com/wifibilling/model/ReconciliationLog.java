package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * One reconciliation run: provider balance vs expected internal position.
 * Kept as an audit trail — diffs are flagged, never silently corrected.
 */
@Entity
@Table(name = "reconciliation_logs", indexes = @Index(columnList = "created_at"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReconciliationLog {

    public static final String CLEAN = "CLEAN";
    public static final String DISCREPANCY = "DISCREPANCY";
    public static final String EXTERNAL_DEBIT = "EXTERNAL_DEBIT";
    public static final String FAILED = "FAILED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Actual MarzPay available balance at sync time (null if fetch failed). */
    private Long providerBalance;

    /** Expected position: platform earnings + reserved + all tenant liabilities. */
    @Column(nullable = false)
    private Long expectedBalance;

    /** providerBalance - expectedBalance (null when provider fetch failed). */
    private Long difference;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 500)
    private String note;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
