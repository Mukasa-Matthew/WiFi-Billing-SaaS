package com.wifibilling.service;

import com.wifibilling.dto.FinancialAnalysisDto;
import com.wifibilling.model.*;
import com.wifibilling.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Financial analysis money rules, worked by hand. "Now" is Wednesday 28 Oct 2026,
 * 15:00 in Kampala, so "this month" is 1–28 Oct and its comparison is 1–28 Sep (to 15:00).
 */
class FinancialAnalysisServiceTest {

    static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    static final long TENANT = 7L;

    TransactionRepository transactions = mock(TransactionRepository.class);
    VoucherRepository vouchers = mock(VoucherRepository.class);
    RouterRepository routers = mock(RouterRepository.class);
    PackagePlanRepository plans = mock(PackagePlanRepository.class);
    CustomerRepository customers = mock(CustomerRepository.class);
    TenantRepository tenants = mock(TenantRepository.class);
    WithdrawalRequestRepository withdrawals = mock(WithdrawalRequestRepository.class);
    SubscriptionPaymentRepository subscriptions = mock(SubscriptionPaymentRepository.class);
    WalletAccountRepository wallets = mock(WalletAccountRepository.class);
    ReportingClock clock = new ReportingClock(ZoneId.of("Africa/Kampala"), Clock.fixed(NOW, ZoneOffset.UTC));
    FinancialAnalysisService service;

    Tenant tenant = Tenant.builder().id(TENANT).name("Biz").monthlySalesTarget(10_000L).build();
    Router r1 = Router.builder().id(11L).name("Main gate").status("online").build();
    Router r2 = Router.builder().id(12L).name("Library").status("online").build();
    PackagePlan p1 = PackagePlan.builder().id(1L).name("3HRS").price(BigDecimal.valueOf(1000)).build();
    PackagePlan p2 = PackagePlan.builder().id(2L).name("Daily").price(BigDecimal.valueOf(2000)).build();
    PackagePlan p3 = PackagePlan.builder().id(3L).name("Unsold").price(BigDecimal.valueOf(9000)).build();

    static OffsetDateTime at(String iso) { return OffsetDateTime.parse(iso); }

    Transaction tx(Router r, PackagePlan p, String status, long amount, String phone, String network, String mode, String when) {
        return Transaction.builder().tenant(tenant).router(r).packagePlan(p).status(status).amount(BigDecimal.valueOf(amount))
                .phone(phone).network(network).gatewayMode(mode).createdAt(at(when)).build();
    }

    @BeforeEach
    void setUp() {
        service = new FinancialAnalysisService(transactions, vouchers, routers, plans, customers, tenants,
                withdrawals, subscriptions, wallets, clock);
        Transaction a = tx(r1, p1, "SUCCESSFUL", 1000, "256771000001", "MTN", "PLATFORM", "2026-10-20T09:00:00Z");
        a.setGatewayFee(30L); a.setPlatformFee(20L); a.setNetAmount(970L);
        List<Transaction> all = List.of(
                a,
                // Mobile money recorded by hand: never walk-in (the owner's rule).
                tx(r2, p2, "SUCCESSFUL", 2000, "0701000002", "MOMO", null, "2026-10-21T09:00:00Z"),
                // Cash at the counter: walk-in, no customer.
                tx(r2, p1, "SUCCESSFUL", 500, "MANUAL", "CASH", null, "2026-10-22T09:00:00Z"),
                // Comparison period (September)
                tx(r1, p1, "SUCCESSFUL", 3000, "256771000001", "MTN", "PLATFORM", "2026-09-10T09:00:00Z"),
                tx(r1, p2, "SUCCESSFUL", 4000, "256779999999", null, "PLATFORM", "2026-09-12T09:00:00Z"),
                // Lost money
                tx(r1, p1, "FAILED", 1500, "256772222222", "MTN", "PLATFORM", "2026-10-23T09:00:00Z"),
                tx(r1, p1, "PENDING", 700, "256703333333", null, "PLATFORM", "2026-10-28T10:00:00Z"),
                tx(r1, p1, "PENDING", 800, "256704444444", null, "PLATFORM", "2026-10-28T11:50:00Z"));
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        when(transactions.findAllByTenantIdOrderByCreatedAtDesc(TENANT)).thenReturn(all);
        when(plans.findAllByTenantIdOrderByPriceAsc(TENANT)).thenReturn(List.of(p1, p2, p3));
        when(routers.findAllByTenantId(TENANT)).thenReturn(List.of(r1, r2));
        when(customers.findAllByTenantIdOrderByCreatedAtDesc(TENANT)).thenReturn(List.of(
                Customer.builder().name("Jane").phone("0701000002").build()));
        when(withdrawals.findAllByTenantIdOrderByCreatedAtDesc(TENANT)).thenReturn(List.of(
                WithdrawalRequest.builder().amount(1000L).fee(30L).status(WithdrawalRequest.COMPLETED)
                        .createdAt(at("2026-10-25T09:00:00Z")).completedAt(at("2026-10-25T09:05:00Z")).build(),
                WithdrawalRequest.builder().amount(9000L).fee(300L).status(WithdrawalRequest.FAILED)
                        .createdAt(at("2026-10-26T09:00:00Z")).build()));
        when(subscriptions.findAllByTenantIdAndPaidAtGreaterThanEqualAndPaidAtLessThan(eq(TENANT), any(), any()))
                .thenReturn(List.of(SubscriptionPayment.builder().amount(50_000L).build()));
        when(wallets.findByTenantId(TENANT)).thenReturn(Optional.of(WalletAccount.builder().availableBalance(5000L).build()));
    }

