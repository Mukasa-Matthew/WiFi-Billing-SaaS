package com.wifibilling.service;

import com.wifibilling.dto.ActiveSessionDto;
import com.wifibilling.dto.TenantDashboardStatsDto;
import com.wifibilling.model.Router;
import com.wifibilling.model.Transaction;
import com.wifibilling.model.Voucher;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TenantDashboardService {

    private final TransactionRepository transactionRepository;
    private final VoucherRepository voucherRepository;
    private final RouterRepository routerRepository;
    private final SessionService sessionService;

    @Transactional(readOnly = true)
    public TenantDashboardStatsDto getStats(Long tenantId, String role) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime startOfDay = now.toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime startOfMonth = now.toLocalDate().withDayOfMonth(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        // Transactions
        List<Transaction> transactions = transactionRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        BigDecimal revToday = BigDecimal.ZERO;
        BigDecimal revMonth = BigDecimal.ZERO;

        Map<LocalDate, BigDecimal> dailyTotals = new LinkedHashMap<>();
        Map<LocalDate, Integer> dailyCounts = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate d = now.toLocalDate().minusDays(i);
            dailyTotals.put(d, BigDecimal.ZERO);
            dailyCounts.put(d, 0);
        }

        for (Transaction t : transactions) {
            if ("SUCCESSFUL".equalsIgnoreCase(t.getStatus())) {
                BigDecimal amt = t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO;
                if (t.getCreatedAt() != null) {
                    if (!t.getCreatedAt().isBefore(startOfDay)) {
                        revToday = revToday.add(amt);
                    }
                    if (!t.getCreatedAt().isBefore(startOfMonth)) {
                        revMonth = revMonth.add(amt);
                    }
                    LocalDate txDate = t.getCreatedAt().toLocalDate();
                    if (dailyTotals.containsKey(txDate)) {
                        dailyTotals.put(txDate, dailyTotals.get(txDate).add(amt));
                        dailyCounts.put(txDate, dailyCounts.get(txDate) + 1);
                    }
                }
            }
        }

        List<Map<String, Object>> weeklySales = new ArrayList<>();
        for (Map.Entry<LocalDate, BigDecimal> entry : dailyTotals.entrySet()) {
            LocalDate date = entry.getKey();
            String dayName = date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            weeklySales.add(Map.of(
                    "date", date.toString(),
                    "day", dayName,
                    "revenue", entry.getValue(),
                    "transactions", dailyCounts.getOrDefault(date, 0)
            ));
        }

        // Routers
        List<Router> routers = routerRepository.findAllByTenantId(tenantId);
        long totalRouters = routers.size();
        OffsetDateTime activeCutoff = now.minusMinutes(6);
        long onlineRouters = routers.stream()
                .filter(r -> "online".equalsIgnoreCase(r.getStatus()) || (r.getLastSeen() != null && r.getLastSeen().isAfter(activeCutoff)))
                .count();

        // Vouchers
        List<Voucher> vouchers = voucherRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        long totalVouchers = vouchers.size();
        long activeVouchers = vouchers.stream().filter(v -> "active".equalsIgnoreCase(v.getStatus())).count();
        long usedVouchers = vouchers.stream().filter(v -> "used".equalsIgnoreCase(v.getStatus())).count();
        long expiredVouchers = vouchers.stream().filter(v -> "expired".equalsIgnoreCase(v.getStatus()) || "revoked".equalsIgnoreCase(v.getStatus())).count();

        // Sessions
        List<ActiveSessionDto> activeSessions = sessionService.getActiveSessions(tenantId, role);
        long activeUsers = activeSessions.size();

        // Per-router revenue: how much each site earns for this tenant
        Map<Long, BigDecimal[]> perRouter = new HashMap<>();
        Map<Long, Long> perRouterCount = new HashMap<>();
        for (Transaction t : transactions) {
            if (!"SUCCESSFUL".equalsIgnoreCase(t.getStatus()) || t.getRouter() == null || t.getCreatedAt() == null) {
                continue;
            }
            Long routerId = t.getRouter().getId();
            BigDecimal amt = t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO;
            BigDecimal[] bucket = perRouter.computeIfAbsent(routerId, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            bucket[2] = bucket[2].add(amt); // total
            if (!t.getCreatedAt().isBefore(startOfDay)) bucket[0] = bucket[0].add(amt);   // today
            if (!t.getCreatedAt().isBefore(startOfMonth)) bucket[1] = bucket[1].add(amt); // month
            perRouterCount.merge(routerId, 1L, Long::sum);
        }
        List<Map<String, Object>> routerRevenue = new ArrayList<>();
        for (Router r : routers) {
            BigDecimal[] bucket = perRouter.getOrDefault(r.getId(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            String wgIp = com.wifibilling.util.Ipv4Subnet.stripPrefix(r.getWgIp());
            long users = wgIp == null ? 0 : activeSessions.stream()
                    .filter(s -> wgIp.equals(s.nasIpAddress())).count();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("routerId", r.getId());
            row.put("name", r.getName());
            row.put("status", r.getStatus());
            row.put("revenueToday", bucket[0]);
            row.put("revenueMonth", bucket[1]);
            row.put("revenueTotal", bucket[2]);
            row.put("transactions", perRouterCount.getOrDefault(r.getId(), 0L));
            row.put("activeUsers", users);
            routerRevenue.add(row);
        }

        long totalDataConsumed = activeSessions.stream()
                .mapToLong(s -> s.totalOctets() != null ? s.totalOctets() : 0L)
                .sum();

        List<ActiveSessionDto> topConsumers = activeSessions.stream()
                .sorted(Comparator.comparing(ActiveSessionDto::totalOctets).reversed())
                .limit(5)
                .toList();

        return TenantDashboardStatsDto.builder()
                .revenueToday(revToday)
                .revenueMonth(revMonth)
                .activeUsers(activeUsers)
                .totalDataConsumedBytes(totalDataConsumed)
                .totalRouters(totalRouters)
                .onlineRouters(onlineRouters)
                .totalVouchers(totalVouchers)
                .activeVouchers(activeVouchers)
                .usedVouchers(usedVouchers)
                .expiredVouchers(expiredVouchers)
                .weeklySales(weeklySales)
                .routerRevenue(routerRevenue)
                .topConsumers(topConsumers)
                .build();
    }
}
