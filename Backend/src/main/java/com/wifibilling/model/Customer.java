package com.wifibilling.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
@Entity @Table(name="customers") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Customer {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="tenant_id",nullable=false) private Tenant tenant;
 @Column(nullable=false,length=120) private String name;
 @Column(length=32) private String phone;
 @Column(length=500) private String notes;
 @Builder.Default private OffsetDateTime createdAt=OffsetDateTime.now();
}
