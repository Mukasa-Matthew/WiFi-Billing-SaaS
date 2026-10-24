package com.wifibilling.service;

import com.wifibilling.dto.ActiveSessionDto;
import com.wifibilling.dto.TenantDashboardStatsDto;
import com.wifibilling.model.Customer;
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
    private final com.wifibilling.repository.RadAcctRepository radAcctRepository;
    private final com.wifibilling.repository.PackagePlanRepository planRepository;
    private final com.wifibilling.repository.CustomerRepository customerRepository;

    @Transactional(readOnly = true)
    public TenantDashboardStatsDto getStats(Long tenantId, String role) {
        return getStats(tenantId, role, "7d");
    }

    @Transactional(readOnly = true)
    public TenantDashboardStatsDto getStats(Long tenantId, String role, String range) {
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
        // Remaining vouchers only — used, expired, and revoked ones no longer count.
        long activeVouchers = vouchers.stream().filter(v -> "active".equalsIgnoreCase(v.getStatus())).count();
        long usedVouchers = vouchers.stream().filter(v -> "used".equalsIgnoreCase(v.getStatus())).count();
        long expiredVouchers = vouchers.stream().filter(v -> "expired".equalsIgnoreCase(v.getStatus()) || "revoked".equalsIgnoreCase(v.getStatus())).count();
        long totalVouchers = vouchers.size() - usedVouchers - expiredVouchers;

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

        // ---- Ranged analytics (period filters on the dashboard) ----
        String r = range == null ? "7d" : range;
        LocalDate today = now.toLocalDate();
        OffsetDateTime thisMonday = today.with(java.time.DayOfWeek.MONDAY).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime yearStart = today.withDayOfYear(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        record Win(OffsetDateTime s, OffsetDateTime e, boolean monthly) {}
        Win w = switch (r) {
            case "today" -> new Win(startOfDay, now, false);
            case "week" -> new Win(thisMonday, now, false);
            case "lastweek" -> new Win(thisMonday.minusDays(7), thisMonday, false);
            case "month" -> new Win(startOfMonth, now, false);
            case "lastmonth" -> new Win(startOfMonth.minusMonths(1), startOfMonth, false);
            case "year" -> new Win(yearStart, now, true);
            case "lastyear" -> new Win(yearStart.minusYears(1), yearStart, true);
            default -> new Win(startOfDay.minusDays(6), now, false);
        };
        if (!List.of("today","7d","week","lastweek","month","lastmonth","year","lastyear").contains(r)) r = "7d";
        final OffsetDateTime rs = w.s(); final OffsetDateTime re = w.e(); final boolean monthly = w.monthly();
        List<LocalDate> buckets = new ArrayList<>();
        if (monthly) {
            LocalDate cursor = rs.toLocalDate().withDayOfMonth(1);
            while (!cursor.isAfter(re.toLocalDate())) { buckets.add(cursor); cursor = cursor.plusMonths(1); }
        } else {
            LocalDate cursor = rs.toLocalDate();
            while (!cursor.isAfter(re.toLocalDate())) { buckets.add(cursor); cursor = cursor.plusDays(1); }
        }
        Map<LocalDate, BigDecimal> rev = new LinkedHashMap<>();
        Map<LocalDate, Integer> txCount = new LinkedHashMap<>();
        Map<LocalDate, Integer> regs = new LinkedHashMap<>();
        Map<LocalDate, Set<String>> users = new LinkedHashMap<>();
        for (LocalDate d : buckets) { rev.put(d, BigDecimal.ZERO); txCount.put(d, 0); regs.put(d, 0); users.put(d, new HashSet<>()); }
        java.util.function.BiFunction<OffsetDateTime, Map<LocalDate, ?>, LocalDate> bucketKey = (ts, m) -> {
            if (ts == null || ts.isBefore(rs) || !ts.isBefore(re.plusNanos(1))) return null;
            LocalDate k = monthly ? ts.toLocalDate().withDayOfMonth(1) : ts.toLocalDate();
            return m.containsKey(k) ? k : null;
        };
        for (Transaction t : transactions) {
            LocalDate k = bucketKey.apply(t.getCreatedAt(), rev);
            if (k != null && "SUCCESSFUL".equalsIgnoreCase(t.getStatus())) {
                rev.put(k, rev.get(k).add(t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO));
                txCount.put(k, txCount.get(k) + 1);
            }
        }
        Map<String, Voucher> byCode = new HashMap<>();
        for (Voucher v : vouchers) {
            byCode.put(v.getCode(), v);
            LocalDate k = bucketKey.apply(v.getCreatedAt(), regs);
            if (k != null) regs.put(k, regs.get(k) + 1);
        }
        List<com.wifibilling.radius.RadAcct> accts = byCode.isEmpty() ? List.of()
                : radAcctRepository.findAllByUsernameIn(byCode.keySet());
        Map<String, Long> octets = new HashMap<>();
        for (var a : accts) {
            OffsetDateTime ts = a.getAcctStartTime() != null ? a.getAcctStartTime() : a.getAcctStopTime();
            LocalDate k = bucketKey.apply(ts, users);
            if (k != null && a.getUsername() != null) {
                users.get(k).add(a.getUsername());
                octets.merge(a.getUsername(), (a.getAcctInputOctets() != null ? a.getAcctInputOctets() : 0L)
                        + (a.getAcctOutputOctets() != null ? a.getAcctOutputOctets() : 0L), Long::sum);
            }
        }
        List<Map<String, Object>> revenueSeries = new ArrayList<>(), dailyUsers = new ArrayList<>(), registrations = new ArrayList<>();
        for (LocalDate d : buckets) {
            String label = monthly ? d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) : d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            revenueSeries.add(Map.of("date", d.toString(), "label", label, "revenue", rev.get(d), "transactions", txCount.get(d)));
            dailyUsers.add(Map.of("date", d.toString(), "label", label, "users", users.get(d).size()));
            registrations.add(Map.of("date", d.toString(), "label", label, "registrations", regs.get(d)));
        }
        List<Map<String, Object>> topDataUsers = octets.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5)
                .map(e -> {
                    Voucher v = byCode.get(e.getKey());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("username", e.getKey());
                    m.put("customer", v != null && v.getCustomer() != null ? v.getCustomer().getName() : null);
                    m.put("phone", v != null && v.getCustomer() != null ? v.getCustomer().getPhone() : null);
                    m.put("package", v != null && v.getPackagePlan() != null ? v.getPackagePlan().getName() : null);
                    m.put("octets", e.getValue());
                    return m;
                }).toList();
        Map<String, Long> activeByPlan = new HashMap<>();
        for (var s : activeSessions) {
            Voucher v = byCode.get(s.username());
            if (v != null && v.getPackagePlan() != null) activeByPlan.merge(v.getPackagePlan().getName(), 1L, Long::sum);
        }
        List<Map<String, Object>> packagePerformance = new ArrayList<>();
        for (var p : planRepository.findAllByTenantIdOrderByPriceAsc(tenantId)) {
            BigDecimal mRev = BigDecimal.ZERO; long sold = 0, monthSold = 0;
            for (Transaction t : transactions) {
                if (!"SUCCESSFUL".equalsIgnoreCase(t.getStatus()) || t.getPackagePlan() == null
                        || !t.getPackagePlan().getId().equals(p.getId())) continue;
                sold++;
                if (t.getCreatedAt() != null && !t.getCreatedAt().isBefore(startOfMonth)) { mRev = mRev.add(t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO); monthSold++; }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId()); m.put("name", p.getName()); m.put("price", p.getPrice());
            m.put("activeUsers", activeByPlan.getOrDefault(p.getName(), 0L));
            m.put("monthlyRevenue", mRev); m.put("totalSold", sold);
            m.put("arpu", monthSold > 0 ? mRev.divide(BigDecimal.valueOf(monthSold), 0, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO);
            packagePerformance.add(m);
        }

        // Card extras: router CPU/RAM averages, monthly data split, customer totals
        List<Router> live = routers.stream()
                .filter(rt -> "online".equalsIgnoreCase(rt.getStatus()) || (rt.getLastSeen() != null && rt.getLastSeen().isAfter(activeCutoff)))
                .toList();
        int avgCpu = live.isEmpty() ? 0 : (int) Math.round(live.stream().mapToInt(rt -> rt.getCpuLoad() != null ? rt.getCpuLoad() : 0).average().orElse(0));
        int avgMem = live.isEmpty() ? 0 : (int) Math.round(live.stream().mapToInt(rt -> rt.getMemoryUsage() != null ? rt.getMemoryUsage() : 0).average().orElse(0));
        long downMonth = 0, upMonth = 0;
        for (var a : accts) {
            OffsetDateTime ts = a.getAcctStartTime() != null ? a.getAcctStartTime() : a.getAcctStopTime();
            if (ts != null && !ts.isBefore(startOfMonth)) {
                downMonth += a.getAcctOutputOctets() != null ? a.getAcctOutputOctets() : 0L;
                upMonth += a.getAcctInputOctets() != null ? a.getAcctInputOctets() : 0L;
            }
        }
        List<Customer> customers = customerRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        long newThisWeek = customers.stream().filter(c -> c.getCreatedAt() != null && !c.getCreatedAt().isBefore(thisMonday)).count();

        return TenantDashboardStatsDto.builder()
                .range(r)
                .avgCpuLoad(avgCpu)
                .avgMemoryUsage(avgMem)
                .dataMonthDownBytes(downMonth)
                .dataMonthUpBytes(upMonth)
                .totalCustomers(customers.size())
                .newCustomersThisWeek(newThisWeek)
                .revenueSeries(revenueSeries)
                .dailyActiveUsers(dailyUsers)
                .registrations(registrations)
                .topDataUsers(topDataUsers)
                .packagePerformance(packagePerformance)
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

    private static LocalDate toLocalDate(Object v) {
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        if (v instanceof OffsetDateTime odt) return odt.toLocalDate();
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(String.valueOf(v).substring(0, 10));
    }
}
