package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "packages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PackagePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    /** Optional: restricts this package to one router. Null = available on all the tenant's routers. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "router_id")
    private Router router;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "rate_limit", nullable = false, length = 30)
    private String rateLimit;

    @Column(name = "session_timeout", nullable = false)
    private Integer sessionTimeout;

    @Column(name = "shared_users")
    @Builder.Default
    private Integer sharedUsers = 1;

    @Column(length = 500)
    private String description;

    @Column(length = 12)
    private String availability;

    private Boolean featured;
    private Integer displayOrder;

    public String effectiveAvailability() {
        return availability == null ? "ACTIVE" : availability;
    }

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
