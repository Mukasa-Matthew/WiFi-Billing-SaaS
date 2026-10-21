package com.wifibilling.model;
import jakarta.persistence.*;import lombok.*;import java.time.OffsetDateTime;
@Entity @Table(name="router_tasks") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RouterTask {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(nullable=false) private Router router;
 @Column(length=30,nullable=false) private String action;
 @Column(length=12000,nullable=false) private String script;
 @Builder.Default private String status="PENDING";
 @Builder.Default private OffsetDateTime createdAt=OffsetDateTime.now();
 private OffsetDateTime appliedAt;
}
