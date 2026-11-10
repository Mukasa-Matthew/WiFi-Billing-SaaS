package com.wifibilling.controller;

import com.wifibilling.model.AuditLog;
import com.wifibilling.model.Router;
import com.wifibilling.model.Subscription;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.AuditLogRepository;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.repository.SubscriptionRepository;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.service.ReportingClock;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Platform-wide business analytics for the superadmin command center: payment
 * volume per day, payment health, mobile-money network split, the busiest
 * businesses, businesses that have stopped selling, subscriptions that need
 * follow-up and the latest account activity.
 *
 * Day boundaries follow {@link ReportingClock} (Africa/Kampala by default).
 * "Gross" is what customers paid, which belongs to the tenants; the platform's
 * own share is the platform fee.
 */
@RestController
@RequestMapping("/api/admin/analytics")
@PreAuthorize("hasAuthority('ROLE_SUPERADMIN')")
@RequiredArgsConstructor
public class AdminAnalyticsController {

    static final int DORMANT_DAYS = 7;

    private final TransactionRepository transactions;
    private final TenantRepository tenants;
    private final RouterRepository routers;
    private final SubscriptionRepository subscriptions;
    private final AuditLogRepository auditLogs;
    private final ReportingClock clock;

    public record Day(String date, long gross, long sales, long failed, long platformFee) {}

    public record Totals(long gross, long sales, long failed, long pending, long platformFee) {}

    public record NetworkShare(String network, long gross, long sales) {}

    public record TopTenant(Long tenantId, String name, boolean school, long gross, long sales,
                            long platformFee, OffsetDateTime lastSaleAt) {}

    public record DormantTenant(Long tenantId, String name, boolean school, int routers,
                                OffsetDateTime lastSaleAt, OffsetDateTime joinedAt) {}

    public record SubscriptionFlag(Long tenantId, String name, String status,
                                   OffsetDateTime expiresAt, OffsetDateTime graceUntil) {}

    public record Activity(Long id, String action, String actor, Long tenantId, String tenantName,
                           String details, String ipAddress, OffsetDateTime at) {}

    public record TenantMix(long hotspot, long school, long active, long suspended, long disabled,
                            long withRouters, long withoutRouters) {}

    public record Overview(int days, String timezone, OffsetDateTime generatedAt,
                           List<Day> daily, Totals period, Totals previousPeriod, Totals today, Totals yesterday,
                           List<NetworkShare> networks, List<TopTenant> topTenants,
                           List<DormantTenant> dormant, long dormantCount, TenantMix tenantMix,
                           List<SubscriptionFlag> subscriptionFlags, List<Activity> activity) {}

    /** Mutable accumulator behind {@link Totals}. */
    private static final class Acc {
        long gross, sales, failed, pending, fee;

        void add(String status, long amount, long platformFee) {
            switch (status == null ? "" : status) {
                case "SUCCESSFUL" -> { gross += amount; sales++; fee += platformFee; }
                case "FAILED" -> failed++;
                case "PENDING" -> pending++;
                default -> { }
            }
        }

        Totals totals() { return new Totals(gross, sales, failed, pending, fee); }
    }

