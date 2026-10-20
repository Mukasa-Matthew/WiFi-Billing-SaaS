package com.wifibilling.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public class RouterDto {

    public record CreateRequest(
            @NotBlank @Size(max = 80) String name) {
    }

    public record Response(
            Long id,
            String name,
            String token,
            String wgIp,
            String wgPublicKey,
            String status,
            Integer cpuLoad,
            Integer memoryUsage,
            String uptime,
            Integer activeUsers,
            OffsetDateTime lastSeen,
            OffsetDateTime createdAt) {
    }
}
