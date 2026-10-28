package com.wifibilling.repository;

import com.wifibilling.model.Customer;
import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.Voucher;
import com.wifibilling.radius.RadAcct;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Queries behind the tenant dashboard aggregate. findTenantSessionsSince must
 * return exactly the rows the old load-everything + Java filter would have
 * used: same tenant (via voucher codes), session time = start, else stop.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:dashboard-queries;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DashboardQueriesTest {

    static final OffsetDateTime SINCE = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired EntityManager em;
    @Autowired RadAcctRepository accounting;
    @Autowired CustomerRepository customers;

    Tenant tenant(String email) {
        Tenant t = Tenant.builder().name(email).email(email).phone("256700000000").passwordHash("x").build();
        em.persist(t);
        return t;
    }

    void voucher(Tenant t, String code) {
        PackagePlan p = PackagePlan.builder().tenant(t).name("P-" + code).price(BigDecimal.TEN).rateLimit("1M/1M").sessionTimeout(3600).build();
        em.persist(p);
        em.persist(Voucher.builder().tenant(t).packagePlan(p).code(code).build());
    }

    void session(String id, String user, OffsetDateTime start, OffsetDateTime stop) {
        em.persist(RadAcct.builder().acctSessionId(id).acctUniqueId(id).username(user).nasIpAddress("10.0.0.1")
                .acctStartTime(start).acctStopTime(stop).acctInputOctets(1L).acctOutputOctets(1L).build());
    }

    @Test
    void sessionsSinceAreTenantScopedAndUseStartElseStop() {
        Tenant a = tenant("a@example.com"), b = tenant("b@example.com");
        voucher(a, "A1"); voucher(a, "A2"); voucher(b, "B1");
        session("in-start", "A1", SINCE.plusHours(1), null);
        session("at-boundary", "A1", SINCE, SINCE.plusHours(1));
        session("before", "A1", SINCE.minusHours(1), null);
        session("in-stop-only", "A2", null, SINCE.plusHours(1));
        session("before-stop-only", "A2", null, SINCE.minusHours(2));
        session("start-wins", "A2", SINCE.minusHours(1), SINCE.plusHours(1));
        session("no-times", "A2", null, null);
        session("other-tenant", "B1", SINCE.plusHours(1), null);
        session("unknown-user", "ZZ", SINCE.plusHours(1), null);
        em.flush();
        em.clear();

        List<String> ids = accounting.findTenantSessionsSince(a.getId(), SINCE).stream().map(RadAcct::getAcctSessionId).sorted().toList();

        assertThat(ids).containsExactly("at-boundary", "in-start", "in-stop-only");
    }

    @Test
    void customerCountsAreTenantScopedAndInclusiveOfTheBoundary() {
        Tenant a = tenant("a@example.com"), b = tenant("b@example.com");
        for (OffsetDateTime at : List.of(SINCE.minusDays(1), SINCE, SINCE.plusHours(1))) {
            em.persist(Customer.builder().tenant(a).name("c").createdAt(at).build());
        }
        em.persist(Customer.builder().tenant(b).name("other").createdAt(SINCE.plusHours(1)).build());
        em.flush();

        assertThat(customers.countByTenantId(a.getId())).isEqualTo(3);
        assertThat(customers.countByTenantIdAndCreatedAtGreaterThanEqual(a.getId(), SINCE)).isEqualTo(2);
    }
}
