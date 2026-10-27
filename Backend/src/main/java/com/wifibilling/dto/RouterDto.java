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
            @Size(max = 32) List<@Pattern(regexp = "[A-Za-z0-9._-]{1,32}") String> hotspotInterfaces,
            @Pattern(regexp = "[\\x20-\\x7E]{1,32}") String wifiSsid) {
    }

    /**
     * Router as shown in lists and detail views. Deliberately carries no
     * credentials: the provisioning token, RADIUS secret and WireGuard key
     * material never leave the server through this record. {@code registered}
     * only says whether the router has registered its WireGuard key.
     */
    public record Response(
            Long id,
            String name,
            String wgIp,
            boolean registered,
            String wanInterface,
            List<String> hotspotInterfaces,
            String wifiSsid,
            String status,
            Integer cpuLoad,
            Integer memoryUsage,
            String uptime,
            Integer activeUsers,
            Boolean hotspotReady,
            Boolean radiusReady,
            Boolean portalReady,
            OffsetDateTime lastSeen,
            OffsetDateTime createdAt) {
    }

    /** Which setup a code is for: the full provisioning script or only the management agent. */
    public record SetupCodeRequest(String purpose) {
    }

    /**
     * A freshly issued one-time setup code. The plaintext exists only in this
     * response; the server keeps a hash. {@code path} is relative to the API origin.
     */
    public record SetupCode(Long routerId, String purpose, String path, OffsetDateTime expiresAt) {
    }

    /**
     * The router's public portal identifier, for the owner's portal preview.
     * It is embedded in customer-facing portal pages and authorizes no setup or
     * credential access.
     */
    public record PortalId(Long routerId, String portalId) {
    }
}
