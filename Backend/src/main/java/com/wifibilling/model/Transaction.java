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

    @Column(length=1000) private String notes;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
