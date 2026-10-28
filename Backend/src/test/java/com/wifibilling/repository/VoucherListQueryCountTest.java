package com.wifibilling.repository;

import com.wifibilling.model.Customer;
import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.Voucher;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Voucher lists (/api/vouchers, /api/users, dashboard stats) read each
 * voucher's lazy customer and package. Without batch fetching that is one
 * extra SELECT per distinct customer and plan; this pins it to a handful.
 * JPA slice on embedded H2 only: no schedulers, no external database.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        // application.properties pins the PostgreSQL dialect; use H2's for the embedded DB.
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        // Several entities have a column named "value", an H2 keyword.
        "spring.datasource.url=jdbc:h2:mem:voucher-query-count;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class VoucherListQueryCountTest {

    @Autowired EntityManager em;
    @Autowired VoucherRepository vouchers;

    @Test
    void listingVouchersWithCustomerAndPlanDoesNotQueryPerRow() {
        Tenant tenant = Tenant.builder().name("T").email("t@example.com").phone("256700000000").passwordHash("x").build();
        em.persist(tenant);
        List<PackagePlan> plans = java.util.stream.IntStream.range(0, 3).mapToObj(i -> {
            PackagePlan p = PackagePlan.builder().tenant(tenant).name("Plan " + i).price(BigDecimal.valueOf(500L * (i + 1)))
                    .rateLimit("5M/5M").sessionTimeout(86400).build();
            em.persist(p);
            return p;
        }).toList();
        List<Customer> customers = java.util.stream.IntStream.range(0, 60).mapToObj(i -> {
            Customer c = Customer.builder().tenant(tenant).name("Customer " + i).build();
            em.persist(c);
            return c;
        }).toList();
        for (int i = 0; i < 120; i++) {
            em.persist(Voucher.builder().tenant(tenant).packagePlan(plans.get(i % 3)).customer(customers.get(i % 60))
                    .code("V" + String.format("%05d", i)).build());
        }
        em.flush();
        em.clear();

        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        List<Voucher> list = vouchers.findAllByTenantIdOrderByCreatedAtDesc(tenant.getId());
        // A plain loop: Stream.map(..).count() may skip the map on sized lists.
        // Batching must not change what each voucher resolves to.
        assertThat(list).hasSize(120);
        for (Voucher v : list) {
            int i = Integer.parseInt(v.getCode().substring(1));
            assertThat(v.getCustomer().getName()).isEqualTo("Customer " + (i % 60));
            assertThat(v.getPackagePlan().getName()).isEqualTo("Plan " + (i % 3));
            assertThat(v.getTenant().getId()).isEqualTo(tenant.getId());
        }

        long statements = stats.getPrepareStatementCount();
        System.out.println("VoucherListQueryCountTest: statements for 120 vouchers / 60 customers / 3 plans = " + statements);
        // 1 list query + 1 batched customer load + 1 batched plan load.
        assertThat(statements).isLessThanOrEqualTo(3);
    }
}
