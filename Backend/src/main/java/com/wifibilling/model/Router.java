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

    @Column(name = "wan_interface", length = 32)
    @Builder.Default
    private String wanInterface = "ether1";

    @Column(name = "hotspot_interfaces", length = 255)
    @Builder.Default
    private String hotspotInterfaces = "ether2,ether3,ether4,ether5,wlan1";

    @Column(name = "wifi_ssid", length = 32)
    @Builder.Default
    private String wifiSsid = "WiFi Hotspot";

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

    @Column(length=64) private String bindingsHash;
    private OffsetDateTime bindingsSyncedAt;
    @Column(length=12000) private String hostsJson;
    private OffsetDateTime hostsSeenAt;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
