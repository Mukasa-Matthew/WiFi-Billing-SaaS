package com.wifibilling.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Builder
public record TenantDashboardStatsDto(
    BigDecimal revenueToday,
    BigDecimal revenueMonth,
    long activeUsers,
    long totalDataConsumedBytes,
    long totalRouters,
    long onlineRouters,
    long totalVouchers,
    long activeVouchers,
    long usedVouchers,
    long expiredVouchers,
    List<Map<String, Object>> weeklySales,
    List<ActiveSessionDto> topConsumers
) {}
