package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "portal_feedback", indexes = @Index(columnList = "tenant_id,created_at"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PortalFeedback {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "router_id")
    private Router router;

    @Column(length = 120)
    private String name;

    @Column(length = 32)
    private String phone;

    @Column(length = 20)
    private String mac;

    private Integer rating; // 1-5, optional

    @Column(nullable = false, length = 1000)
    private String message;

    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
