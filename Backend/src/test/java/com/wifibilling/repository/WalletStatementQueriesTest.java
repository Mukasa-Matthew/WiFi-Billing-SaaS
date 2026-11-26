package com.wifibilling.repository;

import com.wifibilling.model.Tenant;
import com.wifibilling.model.WalletAccount;
import com.wifibilling.model.WalletLedgerEntry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Wallet statement queries: opening entry before the range, balances inside it, oldest first. */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:wallet-statement;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WalletStatementQueriesTest {

    static final OffsetDateTime FROM = OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    static final OffsetDateTime TO = FROM.plusDays(7);

    @Autowired EntityManager em;
    @Autowired WalletLedgerEntryRepository ledger;
    int seq;

    WalletAccount wallet(String email) {
        Tenant t = Tenant.builder().name(email).email(email).phone("256700000000").passwordHash("x").build();
        em.persist(t);
        WalletAccount w = WalletAccount.builder().tenant(t).build();
        em.persist(w);
        return w;
    }

    void entry(WalletAccount w, OffsetDateTime at, long availableAfter) {
        em.persist(WalletLedgerEntry.builder().wallet(w).type("COLLECTION_CREDIT").amount(1L)
                .availableAfter(availableAfter).pendingAfter(0L).reservedAfter(0L)
                .externalRef("ref-" + seq++).createdAt(at).build());
    }

    @Test
    void openingIsTheLastEntryBeforeAndRangeIsHalfOpenAndOrdered() {
        WalletAccount a = wallet("a@example.com"), b = wallet("b@example.com");
        entry(a, FROM.minusDays(2), 100);
        entry(a, FROM.minusSeconds(1), 250);   // opening
        entry(a, FROM.plusDays(3), 900);       // inserted out of order on purpose
        entry(a, FROM, 400);                   // start boundary → in
        entry(a, TO, 9_999);                   // end boundary → out
        entry(b, FROM.plusDays(1), 7_777);     // other wallet → out
        em.flush();
        em.clear();

        assertThat(ledger.findFirstByWalletIdAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(a.getId(), FROM))
                .get().extracting(WalletLedgerEntry::getAvailableAfter).isEqualTo(250L);
        assertThat(ledger.availableAfterInRange(a.getId(), FROM, TO)).containsExactly(400L, 900L);
        assertThat(ledger.findByWalletIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(a.getId(), FROM, TO, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(2);
    }
}
