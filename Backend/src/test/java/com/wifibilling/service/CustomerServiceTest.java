package com.wifibilling.service;

import com.wifibilling.model.Customer;
import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.Voucher;
import com.wifibilling.repository.CustomerRepository;
import com.wifibilling.repository.RadAcctRepository;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.repository.VoucherRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Buyers become customers by phone number; the directory reports each customer's current voucher. */
class CustomerServiceTest {

    static final long TENANT = 7L;

    CustomerRepository customers = mock(CustomerRepository.class);
    TenantRepository tenants = mock(TenantRepository.class);
    VoucherRepository vouchers = mock(VoucherRepository.class);
    TransactionRepository transactions = mock(TransactionRepository.class);
    RadAcctRepository accounting = mock(RadAcctRepository.class);
    CustomerService service = new CustomerService(customers, tenants, vouchers, transactions,
            mock(JdbcTemplate.class), mock(PlatformTransactionManager.class), mock(LeaderLockService.class), accounting);

    PackagePlan daily = PackagePlan.builder().id(1L).name("Daily").sessionTimeout(86400).build();

    @Test
    void localPhoneAcceptsUgandanFormatsOnly() {
        assertThat(CustomerService.localPhone("+256772270145")).isEqualTo("0772270145");
        assertThat(CustomerService.localPhone("256 772 270 145")).isEqualTo("0772270145");
        assertThat(CustomerService.localPhone("0772270145")).isEqualTo("0772270145");
        assertThat(CustomerService.localPhone("772270145")).isEqualTo("0772270145");
        assertThat(CustomerService.localPhone("MANUAL")).isNull();
        assertThat(CustomerService.localPhone(null)).isNull();
        assertThat(CustomerService.phoneVariants("0772270145")).contains("+256772270145", "256772270145", "+256 772 270 145", "0772 270 145");
    }

    @Test
    void newBuyerIsCreatedAndVoucherLinked() {
        OffsetDateTime at = OffsetDateTime.parse("2026-10-20T10:00:00Z");
        when(customers.findFirstByTenantIdAndPhoneInOrderByIdAsc(eq(TENANT), anyCollection())).thenReturn(Optional.empty());
        when(tenants.getReferenceById(TENANT)).thenReturn(Tenant.builder().id(TENANT).build());
        when(customers.save(any())).thenAnswer(i -> i.getArgument(0));
        Voucher v = Voucher.builder().code("ABC").packagePlan(daily).build();
        when(vouchers.findByCodeAndTenantId("ABC", TENANT)).thenReturn(Optional.of(v));

        Customer c = service.recordBuyer(TENANT, "+256772270145", "ABC", at).orElseThrow();

        assertThat(c.getPhone()).isEqualTo("0772270145");
        assertThat(c.getCreatedAt()).isEqualTo(at);
        assertThat(v.getCustomer()).isSameAs(c);
        verify(vouchers).save(v);
    }

    @Test
    void returningBuyerIsMatchedAndExistingLinkKept() {
        Customer existing = Customer.builder().id(3L).name("Ian").phone("+256 772 270 145").build();
        Customer other = Customer.builder().id(9L).name("Someone").build();
        when(customers.findFirstByTenantIdAndPhoneInOrderByIdAsc(eq(TENANT), anyCollection())).thenReturn(Optional.of(existing));
        Voucher assigned = Voucher.builder().code("XYZ").customer(other).packagePlan(daily).build();
        when(vouchers.findByCodeAndTenantId("XYZ", TENANT)).thenReturn(Optional.of(assigned));

        assertThat(service.recordBuyer(TENANT, "0772270145", "XYZ", null)).contains(existing);
        verify(customers, never()).save(any());
        assertThat(assigned.getCustomer()).isSameAs(other);
        assertThat(service.recordBuyer(TENANT, "MANUAL", "XYZ", null)).isEmpty();
    }

