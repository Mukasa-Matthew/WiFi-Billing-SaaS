package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * Received MarzPay webhook — dedup/audit record. payloadHash is unique so a
 * redelivered callback can never be processed twice.
 */
@Entity
@Table(name = "webhook_events", indexes = {
        @Index(columnList = "reference"),
        @Index(columnList = "created_at")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WebhookEvent {

    public static final String PROCESSED = "PROCESSED";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String REJECTED = "REJECTED";
    public static final String FAILED = "FAILED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64)
    private String eventType;

    /** Business reference resolved from the payload (collection ref or payout provider_reference). */
    @Column(length = 64)
    private String reference;

    /** SHA-256 of the raw body — unique idempotency key. */
    @Column(nullable = false, unique = true, length = 64)
    private String payloadHash;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = PROCESSED;

    @Column(length = 255)
    private String note;

    /** Raw payload retained for reconciliation (no secrets — MarzPay never sends them). */
    @Column(columnDefinition = "text")
    private String payload;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
