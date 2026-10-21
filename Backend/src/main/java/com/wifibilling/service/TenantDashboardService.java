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
                .topConsumers(topConsumers)
                .build();
    }
}