    FinancialAnalysisDto month() {
        return service.analyse(TENANT, "month", null, null, FinancialAnalysisService.Filters.none());
    }

    @Test
    void totalsFollowTheDashboardMoneyRules() {
        var d = month();
        assertThat(d.totals().gross()).isEqualTo(3500);
        assertThat(d.totals().gatewayFees()).isEqualTo(30);
        assertThat(d.totals().platformFees()).isEqualTo(20);
        assertThat(d.totals().net()).isEqualTo(950 + 2000 + 500);
        assertThat(d.totals().sales()).isEqualTo(3);
        assertThat(d.totals().avgSale()).isEqualTo(1167);
        assertThat(d.totals().customers()).isEqualTo(2);
        assertThat(d.totals().perCustomer()).isEqualTo(1750);
        assertThat(d.previous().gross()).isEqualTo(7000);
        assertThat(d.previous().sales()).isEqualTo(2);
    }

    @Test
    void handRecordedMobileMoneyIsNeverWalkIn() {
        var ch = month().byChannel();
        assertThat(ch).extracting(FinancialAnalysisDto.Slice::key).containsExactly("MTN", "AIRTEL", "OTHER", "WALK_IN");
        assertThat(ch.get(0).gross()).isEqualTo(1000);
        assertThat(ch.get(0).previousGross()).isEqualTo(7000);
        assertThat(ch.get(2).gross()).isEqualTo(2000);
        assertThat(ch.get(3).gross()).isEqualTo(500);
    }

    @Test
    void comparisonIsTheSameStretchOfLastMonth() {
        var p = month().period();
        assertThat(p.from()).isEqualTo("2026-10-01");
        assertThat(p.to()).isEqualTo("2026-10-28");
        assertThat(p.previousFrom()).isEqualTo("2026-09-01");
        assertThat(p.previousTo()).isEqualTo("2026-09-28");
        assertThat(p.bucket()).isEqualTo("day");
        assertThat(p.partial()).isTrue();
    }

    @Test
    void packagesAndRoutersIncludeOnesThatSoldNothing() {
        var d = month();
        assertThat(d.byPackage()).extracting(FinancialAnalysisDto.Ranked::name).containsExactly("Daily", "3HRS", "Unsold");
        assertThat(d.byPackage().get(0).previousGross()).isEqualTo(4000);
        assertThat(d.byPackage().get(2).gross()).isZero();
        assertThat(d.byRouter()).extracting(FinancialAnalysisDto.Ranked::name).containsExactly("Library", "Main gate");
        assertThat(d.byRouter().get(0).gross()).isEqualTo(2500);
    }

    @Test
    void customersAreSplitIntoNewReturningAndLapsed() {
        var c = month().customers();
        assertThat(c.total()).isEqualTo(2);
        assertThat(c.newCustomers()).isEqualTo(1);
        assertThat(c.newGross()).isEqualTo(2000);
        assertThat(c.returning()).isEqualTo(1);
        assertThat(c.returningGross()).isEqualTo(1000);
        assertThat(c.lapsed()).isEqualTo(1);
        assertThat(c.lapsedPreviousGross()).isEqualTo(4000);
        assertThat(c.top()).hasSize(2);
        assertThat(c.top().get(0).phone()).isEqualTo("0701000002");
        assertThat(c.top().get(0).name()).isEqualTo("Jane");
    }

