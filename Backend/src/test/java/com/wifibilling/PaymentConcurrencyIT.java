package com.wifibilling;

import com.wifibilling.dto.PaymentDto;
import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Router;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.Transaction;
import com.wifibilling.repository.PackagePlanRepository;
import com.wifibilling.repository.RadCheckRepository;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.repository.WalletLedgerEntryRepository;
import com.wifibilling.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-PostgreSQL concurrency tests for the payment lifecycle. These prove the
 * guarantees Mockito cannot: row locking, unique constraints, and the atomic
 * dispatch claim under an actual database.
 *
 * Gated on WB_IT_URL — point it at a DISPOSABLE Postgres database, e.g.:
 *   WB_IT_URL=jdbc:postgresql://localhost:5439/wifi_it \
 *   WB_IT_USER=radius_user WB_IT_PASSWORD=... \
 *   ./mvnw test -Dtest=PaymentConcurrencyIT
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${WB_IT_URL:jdbc:postgresql://localhost:5439/wifi_it}",
        "spring.datasource.username=${WB_IT_USER:radius_user}",
        "spring.datasource.password=${WB_IT_PASSWORD:}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=test-only-jwt-secret-with-at-least-32-characters",
        "payment.mock-enabled=true",
        "payment.gateway.mock.auto-approve-seconds=3600",
        "payment.reconcile.initial-delay-ms=3600000",
        "wireguard.server.endpoint=203.0.113.10:443",
        "wireguard.server.public-key=yoay+E/LyCqRq0xxqsIRJdRoqe8XjRVmkMe3nDx5wEc=",
        "wireguard.server.subnet=10.9.0.0/16",
        "radius.server.ip=10.9.0.1",
        "provisioning.public-base-url=http://localhost:8080"
})
@EnabledIfEnvironmentVariable(named = "WB_IT_URL", matches = ".+")
class PaymentConcurrencyIT {

    @Autowired com.wifibilling.controller.PaymentController paymentController;
    @Autowired PaymentService paymentService;
    @Autowired TransactionRepository transactionRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired RouterRepository routerRepository;
    @Autowired PackagePlanRepository packagePlanRepository;
    @Autowired RadCheckRepository radCheckRepository;
    @Autowired WalletLedgerEntryRepository ledgerRepository;

    private Tenant tenant;
    private Router router;
    private PackagePlan plan;
    private String token;

    @BeforeEach
    void seed() {
        tenant = tenantRepository.save(Tenant.builder()
                .name("IT Tenant").email("it-" + UUID.randomUUID() + "@example.test")
                .phone("+256700000001").passwordHash("x").role("ROLE_ADMIN").status("ACTIVE").build());
        token = "tok_it_" + UUID.randomUUID().toString().substring(0, 12);
        router = routerRepository.save(Router.builder()
                .tenant(tenant).name("it-router").token(token)
                .wgIp("10.99." + (int) (Math.random() * 200 + 1) + "." + (int) (Math.random() * 200 + 1))
                .radiusSecret("it-radius-secret-1234567890").build());
        plan = packagePlanRepository.save(PackagePlan.builder()
                .tenant(tenant).name("1 Hour").price(new BigDecimal("1000.00"))
                .rateLimit("2M/2M").sessionTimeout(3600).availability("ACTIVE").build());
    }

    private Transaction pendingTx(String checkoutRef, String externalRef) {
        return transactionRepository.save(Transaction.builder()
                .tenant(tenant).packagePlan(plan).router(router)
                .reference("PAY-IT-" + UUID.randomUUID().toString().substring(0, 8))
                .checkoutRef(checkoutRef).externalReference(externalRef)
                .phone("+256771234567").amount(new BigDecimal("1000.00"))
                .network("MTN").status(PaymentService.STATUS_PENDING)
                .gatewayMode("PLATFORM").createdAt(OffsetDateTime.now()).build());
    }

    private <T> List<T> race(int n, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> { gate.await(); return task.call(); }));
        }
        gate.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) results.add(f.get());
        pool.shutdown();
        return results;
    }

    @Test
    void concurrentSuccessWebhooksFulfillExactlyOnce() throws Exception {
        Transaction tx = pendingTx("chk-it-1", UUID.randomUUID().toString());
        String ref = tx.getReference();

        race(6, () -> { paymentService.processWebhook(
                new PaymentDto.WebhookRequest(ref, "SUCCESSFUL", null, null)); return null; });

        Transaction done = transactionRepository.findByReference(ref).orElseThrow();
        assertEquals(PaymentService.STATUS_SUCCESSFUL, done.getStatus());
        assertNotNull(done.getVoucherCode());

        // Exactly one voucher: one username across all RADIUS check rows.
        var rows = radCheckRepository.findAllByUsername(done.getVoucherCode());
        assertFalse(rows.isEmpty());
        assertEquals(done.getVoucherCode(),
                rows.stream().map(r -> r.getUsername()).distinct().reduce((a, b) -> a + b).orElse(""));

        // Exactly one wallet credit for this transaction.
        long credits = ledgerRepository.findAll().stream()
                .filter(e -> ("COL:" + ref).equals(e.getExternalRef())).count();
        assertEquals(1, credits);
    }

    @Test
    void concurrentDispatchClaimedByExactlyOneCaller() throws Exception {
        Transaction tx = pendingTx("chk-it-2", UUID.randomUUID().toString());
        // OWN mode passes the mock-gateway guard so the claim itself is exercised.
        tx.setGatewayMode("OWN");
        transactionRepository.save(tx);
        Long id = tx.getId();

        List<Boolean> claims = race(8, () -> paymentService.claimCollectionDispatch(id));

        assertEquals(1, claims.stream().filter(b -> b).count());
    }

    @Test
    void concurrentInitiateSameCheckoutCreatesOneTransaction() throws Exception {
        PaymentDto.InitiateRequest req = new PaymentDto.InitiateRequest(
                token, plan.getId(), "256771234567", null, "chk-it-race", null);

        List<PaymentDto.InitiateResponse> responses = race(4, () -> paymentController.initiate(req));

        assertEquals(1, responses.stream().map(PaymentDto.InitiateResponse::reference).distinct().count());
        assertTrue(transactionRepository.findByCheckoutRef("chk-it-race").isPresent());
    }

    @Test
    void conflictingCheckoutReuseIsRejected() throws Exception {
        pendingTx("chk-it-conflict", UUID.randomUUID().toString());

        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                paymentService.initiatePayment(new PaymentDto.InitiateRequest(
                        token, plan.getId(), "256770000000", null, "chk-it-conflict", null)));
    }
}