    @Test
    void listDescribesCurrentVoucherUsageAndSpend() {
        OffsetDateTime now = OffsetDateTime.now();
        Customer ian = Customer.builder().id(5L).name("Ian").phone("0772270145").createdAt(now.minusDays(9)).build();
        Customer amy = Customer.builder().id(2L).name("Amy").createdAt(now.minusDays(20)).build();
        Customer zed = Customer.builder().id(8L).name("Zed").createdAt(now.minusDays(1)).build();
        Customer new1 = Customer.builder().id(11L).name("New").createdAt(now).build();
        when(customers.findAllByTenantIdOrderByCreatedAtDesc(TENANT)).thenReturn(List.of(new1, zed, ian, amy));
        // Ian: a newer voucher waiting to be used and an older one in use -> the one in use wins.
        Voucher waiting = Voucher.builder().code("W1").status("unused").customer(ian).packagePlan(daily).build();
        Voucher inUse = Voucher.builder().code("U1").status("active").customer(ian).packagePlan(daily).firstLogin(now.minusHours(2)).build();
        Voucher amyOld = Voucher.builder().code("A1").status("used").customer(amy).packagePlan(daily).firstLogin(now.minusDays(5)).build();
        Voucher zedRevoked = Voucher.builder().code("Z1").status("revoked").customer(zed).packagePlan(daily).build();
        when(vouchers.findAllByTenantIdAndCustomerIsNotNullOrderByCreatedAtDesc(TENANT)).thenReturn(List.of(zedRevoked, waiting, inUse, amyOld));
        when(accounting.customerVoucherUsage(eq(TENANT), any())).thenReturn(List.<Object[]>of(
                new Object[]{"U1", 3000L, now.minusMinutes(1), 1L, now.minusHours(2)},
                new Object[]{"A1", 500L, now.minusDays(4), 0L, now.minusDays(5)}));
        when(transactions.spendByCustomer(TENANT)).thenReturn(List.<Object[]>of(new Object[]{5L, new BigDecimal("2000")}));

        var rows = service.list(TENANT);

        var r = rows.stream().filter(x -> x.id() == 5L).findFirst().orElseThrow();
        assertThat(r.status()).isEqualTo("active");
        assertThat(r.voucher()).isEqualTo("U1");
        assertThat(r.sessionEnd()).isEqualTo(inUse.getFirstLogin().plusSeconds(86400));
        assertThat(r.online()).isTrue();
        assertThat(r.dataBytes()).isEqualTo(3000);
        assertThat(r.purchases()).isEqualTo(2);
        assertThat(r.totalSpent()).isEqualByComparingTo("2000");
        assertThat(r.number()).isEqualTo(2); // ids 2, 5, 8, 11 -> Ian is the second recorded

        assertThat(rows.stream().filter(x -> x.id() == 2L).findFirst().orElseThrow().status()).isEqualTo("expired");
        assertThat(rows.stream().filter(x -> x.id() == 8L).findFirst().orElseThrow().status()).isEqualTo("suspended");
        var fresh = rows.stream().filter(x -> x.id() == 11L).findFirst().orElseThrow();
        assertThat(fresh.status()).isEqualTo("new");
        assertThat(fresh.totalSpent()).isEqualByComparingTo("0");
    }

    @Test
    void waitingVoucherMakesCustomerReady() {
        Customer c = Customer.builder().id(1L).name("A").build();
        when(customers.findAllByTenantIdOrderByCreatedAtDesc(TENANT)).thenReturn(List.of(c));
        when(vouchers.findAllByTenantIdAndCustomerIsNotNullOrderByCreatedAtDesc(TENANT)).thenReturn(List.of(
                Voucher.builder().code("N").status("unused").customer(c).packagePlan(daily).build()));
        when(accounting.customerVoucherUsage(eq(TENANT), any())).thenReturn(List.of());
        when(transactions.spendByCustomer(TENANT)).thenReturn(List.of());
        assertThat(service.list(TENANT).get(0).status()).isEqualTo("ready");
    }

    @Test
    void historyListsVouchersWithUsageAndPaymentsForOwnCustomerOnly() {
        OffsetDateTime now = OffsetDateTime.now();
        Customer c = Customer.builder().id(4L).name("Ian").build();
        when(customers.findByIdAndTenantId(4L, TENANT)).thenReturn(Optional.of(c));
        when(customers.findByIdAndTenantId(99L, TENANT)).thenReturn(Optional.empty());
        Voucher v = Voucher.builder().code("U1").status("active").customer(c).packagePlan(daily).firstLogin(now.minusHours(1)).createdAt(now.minusHours(2)).build();
        when(vouchers.findAllByTenantIdAndCustomerIdOrderByCreatedAtDesc(TENANT, 4L)).thenReturn(List.of(v));
        when(accounting.customerVoucherUsage(eq(TENANT), any())).thenReturn(List.<Object[]>of(
                new Object[]{"U1", 700L, now, 1L, now.minusHours(1)},
                new Object[]{"OTHER", 9L, now, 0L, now}));
        when(transactions.findAllByTenantIdAndVoucherCodeInAndStatusOrderByCreatedAtDesc(eq(TENANT), anyCollection(), eq("SUCCESSFUL")))
                .thenReturn(List.of(com.wifibilling.model.Transaction.builder().reference("PAY-1").amount(new BigDecimal("1000"))
                        .network("MTN").voucherCode("U1").createdAt(now.minusHours(2)).build()));

        var h = service.history(TENANT, 4L).orElseThrow();

        assertThat(h.vouchers()).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo("active");
            assertThat(e.dataBytes()).isEqualTo(700);
            assertThat(e.online()).isTrue();
            assertThat(e.sessionEnd()).isEqualTo(v.getFirstLogin().plusSeconds(86400));
        });
        assertThat(h.payments()).extracting(CustomerService.PaymentEntry::reference).containsExactly("PAY-1");
        assertThat(service.history(TENANT, 99L)).isEmpty();
    }
}
