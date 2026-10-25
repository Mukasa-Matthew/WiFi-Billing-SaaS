package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * One-time passcode delivered by email. Used for withdrawal confirmation and
 * password reset. Codes are stored as SHA-256 hashes — plaintext never persisted.
 */
@Entity
@Table(name = "email_otps", indexes = {
        @Index(columnList = "email,purpose,created_at")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmailOtp {

    public static final String PURPOSE_WITHDRAWAL = "WITHDRAWAL";
    public static final String PURPOSE_PASSWORD_RESET = "PASSWORD_RESET";
    public static final String PURPOSE_EMAIL_CHANGE = "EMAIL_CHANGE";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    /** Tenant the code belongs to (nullable for reset on unknown email — we still
     *  burn the code so the flow is indistinguishable). */
    private Long tenantId;

    @Column(nullable = false, length = 32)
    private String purpose;

    /** SHA-256 hex of the 6-digit code. */
    @Column(nullable = false, length = 64)
    private String codeHash;

    @Column(nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    private OffsetDateTime consumedAt;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
