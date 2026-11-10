package com.wifibilling;

import com.jayway.jsonpath.JsonPath;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.Transaction;
import com.wifibilling.repository.PackagePlanRepository;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin_analytics;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=test-only-jwt-secret-with-at-least-32-characters",
        "wireguard.server.endpoint=203.0.113.10:443",
        "wireguard.server.public-key=yoay+E/LyCqRq0xxqsIRJdRoqe8XjRVmkMe3nDx5wEc=",
        "wireguard.server.subnet=10.9.0.0/16",
        "radius.server.ip=10.9.0.1",
        "provisioning.public-base-url=http://localhost:8080"
})
@AutoConfigureMockMvc
class AdminAnalyticsIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired RouterRepository routerRepository;
    @Autowired PackagePlanRepository packageRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String login(String email, String password) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private String createTenant(String name, String email) throws Exception {
        tenantRepository.save(Tenant.builder().name(name).email(email).phone("+256700000011")
                .passwordHash(passwordEncoder.encode("tenant-password")).role("ROLE_ADMIN").status("ACTIVE")
                .createdAt(OffsetDateTime.now().minusDays(30)).build());
        return login(email, "tenant-password");
    }

    private Number postJson(String jwt, String path, String body) throws Exception {
        return JsonPath.read(mockMvc.perform(post(path).header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void payment(Tenant tenant, long routerId, long packageId, long amount, String status,
                         String network, long platformFee, OffsetDateTime at) {
        transactionRepository.save(Transaction.builder()
                .tenant(tenant)
                .router(routerRepository.findById(routerId).orElseThrow())
                .packagePlan(packageRepository.findById(packageId).orElseThrow())
                .reference("T-" + UUID.randomUUID())
                .phone("+256700123456")
                .amount(BigDecimal.valueOf(amount))
                .network(network)
                .status(status)
                .platformFee(platformFee)
                .createdAt(at)
                .build());
    }

    @Test
    void overviewAggregatesPaymentsTopBusinessesAndDormantTenants() throws Exception {
        tenantRepository.save(Tenant.builder().name("Platform Owner").email("analytics-owner@example.test")
                .phone("+256700000000").passwordHash(passwordEncoder.encode("owner-password"))
                .role("ROLE_SUPERADMIN").status("ACTIVE").build());
        String admin = login("analytics-owner@example.test", "owner-password");

        String busyJwt = createTenant("Busy Cafe", "busy@example.test");
        String quietJwt = createTenant("Quiet Hostel", "quiet@example.test");
        Tenant busy = tenantRepository.findByEmailIgnoreCase("busy@example.test").orElseThrow();
        Tenant quiet = tenantRepository.findByEmailIgnoreCase("quiet@example.test").orElseThrow();

        long busyRouter = postJson(busyJwt, "/api/routers", "{\"name\":\"Cafe Router\"}").longValue();
        long busyPlan = postJson(busyJwt, "/api/packages",
                "{\"name\":\"Hour\",\"price\":1000,\"rateLimit\":\"5M/5M\",\"sessionTimeout\":3600,\"sharedUsers\":1}").longValue();
        long quietRouter = postJson(quietJwt, "/api/routers", "{\"name\":\"Hostel Router\"}").longValue();
        long quietPlan = postJson(quietJwt, "/api/packages",
                "{\"name\":\"Day\",\"price\":2000,\"rateLimit\":\"5M/5M\",\"sessionTimeout\":86400,\"sharedUsers\":1}").longValue();

        OffsetDateTime now = OffsetDateTime.now();
        payment(busy, busyRouter, busyPlan, 1000, "SUCCESSFUL", "MTN", 30, now.minusMinutes(5));
        payment(busy, busyRouter, busyPlan, 2000, "SUCCESSFUL", "AIRTEL", 60, now.minusDays(2));
        payment(busy, busyRouter, busyPlan, 1000, "FAILED", "MTN", 0, now.minusDays(2));
        // Quiet Hostel last sold 20 days ago: still in the 30-day window, but dormant for a week.
        payment(quiet, quietRouter, quietPlan, 2000, "SUCCESSFUL", "MTN", 0, now.minusDays(20));
        // Previous 30-day period, for the comparison totals.
        payment(busy, busyRouter, busyPlan, 500, "SUCCESSFUL", "MTN", 0, now.minusDays(45));

        mockMvc.perform(get("/api/admin/analytics/overview").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(30))
                .andExpect(jsonPath("$.daily.length()").value(30))
                .andExpect(jsonPath("$.period.gross").value(5000))
                .andExpect(jsonPath("$.period.sales").value(3))
                .andExpect(jsonPath("$.period.failed").value(1))
                .andExpect(jsonPath("$.period.platformFee").value(90))
                .andExpect(jsonPath("$.previousPeriod.gross").value(500))
                .andExpect(jsonPath("$.today.sales").value(1))
                .andExpect(jsonPath("$.networks[0].network").value("MTN"))
                .andExpect(jsonPath("$.networks[0].gross").value(3000))
                .andExpect(jsonPath("$.topTenants[0].name").value("Busy Cafe"))
                .andExpect(jsonPath("$.topTenants[0].gross").value(3000))
                .andExpect(jsonPath("$.dormantCount").value(1))
                .andExpect(jsonPath("$.dormant[0].name").value("Quiet Hostel"))
                .andExpect(jsonPath("$.tenantMix.hotspot").value(2))
                .andExpect(jsonPath("$.tenantMix.withRouters").value(2));

        // Tenants never see platform analytics.
        mockMvc.perform(get("/api/admin/analytics/overview").header("Authorization", "Bearer " + busyJwt))
                .andExpect(status().isForbidden());
    }
}
