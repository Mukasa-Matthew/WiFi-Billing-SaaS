package com.wifibilling.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public class RouterDto {

    public record CreateRequest(
            @NotBlank @Size(max = 80) String name,
            @Pattern(regexp = "[A-Za-z0-9._-]{1,32}") String wanInterface,
            @Size(max = 10) List<@Pattern(regexp = "[A-Za-z0-9._-]{1,32}") String> hotspotInterfaces,
            @Pattern(regexp = "[\\x20-\\x7E]{1,32}") String wifiSsid) {
    }

    public record Response(
            Long id,
            String name,
            String token,
            String wgIp,
            String wgPublicKey,
            String wanInterface,
            List<String> hotspotInterfaces,
            String wifiSsid,
            String status,
            Integer cpuLoad,
            Integer memoryUsage,
            String uptime,
            Integer activeUsers,
            OffsetDateTime lastSeen,
            OffsetDateTime createdAt) {
    }
}
