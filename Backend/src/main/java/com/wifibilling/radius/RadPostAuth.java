package com.wifibilling.radius;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * Maps standard FreeRADIUS radpostauth table (authentication accept/reject log).
 * NOTE: the {@code pass} column contains the submitted credential and is
 * intentionally never mapped — it must never leave the database.
 */
@Entity
@Table(name = "radpostauth")
@Getter
@NoArgsConstructor
public class RadPostAuth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "reply", length = 32)
    private String reply;

    /** NAS identifier — populated on routers configured to send it. */
    @Column(name = "calledstationid")
    private String calledStationId;

    /** Client MAC address. */
    @Column(name = "callingstationid")
    private String callingStationId;

    @Column(name = "authdate", nullable = false)
    private OffsetDateTime authDate;
}
