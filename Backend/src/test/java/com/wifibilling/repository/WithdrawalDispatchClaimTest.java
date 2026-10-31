package com.wifibilling.repository;

import com.wifibilling.model.Tenant;
import com.wifibilling.model.WithdrawalRequest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dispatch claim is what guarantees a payout is sent at most once: the
 * conditional UPDATE must succeed for exactly one caller, and never for a
 * withdrawal that is already sent or final. JPA slice on embedded H2.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:withdrawal-claim;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WithdrawalDispatchClaimTest {

    @Autowired EntityManager em;
    @Autowired WithdrawalRequestRepository withdrawals;

    private WithdrawalRequest withdrawal(String status) {
        Tenant tenant = Tenant.builder().name("T").email("t" + System.nanoTime() + "@example.com")
                .phone("256700000000").passwordHash("x").build();
        em.persist(tenant);
        WithdrawalRequest w = WithdrawalRequest.builder().tenant(tenant).amount(20_000L).fee(600L)
                .totalDeduction(20_600L).phone("+256772000000").payoutRef("ref-" + System.nanoTime())
                .idempotencyKey("key-" + System.nanoTime()).status(status).build();
        em.persist(w);
        em.flush();
        return w;
    }

    @Test
    void claimSucceedsExactlyOnce() {
        Long id = withdrawal(WithdrawalRequest.REQUESTED).getId();

        assertThat(withdrawals.claimDispatch(id, OffsetDateTime.now())).isEqualTo(1);
        assertThat(withdrawals.claimDispatch(id, OffsetDateTime.now())).isZero();

        WithdrawalRequest claimed = withdrawals.findById(id).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(WithdrawalRequest.PROCESSING);
        assertThat(claimed.getDispatchedAt()).isNotNull();
    }

    @Test
    void finalOrManualWithdrawalsCannotBeClaimed() {
        for (String status : new String[]{WithdrawalRequest.COMPLETED, WithdrawalRequest.FAILED,
                WithdrawalRequest.CANCELLED, WithdrawalRequest.PROCESSING}) {
            assertThat(withdrawals.claimDispatch(withdrawal(status).getId(), OffsetDateTime.now())).isZero();
        }
    }

    @Test
    void idempotencyKeyLooksUpPerTenant() {
        WithdrawalRequest w = withdrawal(WithdrawalRequest.REQUESTED);

        assertThat(withdrawals.findByTenantIdAndIdempotencyKey(w.getTenant().getId(), w.getIdempotencyKey()))
                .contains(w);
        assertThat(withdrawals.findByTenantIdAndIdempotencyKey(w.getTenant().getId() + 1, w.getIdempotencyKey()))
                .isEmpty();
    }
}
