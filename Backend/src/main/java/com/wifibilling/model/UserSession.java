package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * Tracks active login sessions for accounts, enabling visibility, timestamp auditing,
 * and remote session revocation.
 */
@Entity
@Table(name = "user_sessions", indexes = {
        @Index(columnList = "tenant_id,revoked"),
        @Index(columnList = "session_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true, length = 64)
    private String sessionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(nullable = false)
    @Builder.Default
    private Boolean revoked = false;

    @Column(name = "last_active_at")
    @Builder.Default
    private OffsetDateTime lastActiveAt = OffsetDateTime.now();

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
