package com.wifibilling.service;

import com.wifibilling.dto.TenantDashboardStatsDto;
import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Router;
import com.wifibilling.model.Transaction;
import com.wifibilling.repository.CustomerRepository;
import com.wifibilling.repository.PackagePlanRepository;
import com.wifibilling.repository.RadAcctRepository;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.repository.VoucherRepository;
import com.wifibilling.model.Voucher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dashboard aggregate: package performance (now one pass over transactions),
 * customer totals (now count queries) and the accounting window passed to
 * the database. Expected values follow the previous per-plan loop's rules.
 */
class TenantDashboardServiceTest {

    static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");
    static final long TENANT = 7L;

    TransactionRepository transactions = mock(TransactionRepository.class);
    VoucherRepository vouchers = mock(VoucherRepository.class);
    RouterRepository routers = mock(RouterRepository.class);
    SessionService sessions = mock(SessionService.class);
    RadAcctRepository accounting = mock(RadAcctRepository.class);
    PackagePlanRepository plans = mock(PackagePlanRepository.class);
    CustomerRepository customers = mock(CustomerRepository.class);
    ReportingClock clock = new ReportingClock(KAMPALA, Clock.fixed(NOW, ZoneOffset.UTC));
    TenantDashboardService service;

    PackagePlan plan1 = PackagePlan.builder().id(1L).name("Daily").price(BigDecimal.valueOf(1000)).build();
    PackagePlan plan2 = PackagePlan.builder().id(2L).name("Weekly").price(BigDecimal.valueOf(5000)).build();
    PackagePlan plan3 = PackagePlan.builder().id(3L).name("Unsold").price(BigDecimal.valueOf(9000)).build();
    Router router = Router.builder().id(11L).name("Main").status("online").build();
    OffsetDateTime thisMonth = OffsetDateTime.parse("2026-10-20T09:00:00Z");
    OffsetDateTime lastYear = OffsetDateTime.parse("2025-06-01T09:00:00Z");

    Transaction tx(PackagePlan p, String status, long amount, OffsetDateTime at) {
        return Transaction.builder().packagePlan(p).router(router).status(status).amount(BigDecimal.valueOf(amount)).createdAt(at).build();
    }

    @BeforeEach
    void setUp() {
        service = new TenantDashboardService(transactions, vouchers, routers, sessions, accounting, plans, customers, clock);
        when(transactions.findAllByTenantIdOrderByCreatedAtDesc(TENANT)).thenReturn(List.of(
                tx(plan1, "SUCCESSFUL", 1000, thisMonth),
                tx(plan1, "SUCCESSFUL", 2000, thisMonth.plusDays(1)),
                tx(plan1, "SUCCESSFUL", 500, lastYear),
                tx(plan1, "FAILED", 999, thisMonth),
                tx(plan2, "SUCCESSFUL", 3000, thisMonth),
                tx(null, "SUCCESSFUL", 700, thisMonth)));
        when(routers.findAllByTenantId(TENANT)).thenReturn(List.of(router));
        when(vouchers.findAllByTenantIdOrderByCreatedAtDesc(TENANT)).thenReturn(List.of(
                Voucher.builder().code("V1").packagePlan(plan1).status("active").createdAt(thisMonth).build()));
        when(sessions.getActiveSessions(eq(TENANT), anyString())).thenReturn(List.of());
        when(accounting.findTenantSessionsSince(anyLong(), any())).thenReturn(List.of());
        when(plans.findAllByTenantIdOrderByPriceAsc(TENANT)).thenReturn(List.of(plan1, plan2, plan3));
        when(customers.countByTenantId(TENANT)).thenReturn(42L);
        when(customers.countByTenantIdAndCreatedAtGreaterThanEqual(eq(TENANT), any())).thenReturn(5L);
    }

    @Test
    void packagePerformanceMatchesPerPlanRules() {
        TenantDashboardStatsDto s = service.getStats(TENANT, "ROLE_ADMIN", "7d");

        Map<Long, Map<String, Object>> byId = new java.util.HashMap<>();
        s.packagePerformance().forEach(m -> byId.put((Long) m.get("id"), m));
        // Daily: 3 successful sold (one last year); this month 1000 + 2000 over 2 sales.
        assertThat(byId.get(1L)).containsEntry("totalSold", 3L).containsEntry("monthlyRevenue", BigDecimal.valueOf(3000)).containsEntry("arpu", BigDecimal.valueOf(1500));
        assertThat(byId.get(2L)).containsEntry("totalSold", 1L).containsEntry("monthlyRevenue", BigDecimal.valueOf(3000)).containsEntry("arpu", BigDecimal.valueOf(3000));
        assertThat(byId.get(3L)).containsEntry("totalSold", 0L).containsEntry("monthlyRevenue", BigDecimal.ZERO).containsEntry("arpu", BigDecimal.ZERO);
        assertThat(s.packagePerformance()).extracting(m -> m.get("id")).containsExactly(1L, 2L, 3L);
    }

    @Test
    void customerTotalsComeFromCountQueriesFromMondayOn() {
        TenantDashboardStatsDto s = service.getStats(TENANT, "ROLE_ADMIN", "7d");

        assertThat(s.totalCustomers()).isEqualTo(42);
        assertThat(s.newCustomersThisWeek()).isEqualTo(5);
        // Monday 2026-10-26 00:00 in Kampala.
        verify(customers).countByTenantIdAndCreatedAtGreaterThanEqual(TENANT, OffsetDateTime.parse("2026-10-25T21:00:00Z"));
    }

    @Test
    void accountingWindowCoversBothTheRangeAndTheMonth() {
        OffsetDateTime monthStart = OffsetDateTime.parse("2026-09-30T21:00:00Z"); // 1 Oct 00:00 Kampala
        OffsetDateTime yearStart = OffsetDateTime.parse("2025-12-31T21:00:00Z");  // 1 Jan 00:00 Kampala
        ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);

        service.getStats(TENANT, "ROLE_ADMIN", "7d");     // window starts after the month does
        service.getStats(TENANT, "ROLE_ADMIN", "year");   // window starts before the month does
        verify(accounting, org.mockito.Mockito.times(2)).findTenantSessionsSince(eq(TENANT), since.capture());

        assertThat(since.getAllValues().get(0)).isEqualTo(monthStart.minusDays(1));
        assertThat(since.getAllValues().get(1)).isEqualTo(yearStart.minusDays(1));
    }
}