    @Test
    void lostMoneyCountsFailuresAndOldPendingOnly() {
        var l = month().lost();
        assertThat(l.failed()).isEqualTo(1);
        assertThat(l.failedValue()).isEqualTo(1500);
        assertThat(l.abandoned()).isEqualTo(1);      // the 10-minute-old payment may still be approved
        assertThat(l.abandonedValue()).isEqualTo(700);
        assertThat(l.attempts()).isEqualTo(3);       // only online payments; hand-recorded sales can't fail
        assertThat(l.successRate()).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(l.byNetwork().get(0).failed()).isEqualTo(1);    // MTN
        assertThat(l.byNetwork().get(1).abandoned()).isEqualTo(1); // Airtel (070 prefix)
    }

    @Test
    void heatmapUsesKampalaTime() {
        // 20 Oct 09:00 UTC is Tuesday 12:00 in Kampala.
        var d = month();
        assertThat(d.heatmapGross()[1][12]).isEqualTo(1000);
        assertThat(d.heatmapSales()[1][12]).isEqualTo(1);
    }

    @Test
    void filtersNarrowEveryTransactionFigure() {
        var d = service.analyse(TENANT, "month", null, null, new FinancialAnalysisService.Filters(11L, null, null, null));
        assertThat(d.totals().gross()).isEqualTo(1000);
        assertThat(d.lost().failed()).isEqualTo(1);
        var walkIn = service.analyse(TENANT, "month", null, null, new FinancialAnalysisService.Filters(null, null, "WALK_IN", null));
        assertThat(walkIn.totals().gross()).isEqualTo(500);
        // Money flow is always the whole business.
        assertThat(d.moneyFlow().gross()).isEqualTo(3500);
    }

    @Test
    void moneyFlowCountsOnlyCompletedWithdrawals() {
        var m = month().moneyFlow();
        assertThat(m.withdrawn()).isEqualTo(1000);
        assertThat(m.withdrawalFees()).isEqualTo(30);
        assertThat(m.withdrawals()).isEqualTo(1);
        assertThat(m.subscriptionPaid()).isEqualTo(50_000);
        assertThat(m.walletAvailable()).isEqualTo(5000);
    }

    @Test
    void targetProjectsTheMonthAtTheCurrentPace() {
        var t = month().target();
        assertThat(t.monthlyTarget()).isEqualTo(10_000L);
        assertThat(t.monthToDate()).isEqualTo(3500);
        assertThat(t.lastMonthTotal()).isEqualTo(7000);
        assertThat(t.lastMonthSameDay()).isEqualTo(7000);
        // 1–28 Oct 15:00 is 663 of October's 744 hours.
        assertThat(t.projected()).isEqualTo(Math.round(3500 * 744.0 / 663));
        assertThat(t.dayOfMonth()).isEqualTo(28);
        assertThat(t.daysInMonth()).isEqualTo(31);
    }

    @Test
    void todayIsHourlyAndYearIsMonthly() {
        assertThat(service.analyse(TENANT, "today", null, null, null).period().bucket()).isEqualTo("hour");
        var y = service.analyse(TENANT, "year", null, null, null);
        assertThat(y.period().bucket()).isEqualTo("month");
        assertThat(y.series()).hasSize(10);
    }

    @Test
    void customRangeIsValidated() {
        assertThatThrownBy(() -> service.analyse(TENANT, "custom", LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 1), null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.analyse(TENANT, "custom", LocalDate.of(2023, 1, 1), LocalDate.of(2026, 1, 1), null))
                .isInstanceOf(ResponseStatusException.class);
        var d = service.analyse(TENANT, "custom", LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 21), null);
        assertThat(d.totals().gross()).isEqualTo(3000);
        assertThat(d.period().previousFrom()).isEqualTo("2026-10-18");
    }

    @Test
    void targetIsValidated() {
        assertThatThrownBy(() -> service.setTarget(TENANT, 0L)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.setTarget(TENANT, null)).isNull();
        assertThat(service.setTarget(TENANT, 2_000_000L)).isEqualTo(2_000_000L);
        assertThat(tenant.getMonthlySalesTarget()).isEqualTo(2_000_000L);
    }
}
