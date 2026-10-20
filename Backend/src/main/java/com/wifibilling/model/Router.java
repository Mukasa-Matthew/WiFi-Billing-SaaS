package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "routers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Router {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "wg_ip", nullable = false, unique = true, length = 45)
    private String wgIp;

    @Column(name = "wg_public_key", length = 64)
    private String wgPublicKey;

    @Column(name = "radius_secret", nullable = false, length = 64)
    private String radiusSecret;

    @Column(length = 20)
    @Builder.Default
    private String status = "pending";

    @Column(name = "cpu_load")
    @Builder.Default
    private Integer cpuLoad = 0;

    @Column(name = "memory_usage")
    @Builder.Default
    private Integer memoryUsage = 0;

    @Column(length = 50)
    private String uptime;

    @Column(name = "active_users")
    @Builder.Default
    private Integer activeUsers = 0;

    @Column(name = "last_seen")
    private OffsetDateTime lastSeen;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
