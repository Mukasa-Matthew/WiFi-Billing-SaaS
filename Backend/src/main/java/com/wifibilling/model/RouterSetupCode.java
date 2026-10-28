package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Short-lived, owner-issued authorization for one router setup.
 *
 * Only the SHA-256 hash of the code is stored. The plaintext is shown to the
 * owner once, in the setup command. A FULL code releases the credential-bearing
 * provisioning script exactly once (scriptFetchedAt), then authorizes the
 * router's own register/complete callbacks for a bounded window until
 * completedAt. A MANAGEMENT code releases only the management-agent installer.
 */
@Entity
@Table(name = "router_setup_codes", indexes = @Index(name = "ux_router_setup_code_hash", columnList = "codeHash", unique = true))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RouterSetupCode {

    public enum Purpose { FULL, MANAGEMENT, WINBOX }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(nullable = false)
    private Router router;

    @Column(nullable = false, length = 64)
    private String codeHash;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private Purpose purpose;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    /** Last moment the script may be fetched. */
    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    private OffsetDateTime scriptFetchedAt;

    /** SHA-256 of the first WireGuard key registered with this code; later calls must repeat it. */
    @Column(length = 64)
    private String registeredKeyHash;

    private OffsetDateTime completedAt;
    private OffsetDateTime revokedAt;
}