    @GetMapping("/overview")
    @Transactional(readOnly = true)
    public Overview overview(@RequestParam(defaultValue = "30") int days) {
        int span = Math.max(7, Math.min(days, 90));
        LocalDate today = clock.today();
        LocalDate firstDay = today.minusDays(span - 1L);
        LocalDate previousFirstDay = firstDay.minusDays(span);
        OffsetDateTime periodStart = clock.startOf(firstDay);
        OffsetDateTime todayStart = clock.startOf(today);
        OffsetDateTime yesterdayStart = clock.startOf(today.minusDays(1));

        List<Tenant> businesses = tenants.findAll().stream()
                .filter(t -> !"ROLE_SUPERADMIN".equals(t.getRole()))
                .toList();
        Map<Long, Tenant> byId = businesses.stream().collect(Collectors.toMap(Tenant::getId, Function.identity()));

        // ---- payments: one scan of the current and previous period ----
        Map<LocalDate, Acc> perDay = new TreeMap<>();
        for (LocalDate d = firstDay; !d.isAfter(today); d = d.plusDays(1)) perDay.put(d, new Acc());
        Acc period = new Acc(), previous = new Acc(), todayAcc = new Acc(), yesterdayAcc = new Acc();
        Map<String, Acc> networks = new HashMap<>();
        Map<Long, Acc> perTenant = new HashMap<>();

        for (Object[] row : transactions.platformAnalyticsRows(clock.startOf(previousFirstDay))) {
            Long tenantId = (Long) row[0];
            String status = (String) row[1];
            long amount = row[2] == null ? 0 : ((BigDecimal) row[2]).longValue();
            long fee = row[3] == null ? 0 : ((Number) row[3]).longValue();
            String network = row[4] == null ? "OTHER" : ((String) row[4]).toUpperCase(Locale.ROOT);
            OffsetDateTime at = (OffsetDateTime) row[5];
            if (at == null) continue;

            if (at.isBefore(periodStart)) {
                previous.add(status, amount, fee);
                continue;
            }
            period.add(status, amount, fee);
            Acc day = perDay.get(clock.dateOf(at));
            if (day != null) day.add(status, amount, fee);
            if (!at.isBefore(todayStart)) todayAcc.add(status, amount, fee);
            else if (!at.isBefore(yesterdayStart)) yesterdayAcc.add(status, amount, fee);
            if ("SUCCESSFUL".equals(status)) {
                networks.computeIfAbsent(network, k -> new Acc()).add(status, amount, fee);
                perTenant.computeIfAbsent(tenantId, k -> new Acc()).add(status, amount, fee);
            }
        }

        List<Day> daily = perDay.entrySet().stream()
                .map(e -> new Day(e.getKey().toString(), e.getValue().gross, e.getValue().sales,
                        e.getValue().failed, e.getValue().fee))
                .toList();

        List<NetworkShare> networkShares = networks.entrySet().stream()
                .map(e -> new NetworkShare(e.getKey(), e.getValue().gross, e.getValue().sales))
                .sorted(Comparator.comparingLong(NetworkShare::gross).reversed())
                .toList();

        Map<Long, OffsetDateTime> lastSale = new HashMap<>();
        for (Object[] row : transactions.lastSaleByTenant()) lastSale.put((Long) row[0], (OffsetDateTime) row[1]);

        List<TopTenant> top = perTenant.entrySet().stream()
                .filter(e -> byId.containsKey(e.getKey()))
                .sorted(Comparator.comparingLong((Map.Entry<Long, Acc> e) -> e.getValue().gross).reversed())
                .limit(8)
                .map(e -> {
                    Tenant t = byId.get(e.getKey());
                    return new TopTenant(t.getId(), t.getName(), t.isSchool(), e.getValue().gross,
                            e.getValue().sales, e.getValue().fee, lastSale.get(t.getId()));
                })
                .toList();

        // ---- tenants that have routers but stopped selling ----
        Map<Long, Integer> routerCounts = new HashMap<>();
        for (Router r : routers.findAll()) {
            if (r.getTenant() != null) routerCounts.merge(r.getTenant().getId(), 1, Integer::sum);
        }
        OffsetDateTime dormantCutoff = clock.now().minusDays(DORMANT_DAYS);
        List<DormantTenant> dormantAll = businesses.stream()
                .filter(t -> "ACTIVE".equals(t.getStatus()))
                .filter(t -> routerCounts.getOrDefault(t.getId(), 0) > 0)
                .filter(t -> t.getCreatedAt() == null || t.getCreatedAt().isBefore(dormantCutoff))
                .filter(t -> lastSale.get(t.getId()) == null || lastSale.get(t.getId()).isBefore(dormantCutoff))
                .map(t -> new DormantTenant(t.getId(), t.getName(), t.isSchool(), routerCounts.get(t.getId()),
                        lastSale.get(t.getId()), t.getCreatedAt()))
                .sorted(Comparator.comparing(DormantTenant::lastSaleAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();

        long withRouters = businesses.stream().filter(t -> routerCounts.getOrDefault(t.getId(), 0) > 0).count();
        TenantMix mix = new TenantMix(
                businesses.stream().filter(t -> !t.isSchool()).count(),
                businesses.stream().filter(Tenant::isSchool).count(),
                businesses.stream().filter(t -> "ACTIVE".equals(t.getStatus())).count(),
                businesses.stream().filter(t -> "SUSPENDED".equals(t.getStatus())).count(),
                businesses.stream().filter(t -> !"ACTIVE".equals(t.getStatus()) && !"SUSPENDED".equals(t.getStatus())).count(),
                withRouters,
                businesses.size() - withRouters);

        // ---- subscriptions that need a follow-up ----
        OffsetDateTime now = clock.now();
        OffsetDateTime soon = now.plusDays(5);
        List<SubscriptionFlag> flags = subscriptions.findAll().stream()
                .filter(s -> s.getTenant() != null && byId.containsKey(s.getTenant().getId()))
                .filter(s -> Subscription.STATUS_GRACE_PERIOD.equals(s.getStatus())
                        || Subscription.STATUS_SUSPENDED.equals(s.getStatus())
                        || (s.getExpiresAt() != null && s.getExpiresAt().isAfter(now) && s.getExpiresAt().isBefore(soon)))
                .sorted(Comparator.comparingInt((Subscription s) -> switch (s.getStatus()) {
                            case Subscription.STATUS_SUSPENDED -> 0;
                            case Subscription.STATUS_GRACE_PERIOD -> 1;
                            default -> 2;
                        })
                        .thenComparing(Subscription::getExpiresAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(8)
                .map(s -> new SubscriptionFlag(s.getTenant().getId(), s.getTenant().getName(), s.getStatus(),
                        s.getExpiresAt(), s.getGraceUntil()))
                .toList();

        // ---- latest account and security activity across the platform ----
        Map<Long, String> names = tenants.findAll().stream()
                .collect(Collectors.toMap(Tenant::getId, Tenant::getName, (a, b) -> a));
        List<Activity> activity = auditLogs.findTop100ByOrderByCreatedAtDesc().stream()
                .limit(12)
                .map((AuditLog a) -> new Activity(a.getId(), a.getAction(), a.getActorEmail(), a.getTenantId(),
                        a.getTenantId() == null ? null : names.get(a.getTenantId()), a.getDetails(),
                        a.getIpAddress(), a.getCreatedAt()))
                .toList();

        return new Overview(span, clock.zone().getId(), now, daily, period.totals(), previous.totals(),
                todayAcc.totals(), yesterdayAcc.totals(), networkShares, top,
                dormantAll.stream().limit(8).toList(), dormantAll.size(), mix, flags, activity);
    }
}
