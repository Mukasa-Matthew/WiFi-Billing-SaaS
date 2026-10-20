package com.wifibilling.radius;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "nas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Nas {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String nasname;

    @Column(length = 32)
    private String shortname;

    @Column(length = 30)
    private String type;

    private Integer ports;

    @Column(nullable = false, length = 60)
    private String secret;

    @Column(length = 64)
    private String server;

    @Column(length = 50)
    private String community;

    @Column(length = 200)
    private String description;
}
