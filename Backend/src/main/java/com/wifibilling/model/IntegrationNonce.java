package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Nonces of signed requests from the school identity system, kept briefly so a request can never be replayed. */
@Entity
@Table(name = "integration_nonces", indexes = @Index(columnList = "received_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationNonce {
    @Id
    @Column(length = 64)
    private String nonce;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;
}
