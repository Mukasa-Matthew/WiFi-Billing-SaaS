package com.wifibilling.radius;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * Maps standard FreeRADIUS radacct accounting table.
 */
@Entity
@Table(name = "radacct")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadAcct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "radacctid")
    private Long radAcctId;

    @Column(name = "acctsessionid", nullable = false, length = 64)
    private String acctSessionId;

    @Column(name = "acctuniqueid", nullable = false, length = 32)
    private String acctUniqueId;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    @Column(name = "groupname", length = 64)
    private String groupName;

    @Column(name = "realm", length = 64)
    private String realm;

    @Column(name = "nasipaddress", nullable = false, length = 15)
    private String nasIpAddress;

    @Column(name = "nasportid", length = 32)
    private String nasPortId;

    @Column(name = "nasporttype", length = 32)
    private String nasPortType;

    @Column(name = "acctstarttime")
    private OffsetDateTime acctStartTime;

    @Column(name = "acctupdatetime")
    private OffsetDateTime acctUpdateTime;

    @Column(name = "acctstoptime")
    private OffsetDateTime acctStopTime;

    @Column(name = "acctinterval")
    private Integer acctInterval;

    @Column(name = "acctsessiontime")
    private Long acctSessionTime;

    @Column(name = "acctauthentic", length = 32)
    private String acctAuthentic;

    @Column(name = "connectinfo_start", length = 128)
    private String connectInfoStart;

    @Column(name = "connectinfo_stop", length = 128)
    private String connectInfoStop;

    @Column(name = "acctinputoctets")
    private Long acctInputOctets;

    @Column(name = "acctoutputoctets")
    private Long acctOutputOctets;

    @Column(name = "calledstationid", length = 50)
    private String calledStationId;

    @Column(name = "callingstationid", length = 50)
    private String callingStationId;

    @Column(name = "acctterminatecause", length = 32)
    private String acctTerminateCause;

    @Column(name = "servicetype", length = 32)
    private String serviceType;

    @Column(name = "framedprotocol", length = 32)
    private String framedProtocol;

    @Column(name = "framedipaddress", length = 15)
    private String framedIpAddress;

    @Column(name = "framedipv6address", length = 45)
    private String framedIpv6Address;

    @Column(name = "framedipv6prefix", length = 45)
    private String framedIpv6Prefix;

    @Column(name = "framedinterfaceid", length = 44)
    private String framedInterfaceId;

    @Column(name = "delegatedipv6prefix", length = 45)
    private String delegatedIpv6Prefix;
}
