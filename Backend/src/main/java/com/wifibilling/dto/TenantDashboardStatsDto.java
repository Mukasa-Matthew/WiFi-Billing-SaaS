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
    List<Map<String, Object>> routerRevenue,
    List<ActiveSessionDto> topConsumers,
    String range,
    List<Map<String, Object>> revenueSeries,
    List<Map<String, Object>> dailyActiveUsers,
    List<Map<String, Object>> registrations,
    List<Map<String, Object>> topDataUsers,
    List<Map<String, Object>> packagePerformance,
    Integer avgCpuLoad,
    Integer avgMemoryUsage,
    long dataMonthDownBytes,
    long dataMonthUpBytes,
    long totalCustomers,
    long newCustomersThisWeek
) {}
