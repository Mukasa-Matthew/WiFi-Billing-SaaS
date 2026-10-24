package com.wifibilling.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/** Payload pushed by the router's wb-telemetry scheduler every 2 minutes. */
public record TelemetryRequest(
        @NotBlank String token,
        @PositiveOrZero Integer cpuLoad,
        @PositiveOrZero Long freeMemory,
        @PositiveOrZero Long totalMemory,
        String uptime,
        @PositiveOrZero Integer activeUsers,
        Boolean hotspotReady,
        Boolean radiusReady,
        Boolean portalReady) {
}
