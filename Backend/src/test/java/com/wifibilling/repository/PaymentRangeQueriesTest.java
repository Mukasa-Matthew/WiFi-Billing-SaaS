package com.wifibilling.repository;

import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Router;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.Transaction;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Payments page's date-range queries: tenant scoped, [from, to), status groups. */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:payment-range;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentRangeQueriesTest {

    static final OffsetDateTime FROM = OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    static final OffsetDateTime TO = FROM.plusDays(7);

    @Autowired EntityManager em;
    @Autowired TransactionRepository transactions;
    Tenant a, b;
    int seq;

    @BeforeEach
    void setUp() {
        a = tenant("a@example.com");
        b = tenant("b@example.com");
    }

    Tenant tenant(String email) {
        Tenant t = Tenant.builder().name(email).email(email).phone("256700000000").passwordHash("x").build();
        em.persist(t);
        return t;
    }

    void tx(Tenant t, String status, OffsetDateTime at) {
        PackagePlan p = PackagePlan.builder().tenant(t).name("P" + seq).price(BigDecimal.TEN).rateLimit("1M/1M").sessionTimeout(3600).build();
        em.persist(p);
        Router r = Router.builder().tenant(t).name("R" + seq).token("tok" + seq).wgIp("10.9.0." + seq).radiusSecret("s").build();
        em.persist(r);
        em.persist(Transaction.builder().tenant(t).packagePlan(p).router(r).reference(status + "-" + seq++)
                .phone("0772000000").amount(BigDecimal.valueOf(1000)).network("MTN").status(status).createdAt(at).build());
    }

    List<String> refs(String status) {
        return transactions.findInRange(a.getId(), FROM, TO, status, PageRequest.of(0, 50, Sort.by("reference")))
                .map(Transaction::getReference).getContent();
    }

    @Test
    void rangeIsTenantScopedHalfOpenAndGroupsStatuses() {
        tx(a, "SUCCESSFUL", FROM);                 // 0: on the start boundary → in
        tx(a, "FAILED", FROM.plusDays(1));         // 1
        tx(a, "PENDING", FROM.plusDays(2));        // 2
        tx(a, "EXPIRED", FROM.plusDays(3));        // 3: neither success nor failure → pending group
        tx(a, "SUCCESSFUL", TO);                   // 4: on the end boundary → out
        tx(a, "SUCCESSFUL", FROM.minusSeconds(1)); // 5: before → out
        tx(b, "SUCCESSFUL", FROM.plusDays(1));     // 6: other tenant → out
        em.flush();
        em.clear();

        assertThat(refs("ALL")).containsExactlyInAnyOrder("SUCCESSFUL-0", "FAILED-1", "PENDING-2", "EXPIRED-3");
        assertThat(refs("SUCCESSFUL")).containsExactly("SUCCESSFUL-0");
        assertThat(refs("FAILED")).containsExactly("FAILED-1");
        assertThat(refs("PENDING")).containsExactlyInAnyOrder("PENDING-2", "EXPIRED-3");
        assertThat(transactions.rangeFacts(a.getId(), FROM, TO)).hasSize(4);
    }
}
