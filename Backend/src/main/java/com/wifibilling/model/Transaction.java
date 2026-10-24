package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "transactions", indexes = { @Index(columnList = "tenant_id,created_at"), @Index(columnList = "tenant_id,status") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_id", nullable = false)
    private PackagePlan packagePlan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "router_id", nullable = false)
    private Router router;

    @Column(nullable = false, unique = true, length = 64)
    private String reference;

    @Column(nullable = false, length = 32)
    private String phone;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String network; // MTN, AIRTEL, etc.

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING"; // PENDING, SUCCESSFUL, FAILED

    @Column(name = "voucher_code", length = 32)
    private String voucherCode;

    @Column(name = "external_reference", length = 128)
    private String externalReference;

    /** Which gateway originated this payment: PLATFORM or OWN. Frozen at creation —
     *  a later gateway switch never rewrites history. */
    @Column(name = "gateway_mode", length = 16)
    private String gatewayMode;

    /** Currency of amount/fee/net (ISO 4217). */
    @Column(length = 8)
    @Builder.Default
    private String currency = "UGX";

    /** Provider-reported fee (transaction.charge) — recorded, never double-applied. */
    @Column(name = "gateway_fee")
    private Long gatewayFee;

    /** Provider-reported net (transaction.net_amount) credited to merchant wallet. */
    @Column(name = "net_amount")
    private Long netAmount;

    /** MarzPay transaction UUID (data.transaction.uuid). */
    @Column(name = "provider_uuid", length = 64)
    private String providerUuid;

    /** Telco transaction id (collection.provider_transaction_id). */
    @Column(name = "provider_transaction_id", length = 128)
    private String providerTransactionId;

    @Column(length=1000) private String notes;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
