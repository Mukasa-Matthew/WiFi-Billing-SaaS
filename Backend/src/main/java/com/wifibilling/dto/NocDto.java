package com.wifibilling.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public class NocDto {

    public record Overview(
            long totalTenants,
            long totalRouters,
            long onlineRouters,
            long offlineRouters,
            long attentionRouters,
            long activeSessions,
            long staleOpenSessions,
            long dataBytesLast24h,
            long authFailuresLast24h,
            long failedTasksLast7d,
            long openIncidents,
            List<IncidentItem> incidents
    ) {}

    public record TenantItem(
            Long tenantId,
            String tenantName,
            String status,
            long routerCount,
            long onlineRouters,
            long attentionRouters
    ) {}

    public record RouterRow(
            Long id,
            Long tenantId,
            String tenantName,
            String name,
            String status,
            String wgIp,
            Integer cpuLoad,
            Integer memoryUsage,
            String uptime,
            Integer activeUsers,
            Boolean hotspotReady,
            Boolean radiusReady,
            Boolean portalReady,
            boolean bindingsSynced,
            String provisioningState,
            OffsetDateTime lastSeen,
            OffsetDateTime bindingsSyncedAt,
            OffsetDateTime hostsSeenAt,
            long openSessions,
            long openIncidents,
            OffsetDateTime createdAt
    ) {}

    public record RouterDetail(
            RouterRow router,
            String radiusNasRegistered,
            OffsetDateTime lastAccountingAt,
            Map<String, Object> live,
            String liveSource
    ) {}

    public record SessionRow(
            Long id,
            String username,
            String acctSessionId,
            String framedIp,
            String mac,
            String nasIp,
            OffsetDateTime startedAt,
            OffsetDateTime lastUpdateAt,
            OffsetDateTime stoppedAt,
            Long sessionSeconds,
            Long uploadOctets,
            Long downloadOctets,
            String terminateCause,
            /** LIVE = open & recently updated, OPEN_STALE = open but no recent update, CLOSED = stopped */
            String state,
            Long packageId,
            String packageName,
            Long remainingSeconds
    ) {}

    public record PageResponse<T>(
            List<T> content, int page, int size, long totalElements, int totalPages
    ) {}

    public record AuthEventRow(
            Long id,
            OffsetDateTime at,
            String username,
            String reply,
            String mac,
            String severity,
            String description
    ) {}

    public record IncidentItem(
            Long id,
            Long routerId,
            String routerName,
            String tenantName,
            String type,
            String severity,
            String title,
            String detail,
            OffsetDateTime openedAt,
            OffsetDateTime resolvedAt
    ) {}

    public record EventItem(
            OffsetDateTime at,
            String type,
            String severity,
            String title,
            String detail
    ) {}

    public record TaskItem(
            Long id,
            String action,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime appliedAt
    ) {}

    public record DiagnosticCheck(
            String key,
            String label,
            /** HEALTHY, WARNING, CRITICAL, UNKNOWN */
            String status,
            /** LIVE = queried just now, INFERRED = derived from stored data */
            String source,
            String detail,
            String recommendation
    ) {}

    public record Diagnostics(List<DiagnosticCheck> checks) {}

    public record UsagePoint(String day, long uploadBytes, long downloadBytes, long users) {}
}
