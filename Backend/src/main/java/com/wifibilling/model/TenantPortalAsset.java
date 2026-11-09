package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * A tenant's captive-portal branding image (logo or background), stored in the
 * database so it survives blue-green deploys and is covered by database backups.
 * Table: deployment/database/05-tenant-portal-assets.sql.
 */
@Entity
@Table(name = "tenant_portal_assets",
        uniqueConstraints = @UniqueConstraint(name = "ux_tenant_portal_assets_tenant_kind", columnNames = {"tenant_id", "kind"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TenantPortalAsset {
    public static final String LOGO = "logo";
    public static final String BACKGROUND = "background";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(name = "content_type", nullable = false, length = 40)
    private String contentType;

    /** bytea, as in the migration; plain binary (not a LOB) so it reads in one query. */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] data;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
