package com.wifibilling.service;

import com.wifibilling.dto.TenantDashboardStatsDto;
import com.wifibilling.model.Transaction;
import com.wifibilling.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Reporting days follow Africa/Kampala (UTC+3): local midnight is 21:00 UTC
 * on the previous calendar day. Stored timestamps remain UTC instants.
 */
@ExtendWith(MockitoExtension.class)
class ReportingTimezoneTest {

    private static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");
    // 09:00 Kampala on Sunday 25 Oct 2026.
    private static final Instant NOW = Instant.parse("2026-10-25T06:00:00Z");
    private final ReportingClock clock = new ReportingClock(KAMPALA, Clock.fixed(NOW, ZoneOffset.UTC));

    @Mock private TransactionRepository transactionRepository;
    @Mock private VoucherRepository voucherRepository;
    @Mock private RouterRepository routerRepository;
    @Mock private SessionService sessionService;
    @Mock private RadAcctRepository radAcctRepository;
    @Mock private PackagePlanRepository planRepository;
    @Mock private CustomerRepository customerRepository;

    private static OffsetDateTime utc(String iso) { return OffsetDateTime.parse(iso); }

    @Test
    void localDayBoundaryIsKampalaMidnight() {
        assertEquals(LocalDate.of(2026, 10, 25), clock.today());
        assertEquals(utc("2026-10-24T21:00:00Z"), clock.startOf(LocalDate.of(2026, 10, 25)));
        assertEquals(ZoneOffset.UTC, clock.startOf(LocalDate.of(2026, 10, 25)).getOffset(), "boundaries stay UTC instants");
    }

    @Test
    void instantsAroundMidnightLandOnTheRightLocalDay() {
        assertEquals(LocalDate.of(2026, 10, 24), clock.dateOf(utc("2026-10-24T20:59:59Z")), "23:59:59 Kampala");
        assertEquals(LocalDate.of(2026, 10, 25), clock.dateOf(utc("2026-10-24T21:00:00Z")), "00:00:00 Kampala");
        assertEquals(LocalDate.of(2026, 10, 25), clock.dateOf(utc("2026-10-24T22:30:00Z")), "01:30 Kampala, still 24th in UTC");
        assertEquals(LocalDate.of(2026, 10, 25), clock.dateOf(utc("2026-10-25T03:30:00+03:00")), "offset-carrying input converts too");
    }

    @Test
    void monthStartsAtLocalMidnightOnTheFirst() {
        assertEquals(utc("2026-09-30T21:00:00Z"), clock.startOf(LocalDate.of(2026, 10, 1)));
        assertEquals(LocalDate.of(2026, 9, 30), clock.dateOf(utc("2026-09-30T20:59:00Z")));
    }

    private Transaction sale(String createdAtUtc, int amount) {
        return Transaction.builder().status("SUCCESSFUL").amount(BigDecimal.valueOf(amount)).createdAt(utc(createdAtUtc)).build();
    }

    private TenantDashboardStatsDto stats(List<Transaction> txs, String range) {
        when(transactionRepository.findAllByTenantIdOrderByCreatedAtDesc(1L)).thenReturn(txs);
        TenantDashboardService service = new TenantDashboardService(transactionRepository, voucherRepository, routerRepository,
                sessionService, radAcctRepository, planRepository, customerRepository, clock);
        return service.getStats(1L, "ROLE_ADMIN", range);
    }

    @Test
    void todaysRevenueUsesKampalaMidnightNotUtcMidnight() {
        TenantDashboardStatsDto s = stats(List.of(
                sale("2026-10-24T20:59:00Z", 1000),   // 23:59 on the 24th, Kampala: yesterday
                sale("2026-10-24T21:01:00Z", 2000),   // 00:01 on the 25th, Kampala: today (UTC would say yesterday)
                sale("2026-10-25T05:00:00Z", 5000)),  // 08:00 today
                "7d");
        assertEquals(0, BigDecimal.valueOf(7000).compareTo(s.revenueToday()));

        List<Map<String, Object>> week = s.weeklySales();
        assertEquals(7, week.size());
        Map<String, Object> today = week.get(6), yesterday = week.get(5);
        assertEquals("2026-10-25", today.get("date"));
        assertEquals(0, BigDecimal.valueOf(7000).compareTo((BigDecimal) today.get("revenue")));
        assertEquals(2, today.get("transactions"));
        assertEquals("2026-10-24", yesterday.get("date"));
        assertEquals(0, BigDecimal.valueOf(1000).compareTo((BigDecimal) yesterday.get("revenue")));
    }

    @Test
    void monthRevenueStartsAtLocalFirstOfMonth() {
        TenantDashboardStatsDto s = stats(List.of(
                sale("2026-09-30T20:30:00Z", 4000),   // 23:30 on 30 Sep, Kampala: last month
                sale("2026-09-30T21:30:00Z", 3000)),  // 00:30 on 1 Oct, Kampala: this month
                "month");
        assertEquals(0, BigDecimal.valueOf(3000).compareTo(s.revenueMonth()));
    }

    @Test
    void lastWeekHasSevenBucketsEndingSunday() {
        TenantDashboardStatsDto s = stats(List.of(sale("2026-10-18T20:30:00Z", 2500)), "lastweek"); // 23:30 Sun 18 Oct Kampala
        List<Map<String, Object>> series = s.revenueSeries();
        assertEquals(7, series.size(), "exclusive window end must not add next Monday");
        assertEquals("2026-10-12", series.get(0).get("date"));
        assertEquals("2026-10-18", series.get(6).get("date"));
        assertEquals(0, BigDecimal.valueOf(2500).compareTo(new BigDecimal(String.valueOf(series.get(6).get("revenue")))));
    }
}
