package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * A persisted NOC incident: an operational condition detected on a router
 * (offline, degraded readiness, failed sync, auth-failure burst, stale
 * accounting). Persisted so incident history survives restarts; a resolved_at
 * stamp marks recovery. One open incident per (router, type) at a time.
 */
@Entity
@Table(name = "noc_incidents", indexes = {
        @Index(columnList = "router_id,resolved_at"),
        @Index(columnList = "resolved_at,severity")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NocIncident {

    public static final String ROUTER_OFFLINE = "ROUTER_OFFLINE";
    public static final String ROUTER_DEGRADED = "ROUTER_DEGRADED";
    public static final String SYNC_FAILED = "SYNC_FAILED";
    public static final String AUTH_FAILURE_BURST = "AUTH_FAILURE_BURST";
    public static final String ACCOUNTING_STALE = "ACCOUNTING_STALE";
    public static final String HIGH_CPU = "HIGH_CPU";
    public static final String HIGH_MEMORY = "HIGH_MEMORY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "router_id", nullable = false)
    private Router router;

    @Column(nullable = false, length = 40)
    private String type;

    /** INFO, WARNING, CRITICAL */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String severity = "WARNING";

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String detail;

    @Column(name = "opened_at", nullable = false)
    @Builder.Default
    private OffsetDateTime openedAt = OffsetDateTime.now();

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;
}
