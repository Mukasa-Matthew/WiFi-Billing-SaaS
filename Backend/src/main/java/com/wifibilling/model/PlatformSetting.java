package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/** Tiny key-value store for platform-wide settings (e.g. default subscription fee). */
@Entity
@Table(name = "platform_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PlatformSetting {

    @Id
    @Column(name = "setting_key", length = 64)
    private String key;

    @Column(nullable = false, length = 255)
    private String value;

    @Column(name = "updated_at")
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PrePersist @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }
}
