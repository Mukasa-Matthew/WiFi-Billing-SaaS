package com.wifibilling.radius;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "radreply")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadReply {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(nullable = false, length = 64)
    private String attribute;

    @Column(nullable = false, length = 2)
    private String op;

    @Column(nullable = false, length = 253)
    private String value;
}
