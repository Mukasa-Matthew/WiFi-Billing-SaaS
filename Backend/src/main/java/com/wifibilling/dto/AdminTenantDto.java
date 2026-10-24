package com.wifibilling.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public class AdminTenantDto {

    public record CreateRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Email @Size(max = 120) String email,
            @NotBlank @Size(max = 30) String phone,
            @NotBlank @Size(min = 6) String password) {
    }

    public record UpdateStatusRequest(
            @NotBlank String status) { // ACTIVE, SUSPENDED, DISABLED
    }

    public record SummaryResponse(
            Long id,
            String name,
            String email,
            String phone,
            String role,
            String status,
            int routerCount,
            long voucherCount,
            OffsetDateTime createdAt) {
    }

    public record PlatformMetricsResponse(
            long totalTenants,
            long totalRouters,
            long onlineRouters,
            long totalVouchers,
            long totalTransactions,
            java.math.BigDecimal totalRevenueUgx,
            long activeHotspotSessions) {
    }

    public record RouterFleetResponse(
            Long id,
            Long tenantId,
            String tenantName,
            String name,
            String status,
            String managementIp,
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
}
