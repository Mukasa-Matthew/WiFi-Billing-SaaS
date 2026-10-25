package com.wifibilling.service;

import com.wifibilling.model.Router;
import com.wifibilling.model.RouterSetupCode;
import com.wifibilling.model.RouterSetupCode.Purpose;
import com.wifibilling.repository.RouterSetupCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Setup-code lifecycle. An in-memory store stands in for the repository and
 * mirrors its conditional updates, so single-use and revocation are exercised
 * for real. No database is involved.
 */
class RouterSetupServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-25T10:00:00Z");
    private final MutableClock clock = new MutableClock(T0);
    private final Map<Long, RouterSetupCode> store = new HashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private RouterSetupCodeRepository repo;
    private RouterSetupService service;
    private final Router router = Router.builder().id(42L).token("tok_PUBLICportalIdMarker123").name("Test").build();

    @BeforeEach
    void setUp() {
        repo = mock(RouterSetupCodeRepository.class);
        when(repo.save(any())).thenAnswer(i -> {
            RouterSetupCode c = i.getArgument(0);
            if (c.getId() == null) c.setId(ids.incrementAndGet());
            store.put(c.getId(), c);
            return c;
        });
        when(repo.findByCodeHash(anyString())).thenAnswer(i -> store.values().stream()
                .filter(c -> c.getCodeHash().equals(i.getArgument(0))).findFirst());
        when(repo.markScriptFetched(anyLong(), any())).thenAnswer(i -> {
            RouterSetupCode c = store.get((Long) i.getArgument(0));
            if (c == null || c.getScriptFetchedAt() != null || c.getRevokedAt() != null) return 0;
            c.setScriptFetchedAt(i.getArgument(1));
            return 1;
        });
        when(repo.bindRegisteredKey(anyLong(), anyString())).thenAnswer(i -> {
            RouterSetupCode c = store.get((Long) i.getArgument(0));
            if (c == null || c.getRegisteredKeyHash() != null) return 0;
            c.setRegisteredKeyHash(i.getArgument(1));
            return 1;
        });
        when(repo.revokeOutstanding(anyLong(), any())).thenAnswer(i -> {
            int n = 0;
            for (RouterSetupCode c : store.values()) {
                if (c.getRouter().getId().equals(i.getArgument(0)) && c.getRevokedAt() == null && c.getCompletedAt() == null) {
                    c.setRevokedAt(i.getArgument(1)); n++;
                }
            }
            return n;
        });
        service = new RouterSetupService(repo, clock, Duration.ofMinutes(30), Duration.ofMinutes(120));
    }

    private static void assertRejected(Runnable r) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, r::run);
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode(), "all failures look identical");
    }

    @Test
    void codesAreRandomAndOnlyTheirHashIsStored() {
        String a = service.issue(router, Purpose.FULL).code();
        String b = service.issue(router, Purpose.FULL).code();
        assertTrue(RouterSetupService.FORMAT.matcher(a).matches());
        assertNotEquals(a, b);
        store.values().forEach(c -> {
            assertNotEquals(a, c.getCodeHash());
            assertNotEquals(b, c.getCodeHash());
            assertEquals(64, c.getCodeHash().length());
        });
    }

    @Test
    void scriptIsReleasedExactlyOnce() {
        String code = service.issue(router, Purpose.FULL).code();
        assertEquals(42L, service.redeem(code, Purpose.FULL));
        assertRejected(() -> service.redeem(code, Purpose.FULL));
    }

    @Test
    void expiredCodeIsRejected() {
        String code = service.issue(router, Purpose.FULL).code();
        clock.advance(Duration.ofMinutes(30));
        assertRejected(() -> service.redeem(code, Purpose.FULL));
    }

    @Test
    void issuingANewCodeRevokesTheOldOne() {
        String old = service.issue(router, Purpose.FULL).code();
        String fresh = service.issue(router, Purpose.FULL).code();
        assertRejected(() -> service.redeem(old, Purpose.FULL));
        assertEquals(42L, service.redeem(fresh, Purpose.FULL));
    }

    @Test
    void managementCodeCannotReleaseTheCredentialScript() {
        String code = service.issue(router, Purpose.MANAGEMENT).code();
        assertRejected(() -> service.redeem(code, Purpose.FULL));
        assertEquals(42L, service.redeem(code, Purpose.MANAGEMENT));
    }

    @Test
    void publicRouterIdAndMalformedValuesAreNeverSetupCodes() {
        service.issue(router, Purpose.FULL);
        assertRejected(() -> service.redeem(router.getToken(), Purpose.FULL));
        assertRejected(() -> service.authorizeCallback(router.getToken()));
        assertRejected(() -> service.redeem(null, Purpose.FULL));
        assertRejected(() -> service.redeem("su_short", Purpose.FULL));
        assertRejected(() -> service.redeem("su_" + "A".repeat(32), Purpose.FULL)); // well-formed but unknown
    }

    @Test
    void callbacksNeedAFetchedScriptAndStopAtCompletion() {
        String code = service.issue(router, Purpose.FULL).code();
        assertRejected(() -> service.authorizeCallback(code));          // before the script was fetched
        service.redeem(code, Purpose.FULL);
        assertEquals(42L, service.authorizeCallback(code));            // register retries are fine
        assertEquals(42L, service.authorizeCallback(code));
        assertEquals(42L, service.complete(code));
        assertRejected(() -> service.authorizeCallback(code));          // closed
        assertRejected(() -> service.complete(code));
    }

    @Test
    void callbacksExpireAfterTheWindow() {
        String code = service.issue(router, Purpose.FULL).code();
        clock.advance(Duration.ofMinutes(29));
        service.redeem(code, Purpose.FULL);
        clock.advance(Duration.ofMinutes(119));
        assertEquals(42L, service.authorizeCallback(code));
        clock.advance(Duration.ofMinutes(1));
        assertRejected(() -> service.authorizeCallback(code));
    }

    @Test
    void managementCodeNeverAuthorizesKeyRegistration() {
        String code = service.issue(router, Purpose.MANAGEMENT).code();
        service.redeem(code, Purpose.MANAGEMENT);
        assertRejected(() -> service.authorizeCallback(code));
    }

    @Test
    void firstRegisteredKeyIsBoundAndADifferentKeyIsRefused() {
        String code = service.issue(router, Purpose.FULL).code();
        service.redeem(code, Purpose.FULL);
        String routerKey = "ROUTERkeyMarkerAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        String attackerKey = "ATTACKERkeyMarkerBBBBBBBBBBBBBBBBBBBBBBBBB=";
        assertEquals(42L, service.authorizeRegistration(code, routerKey));
        assertEquals(42L, service.authorizeRegistration(code, routerKey));   // router retries
        assertRejected(() -> service.authorizeRegistration(code, attackerKey));
        store.values().forEach(c -> assertNotEquals(routerKey, c.getRegisteredKeyHash(), "only a hash of the key is stored"));
    }

    @Test
    void registrationNeedsAFetchedScript() {
        String code = service.issue(router, Purpose.FULL).code();
        assertRejected(() -> service.authorizeRegistration(code, "ROUTERkeyMarkerAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
    }

    @Test
    void lostConcurrentRedemptionIsRejected() {
        String code = service.issue(router, Purpose.FULL).code();
        when(repo.markScriptFetched(anyLong(), any())).thenReturn(0); // another request won the update
        assertRejected(() -> service.redeem(code, Purpose.FULL));
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
