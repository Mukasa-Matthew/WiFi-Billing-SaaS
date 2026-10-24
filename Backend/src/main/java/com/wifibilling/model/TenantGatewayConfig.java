package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * Per-tenant payment gateway configuration. Exactly one row per tenant.
 * Credentials are AES-GCM encrypted at rest and never returned to the frontend.
 */
@Entity
@Table(name = "tenant_gateway_config")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TenantGatewayConfig {

    public static final String MODE_PLATFORM = "PLATFORM";
    public static final String MODE_OWN = "OWN";

    public static final String CONN_UNCONFIGURED = "UNCONFIGURED";
    public static final String CONN_CONNECTED = "CONNECTED";
    public static final String CONN_FAILED = "FAILED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, unique = true)
    private Tenant tenant;

    /** PLATFORM = collections via the platform MarzPay account into the tenant's
     *  internal wallet; OWN = tenant's own MarzPay credentials, funds settle to them. */
    @Column(nullable = false, length = 16)
    @Builder.Default
    private String mode = MODE_PLATFORM;

    // --- Own-gateway credentials (encrypted) — only used when mode = OWN ---
    @Column(name = "api_key", length = 255)
    private String apiKey;

    @Column(name = "api_secret_enc", length = 2000)
    private String apiSecretEnc;

    @Column(name = "webhook_secret_enc", length = 2000)
    private String webhookSecretEnc;

    /** MarzPay environment: live or sandbox (advisory — env is a property of the key pair). */
    @Column(length = 16)
    @Builder.Default
    private String environment = "live";

    @Column(nullable = false, length = 8)
    @Builder.Default
    private String country = "UG";

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String connectionStatus = CONN_UNCONFIGURED;

    @Column(length = 255)
    private String connectionError;

    private OffsetDateTime lastVerifiedAt;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    private OffsetDateTime updatedAt;

    @PrePersist @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }
}
