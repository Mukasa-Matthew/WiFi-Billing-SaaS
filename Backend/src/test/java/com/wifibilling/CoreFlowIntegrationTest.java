package com.wifibilling;

import com.jayway.jsonpath.JsonPath;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.NasRepository;
import com.wifibilling.repository.RadCheckRepository;
import com.wifibilling.repository.RadReplyRepository;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core_flow;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
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
class CoreFlowIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TenantRepository tenantRepository;
    @Autowired RouterRepository routerRepository;
    @Autowired NasRepository nasRepository;
    @Autowired RadCheckRepository radCheckRepository;
    @Autowired RadReplyRepository radReplyRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Autowired com.wifibilling.repository.IpBindingRepository bindingRepository;
    @Autowired com.wifibilling.repository.RadAcctRepository accountingRepository;
    @Autowired com.wifibilling.service.VoucherExpirationWorker lifecycle;
    @Autowired com.wifibilling.service.RouterManagementService management;

    @Autowired com.wifibilling.service.SubscriptionService subscriptionService;

    private String actor(String suffix) throws Exception {
        String email = suffix + "@example.test";
        Tenant createdTenant = tenantRepository.save(Tenant.builder().name(suffix).email(email).phone("+256700000010").passwordHash(passwordEncoder.encode("testing-password")).role("ROLE_ADMIN").status("ACTIVE").build());
        subscriptionService.grantTrialIfEligible(createdTenant);
        return JsonPath.read(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"testing-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }
    private String request(String jwt, String path, String body) throws Exception {
        return mockMvc.perform(post(path).header("Authorization","Bearer "+jwt).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }
    private Number plan(String jwt) throws Exception {
        return JsonPath.read(request(jwt,"/api/packages","{\"name\":\"One Hour\",\"price\":1000,\"rateLimit\":\"5M/5M\",\"sessionTimeout\":3600,\"sharedUsers\":1}"),"$.id");
    }

    @Test @org.springframework.transaction.annotation.Transactional
    void customizedVouchersAssignmentAndSaleAreTenantScopedAndNotDuplicated() throws Exception {
        String jwt=actor("voucher-workflow"), other=actor("voucher-other"); Number planId=plan(jwt);
        String body=request(jwt,"/api/vouchers","{\"packageId\":"+planId+",\"count\":3,\"prefix\":\"X\",\"codeLength\":6,\"codeFormat\":\"NUMERIC\",\"batchReference\":\"TEST-BATCH\",\"giveaway\":false}");
        java.util.List<String> codes=JsonPath.read(body,"$[*].code");
        org.junit.jupiter.api.Assertions.assertEquals(3,new java.util.HashSet<>(codes).size());
        assertTrue(codes.stream().allMatch(c->c.matches("X[2-9]{5}")));
        Number customer=JsonPath.read(request(jwt,"/api/customers","{\"name\":\"Test Customer\",\"phone\":\"256700001111\"}"),"$.id");
        request(jwt,"/api/vouchers/assign","{\"customerId\":"+customer+",\"codes\":[\""+codes.getFirst()+"\"]}");
        mockMvc.perform(get("/api/vouchers/"+codes.getFirst()).header("Authorization","Bearer "+jwt))
                .andExpect(jsonPath("$.customerName").value("Test Customer"));
        mockMvc.perform(post("/api/vouchers/assign").header("Authorization","Bearer "+other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":"+customer+",\"codes\":[\""+codes.getFirst()+"\"]}"))
                .andExpect(status().isNotFound());
        Number routerId=JsonPath.read(request(jwt,"/api/routers","{\"name\":\"Sale Router\"}"),"$.id");
        String sale="{\"routerId\":"+routerId+",\"amount\":1000,\"method\":\"CASH\"}";
        request(jwt,"/api/vouchers/"+codes.getFirst()+"/sale",sale);
        mockMvc.perform(post("/api/vouchers/"+codes.getFirst()+"/sale").header("Authorization","Bearer "+jwt).contentType(MediaType.APPLICATION_JSON).content(sale)).andExpect(status().isConflict());
        mockMvc.perform(get("/api/finance/payments").header("Authorization","Bearer "+jwt)).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/finance/payments").header("Authorization","Bearer "+other)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test @org.springframework.transaction.annotation.Transactional
    void firstLoginStartsAbsoluteValidityAndExpiryRemovesRadiusCredentials() throws Exception {
        String jwt=actor("voucher-timing");Number planId=plan(jwt);
        String code=JsonPath.read(request(jwt,"/api/vouchers","{\"packageId\":"+planId+",\"count\":1}"),"$[0].code");
        var voucher=voucherRepositoryForTiming(code);
        org.junit.jupiter.api.Assertions.assertNull(voucher.getExpiresAt());
        var first=java.time.OffsetDateTime.now().minusMinutes(5);
        accountingRepository.save(com.wifibilling.radius.RadAcct.builder().username(code).acctSessionId("time-session").acctUniqueId("time-unique")
                .nasIpAddress("10.9.0.99").acctStartTime(first).acctUpdateTime(first).build());
        lifecycle.processVoucherLifecycle();
        voucher=voucherRepositoryForTiming(code);
        org.junit.jupiter.api.Assertions.assertEquals(first.plusHours(1).toEpochSecond(),voucher.getExpiresAt().toEpochSecond());
        assertTrue(radCheckRepository.findFirstByUsernameAndAttribute(code,"Expiration").isPresent());
        var original=voucher.getExpiresAt();
        accountingRepository.save(com.wifibilling.radius.RadAcct.builder().username(code).acctSessionId("reconnect").acctUniqueId("reconnect-unique")
                .nasIpAddress("10.9.0.99").acctStartTime(java.time.OffsetDateTime.now()).build());
        lifecycle.processVoucherLifecycle();
        org.junit.jupiter.api.Assertions.assertEquals(original,voucherRepositoryForTiming(code).getExpiresAt());
        mockMvc.perform(get("/api/users").header("Authorization","Bearer "+jwt)).andExpect(jsonPath("$[0].status").value("active"));
        voucher.setExpiresAt(java.time.OffsetDateTime.now().minusSeconds(1));timingVouchers.saveAndFlush(voucher);
        lifecycle.processVoucherLifecycle();
        org.junit.jupiter.api.Assertions.assertEquals("expired",voucherRepositoryForTiming(code).getStatus());
        assertFalse(radCheckRepository.existsByUsername(code));
        mockMvc.perform(post("/api/vouchers/"+code+"/archive").header("Authorization","Bearer "+jwt)).andExpect(status().isOk());
        mockMvc.perform(get("/api/users").header("Authorization","Bearer "+jwt)).andExpect(jsonPath("$.length()").value(0));
    }
    @Autowired com.wifibilling.repository.VoucherRepository timingVouchers;
    private com.wifibilling.model.Voucher voucherRepositoryForTiming(String code){return timingVouchers.findByCode(code).orElseThrow();}

    @Test @org.springframework.transaction.annotation.Transactional
    void bindingsRequireAcknowledgementAndTasksAreRouterScoped() throws Exception {
        String jwt=actor("binding-workflow"),other=actor("binding-other");
        String routerBody=request(jwt,"/api/routers","{\"name\":\"Binding Router\"}");
        Number routerId=JsonPath.read(routerBody,"$.id");
        String portalBody=mockMvc.perform(get("/api/routers/"+routerId+"/portal-id").header("Authorization","Bearer "+jwt))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token=JsonPath.read(portalBody,"$.portalId");
        // A second pending router must not collide on an empty tunnel address.
        request(jwt,"/api/routers","{\"name\":\"Second Router\"}");
        Number bindingPlan=plan(jwt);
        request(jwt,"/api/bindings","{\"routerId\":"+routerId+",\"packageId\":"+bindingPlan+",\"name\":\"Office\",\"mac\":\"AA:BB:CC:DD:EE:11\",\"address\":\"10.10.0.7\",\"recordPayment\":false}");
        mockMvc.perform(get("/api/bindings").header("Authorization","Bearer "+jwt)).andExpect(jsonPath("$[0].synced").value(false));
        String script=mockMvc.perform(get("/api/router/provision/"+token+"/management.rsc")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(script.contains("type=bypassed"));
        assertTrue(script.indexOf("/queue/simple/add") < script.indexOf("/ip/hotspot/ip-binding/add"));
        assertTrue(script.indexOf("/system/scheduler/add") < script.indexOf("/ip/hotspot/ip-binding/add"));
        assertTrue(script.contains("interval=0s"));
        String hash=java.util.regex.Pattern.compile("bindings-ack\\?hash=([a-f0-9]{64})").matcher(script).results().findFirst().orElseThrow().group(1);
        mockMvc.perform(post("/api/router/provision/"+token+"/bindings-ack").param("hash",hash)).andExpect(status().isOk());
        mockMvc.perform(get("/api/bindings").header("Authorization","Bearer "+jwt)).andExpect(jsonPath("$[0].synced").value(true));
        mockMvc.perform(get("/api/bindings").header("Authorization","Bearer "+other)).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post("/api/routers/"+routerId+"/tasks").header("Authorization","Bearer "+other).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"SYNC_TIME\"}")).andExpect(status().isNotFound());
        Number task=JsonPath.read(request(jwt,"/api/routers/"+routerId+"/tasks","{\"action\":\"SYNC_PORTAL\"}"),"$.id");
        mockMvc.perform(post("/api/router/provision/"+token+"/tasks/"+task+"/ack").param("success","true")).andExpect(status().isOk());
        mockMvc.perform(get("/api/routers/"+routerId+"/tasks").header("Authorization","Bearer "+jwt)).andExpect(jsonPath("$[0].status").value("APPLIED"));
    }

    @Test
    void superadminCanCreateTenantWhoCanImmediatelyLogin() throws Exception {
        tenantRepository.save(Tenant.builder()
                .name("Platform Owner")
                .email("owner@example.test")
                .phone("+256700000000")
                .passwordHash(passwordEncoder.encode("owner-password"))
                .role("ROLE_SUPERADMIN")
                .status("ACTIVE")
                .build());

        String adminLoginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"owner@example.test","password":"owner-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ROLE_SUPERADMIN"))
                .andReturn().getResponse().getContentAsString();
        String adminJwt = JsonPath.read(adminLoginBody, "$.token");

        String createdBody = mockMvc.perform(post("/api/admin/tenants")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"New Hotspot Business",
                                  "email":"operator@example.test",
                                  "phone":"+256700000002"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant.role").value("ROLE_ADMIN"))
                .andExpect(jsonPath("$.tenant.status").value("ACTIVE"))
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String temporaryPassword = JsonPath.read(createdBody, "$.temporaryPassword");

        String tenantLoginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"operator@example.test","password":"%s"}
                                """.formatted(temporaryPassword)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Hotspot Business"))
                .andExpect(jsonPath("$.email").value("operator@example.test"))
                .andExpect(jsonPath("$.role").value("ROLE_ADMIN"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String tenantJwt = JsonPath.read(tenantLoginBody, "$.token");

        mockMvc.perform(get("/api/dashboard/stats").header("Authorization", "Bearer " + tenantJwt))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + tenantJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"tenant-password"}
                                """.formatted(temporaryPassword)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"operator@example.test","password":"tenant-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void tenantCanCreatePackageProvisionRouterAndGenerateVoucher() throws Exception {
        Tenant flowTenant = tenantRepository.save(Tenant.builder()
                .name("Flow Test Cafe")
                .email("flow@example.test")
                .phone("+256700000001")
                .passwordHash(passwordEncoder.encode("correct-horse-battery-staple"))
                .role("ROLE_ADMIN")
                .status("ACTIVE")
                .build());
        subscriptionService.grantTrialIfEligible(flowTenant);

        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"flow@example.test","password":"correct-horse-battery-staple"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ROLE_ADMIN"))
                .andReturn().getResponse().getContentAsString();
        String jwt = JsonPath.read(loginBody, "$.token");

        String packageBody = mockMvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"One Hour","price":1000,"rateLimit":"5M/5M","sessionTimeout":3600,"sharedUsers":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("One Hour"))
                .andReturn().getResponse().getContentAsString();
        Number packageId = JsonPath.read(packageBody, "$.id");

        String routerBody = mockMvc.perform(post("/api/routers")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Front Desk Router\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andReturn().getResponse().getContentAsString();
        Number routerId = JsonPath.read(routerBody, "$.id");

        // Issue one-time secure setup code
        String setupBody = mockMvc.perform(post("/api/routers/" + routerId + "/setup-code")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"FULL\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String setupPath = JsonPath.read(setupBody, "$.path");

        mockMvc.perform(get(setupPath))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/interface/wireguard add")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("wifi-billing")));

        var router = routerRepository.findById(routerId.longValue()).orElseThrow();
        assertTrue(nasRepository.existsByNasname(router.getWgIp()));

        String voucherBody = mockMvc.perform(post("/api/vouchers")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packageId\":" + packageId + ",\"count\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].status").value("unused"))
                .andReturn().getResponse().getContentAsString();
        String voucherCode = JsonPath.read(voucherBody, "$[0].code");
        assertTrue(radCheckRepository.existsByUsername(voucherCode));
        assertFalse(radReplyRepository.findAllByUsername(voucherCode).isEmpty());

        mockMvc.perform(delete("/api/routers/{id}", routerId.longValue())
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent());
        assertFalse(routerRepository.existsById(routerId.longValue()));
        assertFalse(nasRepository.existsByNasname(router.getWgIp()));
    }
}
