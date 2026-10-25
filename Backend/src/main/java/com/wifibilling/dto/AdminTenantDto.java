package com.wifibilling.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public class AdminTenantDto {

    public record CreateRequest(
            @NotBlank(message = "Business name is required")
            @Size(max = 120)
            String name,

            @NotBlank(message = "Email is required")
            @Email(message = "Valid email is required")
            String email,

            @NotBlank(message = "Phone number is required")
            @Size(max = 30)
            String phone
    ) {}

    public record SummaryResponse(
            Long id,
            String name,
            String email,
            String phone,
            String role,
            String status,
            int routerCount,
            long voucherCount,
            OffsetDateTime createdAt
    ) {}

    public record CreateResponse(
            SummaryResponse tenant,
            String temporaryPassword,
            boolean welcomeEmailSent
    ) {}

    public record ResetCredentialsResponse(
            Long tenantId,
            String email,
            String temporaryPassword,
            boolean welcomeEmailSent
    ) {}

    public record UpdateStatusRequest(
            @NotBlank(message = "Status is required")
            String status
    ) {}

    public record PlatformMetricsResponse(
            long totalTenants,
            long totalRouters,
            long onlineRouters,
            long totalVouchers,
            long totalTransactions,
            java.math.BigDecimal totalRevenue,
            long activeGuestSessions
    ) {}

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
            OffsetDateTime createdAt
    ) {}
}
