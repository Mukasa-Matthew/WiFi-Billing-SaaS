package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * Audit log recording platform and account security events.
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(columnList = "tenant_id,created_at"),
        @Index(columnList = "action,created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(nullable = false, length = 64)
    private String action; // e.g., 2FA_ENABLED, 2FA_DISABLED, PASSWORD_CHANGED, EMAIL_CHANGED, PHONE_CHANGED, SESSION_REVOKED

    @Column(length = 120)
    private String actorEmail;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(length = 500)
    private String details;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
