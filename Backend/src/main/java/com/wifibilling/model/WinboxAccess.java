package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.OffsetDateTime;

@Entity
@Table(name = "winbox_access")
@Getter @Setter @NoArgsConstructor
public class WinboxAccess {
    @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "winbox_ports")
    @SequenceGenerator(name = "winbox_ports", sequenceName = "winbox_public_port_seq", initialValue = 30000, allocationSize = 1)
    private Integer port;
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Router router;
    @Column(nullable = false) private Long tenantId;
    @Column(nullable = false, length = 15) private String tunnelIp;
    @Column(nullable = false, length = 64) private String peerKey;
    @Column(nullable = false, length = 48) private String username;
    @Column(nullable = false, length = 512) private String passwordCiphertext;
    @Column(length = 512) private String agentCiphertext;
    @Column(length = 64) private String agentHash;
    private boolean enabled;
    private long revision;
    private long appliedRevision;
    private OffsetDateTime routerCheckedAt;
    @Column(length = 32) private String routerResult;
    @Column(length = 32) private String gatewayResult;
    private long gatewayRevision;
    private OffsetDateTime gatewayCheckedAt;
    private OffsetDateTime lastSuccessfulCheck;
    @Version private long version;
}
