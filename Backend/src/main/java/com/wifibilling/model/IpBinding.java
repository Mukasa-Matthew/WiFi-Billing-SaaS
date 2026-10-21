package com.wifibilling.model;
import jakarta.persistence.*;import lombok.*;import java.time.OffsetDateTime;
@Entity @Table(name="ip_bindings") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class IpBinding {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(nullable=false) private Tenant tenant;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(nullable=false) private Router router;
 @ManyToOne(fetch=FetchType.LAZY) private PackagePlan packagePlan;
 @Column(length=120,nullable=false) private String name;
 @Column(length=17,nullable=false) private String mac;
 @Column(length=15) private String address;
 private OffsetDateTime expiresAt;
 @Builder.Default private Boolean removed=false;
 @Builder.Default private OffsetDateTime createdAt=OffsetDateTime.now();
}
