package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "vouchers", indexes = { @Index(columnList = "tenant_id,created_at"), @Index(columnList = "tenant_id,status"), @Index(columnList = "code") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Voucher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_id", nullable = false)
    private PackagePlan packagePlan;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(length = 20)
    @Builder.Default
    private String status = "unused";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;
    @Column(length = 80) private String batchReference;
    @Column(length = 20) private String saleType;
    @Column(length = 20) private String delivery;
    private Boolean archived;

    @Column(name = "claimed_mac", length = 30)
    private String claimedMac;

    @Column(name = "first_login")
    private OffsetDateTime firstLogin;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    /** School tenants: the identity-system person this voucher belongs to (students and staff). */
    @Column(name = "person_id", length = 80)
    private String personId;

    /** School tenants: STUDENT, STAFF or VISITOR. */
    @Column(name = "school_category", length = 8)
    private String schoolCategory;

    @Column(name = "visitor_pass_id", length = 36)
    private String visitorPassId;

    /** Latest moment this voucher may ever be used, whatever its package says (leaving date, pass expiry). */
    @Column(name = "hard_expires_at")
    private OffsetDateTime hardExpiresAt;

    /** True while the identity system has asked for sign-out; a fresh ID and PIN check re-enables it. */
    @Column(name = "school_hold")
    private Boolean schoolHold;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
