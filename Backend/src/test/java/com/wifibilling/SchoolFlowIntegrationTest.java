package com.wifibilling;

import com.jayway.jsonpath.JsonPath;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.Voucher;
import com.wifibilling.repository.*;
import com.wifibilling.school.IdentityClient;
import com.wifibilling.school.IdentityException;
import com.wifibilling.school.IdentitySignature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:school_flow;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
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
        "provisioning.public-base-url=http://localhost:8080",
        "payment.gateway.mode=mock",
        "payment.gateway.mock.auto-approve-seconds=600",
        "payment.webhook.secret=school-test-webhook-secret",
        "identity.shared-secret=" + SchoolFlowIntegrationTest.SECRET
})
@AutoConfigureMockMvc
class SchoolFlowIntegrationTest {
    static final String SECRET = "test-shared-secret-at-least-32-characters";
    static final String PHONE_MAC = "AA:BB:CC:00:00:01";
    static final String TABLET_MAC = "AA:BB:CC:00:00:02";

    @MockitoBean IdentityClient identity;
    @Autowired MockMvc mvc;
    @Autowired TenantRepository tenants;
    @Autowired VoucherRepository vouchers;
    @Autowired RadCheckRepository radCheck;
    @Autowired RadReplyRepository radReply;
    @Autowired RadAcctRepository radAcct;
    @Autowired PackagePlanRepository plans;
    @Autowired PasswordEncoder passwords;
    @Autowired com.wifibilling.service.SubscriptionService subscriptions;
    @Autowired com.wifibilling.service.VoucherExpirationWorker lifecycle;
    @Autowired com.wifibilling.controller.AdminTenantController adminTenants;
    private final ObjectMapper json = new ObjectMapper();

    record Site(Tenant tenant, String jwt, String routerToken, Number planId) {}

    private Site site(String name, boolean school) throws Exception {
        String email = name + "@example.test";
        Tenant tenant = tenants.save(Tenant.builder().name(name).email(email).phone("+256700000010").passwordHash(passwords.encode("testing-password"))
                .role("ROLE_ADMIN").status("ACTIVE").tenantType(school ? "SCHOOL" : null).schoolStaffRateLimit(school ? "4M/4M" : null).build());
        subscriptions.grantTrialIfEligible(tenant);
        String jwt = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"testing-password\"}")).andReturn().getResponse().getContentAsString(), "$.token");
        Number routerId = JsonPath.read(authed(jwt, "/api/routers", "{\"name\":\"" + name + " router\"}"), "$.id");
        String token = JsonPath.read(mvc.perform(get("/api/routers/" + routerId + "/portal-id").header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(), "$.portalId");
        Number plan = JsonPath.read(authed(jwt, "/api/packages", "{\"name\":\"Day\",\"price\":1000,\"rateLimit\":\"5M/5M\",\"sessionTimeout\":86400,\"sharedUsers\":1}"), "$.id");
        return new Site(tenant, jwt, token, plan);
    }
    private String authed(String jwt, String path, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + jwt).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }
    private String portal(String path, String body) throws Exception {
        return mvc.perform(post("/api/portal/school/" + path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private String verifyBody(Site site, String mac) { return "{\"token\":\"" + site.routerToken() + "\",\"identifier\":\"S23/0412\",\"pin\":\"4826\",\"mac\":\"" + mac + "\"}"; }
    private void verified(long tenantId, String status, String category, String personId) {
        var node = json.createObjectNode().put("status", status).put("personId", personId).put("fullName", "Amina Nakato")
                .put("category", category).put("groupName", "S4").put("accessUntil", LocalDate.now().plusYears(1).toString()).put("devicesAllowed", 1);
        if (status.equals("PIN_CHANGE_REQUIRED")) node.put("pinChangeToken", "pin-change-token");
        when(identity.verify(eq(tenantId), anyString(), anyString(), anyString())).thenReturn(node);
    }
    private MockHttpServletRequestBuilder signedDisconnect(String body, String secret, long timestamp, String nonce) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return post("/api/integrations/identity/disconnect").contentType(MediaType.APPLICATION_JSON).content(bytes)
                .header("X-Timestamp", timestamp).header("X-Nonce", nonce)
                .header("X-Signature", IdentitySignature.sign(secret, IdentitySignature.FROM_IDENTITY, timestamp, nonce, "POST", "/api/integrations/identity/disconnect", bytes));
    }
    private String pay(Site site, String mac, String ticket) throws Exception {
        String body = "{\"token\":\"" + site.routerToken() + "\",\"packageId\":" + site.planId() + ",\"phone\":\"0770000001\",\"mac\":\"" + mac + "\""
                + (ticket == null ? "" : ",\"schoolTicket\":\"" + ticket + "\"") + "}";
        return mvc.perform(post("/api/payments/initiate").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void ordinaryTenantsKeepTheirPortalAndPaymentsExactlyAsBefore() throws Exception {
        Site shop = site("ordinary-shop", false);
        mvc.perform(get("/api/portal/config").param("token", shop.routerToken())).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.school").value(false)).andExpect(jsonPath("$.settings.schoolVisitors").value(false))
                .andExpect(jsonPath("$.settings.buyForOther").value(true));
        mvc.perform(post("/api/portal/school/verify").contentType(MediaType.APPLICATION_JSON).content(verifyBody(shop, PHONE_MAC))).andExpect(status().isNotFound());
        mvc.perform(post("/api/payments/initiate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + shop.routerToken() + "\",\"packageId\":" + shop.planId() + ",\"phone\":\"0770000002\",\"mac\":\"" + PHONE_MAC + "\"}"))
                .andExpect(status().isAccepted());
        verifyNoInteractions(identity);
        mvc.perform(get("/api/school/status").header("Authorization", "Bearer " + shop.jwt())).andExpect(jsonPath("$.school").value(false));
        mvc.perform(get("/api/school").header("Authorization", "Bearer " + shop.jwt())).andExpect(status().isNotFound());
    }

    @Test
    void studentsMustSignInToBuyAndTheirVoucherFollowsThemAcrossDevicesAndSuspension() throws Exception {
        Site school = site("student-school", true);
        mvc.perform(get("/api/portal/config").param("token", school.routerToken()))
                .andExpect(jsonPath("$.settings.school").value(true)).andExpect(jsonPath("$.settings.buyForOther").value(false));
        assertTrue(pay(school, PHONE_MAC, null).contains("Sign in with your school ID"));
        verified(school.tenant().getId(), "OK", "STUDENT", "demo-0000aaaa");
        String result = portal("verify", verifyBody(school, PHONE_MAC.toLowerCase().replace(':', '-')));
        assertEquals("BUY", JsonPath.read(result, "$.action"));
        String ticket = JsonPath.read(result, "$.ticket");
        assertTrue(pay(school, TABLET_MAC, ticket).contains("Sign in with your school ID"), "a ticket only works on the device that signed in");
        String reference = JsonPath.read(pay(school, PHONE_MAC, ticket), "$.reference");
        mvc.perform(post("/api/payments/webhook").header("X-Webhook-Secret", "school-test-webhook-secret").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"" + reference + "\",\"status\":\"SUCCESSFUL\"}")).andExpect(status().isOk());
        String code = JsonPath.read(mvc.perform(get("/api/payments/status/" + reference)).andReturn().getResponse().getContentAsString(), "$.voucherCode");
        Voucher bought = vouchers.findByCode(code).orElseThrow();
        assertEquals("demo-0000aaaa", bought.getPersonId());
        assertEquals("STUDENT", bought.getSchoolCategory());
        assertNotNull(bought.getHardExpiresAt());
        assertTrue(radCheck.findFirstByUsernameAndAttribute(code, "Expiration").isPresent());
        assertEquals(PHONE_MAC, radCheck.findFirstByUsernameAndAttribute(code, "Calling-Station-Id").orElseThrow().getValue());

        String again = portal("verify", verifyBody(school, TABLET_MAC));
        assertEquals("CONNECT", JsonPath.read(again, "$.action"));
        assertEquals(code, JsonPath.read(again, "$.code"), "signing in again reuses the paid voucher instead of selling another");
        assertEquals(TABLET_MAC, radCheck.findFirstByUsernameAndAttribute(code, "Calling-Station-Id").orElseThrow().getValue());

        String body = "{\"tenantId\":" + school.tenant().getId() + ",\"personId\":\"demo-0000aaaa\"}";
        long now = Instant.now().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        mvc.perform(signedDisconnect(body, SECRET + "x", now, UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
        mvc.perform(signedDisconnect(body, SECRET, now - 600, UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
        mvc.perform(signedDisconnect(body, SECRET, now, nonce)).andExpect(status().isOk()).andExpect(jsonPath("$.vouchers").value(1));
        mvc.perform(signedDisconnect(body, SECRET, now, nonce)).andExpect(status().isUnauthorized());
        assertTrue(vouchers.findByCode(code).orElseThrow().getSchoolHold());
        assertFalse(radCheck.existsByUsername(code), "a suspended student's voucher cannot log in");

        assertEquals(code, JsonPath.read(portal("verify", verifyBody(school, PHONE_MAC)), "$.code"));
        assertFalse(vouchers.findByCode(code).orElseThrow().getSchoolHold());
        assertTrue(radCheck.existsByUsername(code), "a fresh ID and PIN check restores the paid time");
    }

    @Test
    void staffGetFreeAccessOnTheOwnersSpeedAndNeverSeeItAsAPackage() throws Exception {
        Site school = site("staff-school", true);
        verified(school.tenant().getId(), "OK", "STAFF", "demo-0000bbbb");
        String first = portal("verify", verifyBody(school, PHONE_MAC));
        assertEquals("CONNECT", JsonPath.read(first, "$.action"));
        String code = JsonPath.read(first, "$.code");
        Voucher staff = vouchers.findByCode(code).orElseThrow();
        assertEquals("SCHOOL_STAFF", staff.getSaleType());
        var staffPlan = plans.findFirstByTenantIdAndAvailabilityAndName(school.tenant().getId(), "SCHOOL", "School staff access").orElseThrow();
        assertEquals(0, staffPlan.getPrice().signum());
        assertEquals("4M/4M", staffPlan.getRateLimit());
        assertEquals(code, JsonPath.read(portal("verify", verifyBody(school, PHONE_MAC)), "$.code"));
        String packages = mvc.perform(get("/api/packages").header("Authorization", "Bearer " + school.jwt())).andReturn().getResponse().getContentAsString();
        assertEquals(1, (int) JsonPath.read(packages, "$.length()"), "only the owner's own package is listed");
        mvc.perform(get("/api/packages/" + staffPlan.getId()).header("Authorization", "Bearer " + school.jwt())).andExpect(status().isNotFound());
        assertTrue(pay(school, PHONE_MAC, null).contains("Sign in"));
    }

    @Test
    void firstSignInAsksForANewPinAndSignInErrorsReachThePortal() throws Exception {
        Site school = site("pin-school", true);
        verified(school.tenant().getId(), "PIN_CHANGE_REQUIRED", "STUDENT", "demo-0000cccc");
        String first = portal("verify", verifyBody(school, PHONE_MAC));
        assertEquals("CHANGE_PIN", JsonPath.read(first, "$.action"));
        assertEquals("pin-change-token", JsonPath.read(first, "$.pinChangeToken"));
        when(identity.changePin(school.tenant().getId(), "pin-change-token", "4826")).thenReturn(json.createObjectNode().put("status", "OK")
                .put("personId", "demo-0000cccc").put("fullName", "Amina").put("category", "STUDENT").put("accessUntil", LocalDate.now().plusMonths(6).toString()));
        assertEquals("BUY", JsonPath.read(portal("change-pin", "{\"token\":\"" + school.routerToken() + "\",\"pinChangeToken\":\"pin-change-token\",\"newPin\":\"4826\",\"mac\":\"" + PHONE_MAC + "\"}"), "$.action"));

        when(identity.verify(eq(school.tenant().getId()), anyString(), anyString(), anyString()))
                .thenThrow(new IdentityException(401, "WRONG_PIN", "That PIN is incorrect.", json.createObjectNode().put("attemptsLeft", 4)));
        mvc.perform(post("/api/portal/school/verify").contentType(MediaType.APPLICATION_JSON).content(verifyBody(school, PHONE_MAC)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("WRONG_PIN"))
                .andExpect(jsonPath("$.message").value("That PIN is incorrect.")).andExpect(jsonPath("$.attemptsLeft").value("4"));
        mvc.perform(post("/api/portal/school/verify").contentType(MediaType.APPLICATION_JSON).content(verifyBody(school, "not-a-mac"))).andExpect(status().isBadRequest());
    }

    @Test
    void visitorsUseOneDeviceUntilThePassEndsOrIsCancelled() throws Exception {
        Site school = site("visitor-school", true);
        String passId = UUID.randomUUID().toString();
        when(identity.admitVisitor(eq(school.tenant().getId()), anyString(), anyString())).thenReturn(json.createObjectNode()
                .put("passId", passId).put("fullName", "Guest Speaker").put("expiresAt", OffsetDateTime.now().plusHours(6).toString()));
        String body = "{\"token\":\"" + school.routerToken() + "\",\"code\":\"ABCD-EFGH\",\"mac\":\"" + PHONE_MAC + "\"}";
        String code = JsonPath.read(portal("visitor", body), "$.code");
        assertEquals(code, JsonPath.read(portal("visitor", body), "$.code"));
        assertEquals("SCHOOL_VISITOR", vouchers.findByCode(code).orElseThrow().getSaleType());
        mvc.perform(signedDisconnect("{\"tenantId\":" + school.tenant().getId() + ",\"visitorPassId\":\"" + passId + "\"}", SECRET,
                Instant.now().getEpochSecond(), UUID.randomUUID().toString())).andExpect(status().isOk());
        assertEquals("revoked", vouchers.findByCode(code).orElseThrow().getStatus());
    }

    @Test
    void unusedSchoolVouchersExpireAtTheirHardLimit() throws Exception {
        Site school = site("expiry-school", true);
        verified(school.tenant().getId(), "OK", "STAFF", "demo-0000dddd");
        String code = JsonPath.read(portal("verify", verifyBody(school, PHONE_MAC)), "$.code");
        Voucher voucher = vouchers.findByCode(code).orElseThrow();
        voucher.setHardExpiresAt(OffsetDateTime.now().minusMinutes(1));
        vouchers.saveAndFlush(voucher);
        lifecycle.processVoucherLifecycle();
        assertEquals("expired", vouchers.findByCode(code).orElseThrow().getStatus());
        assertFalse(radCheck.existsByUsername(code));
    }

    @Test
    void ownersControlVisitorsAndSpeedsThroughTheIdentitySystem() throws Exception {
        Site school = site("settings-school", true);
        when(identity.updateSchool(eq(school.tenant().getId()), anyMap())).thenReturn(json.createObjectNode().put("visitorsEnabled", true).put("visitorDailyCap", 20));
        mvc.perform(put("/api/school/settings").header("Authorization", "Bearer " + school.jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"visitorsEnabled\":true,\"visitorDailyCap\":20,\"staffRateLimit\":\"10m/10m\",\"visitorRateLimit\":\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visitorsEnabled").value(true)).andExpect(jsonPath("$.staffRateLimit").value("10M/10M"));
        verify(identity).updateSchool(eq(school.tenant().getId()), argThat(changes -> Boolean.TRUE.equals(changes.get("visitorsEnabled")) && Integer.valueOf(20).equals(changes.get("visitorDailyCap"))));
        mvc.perform(get("/api/portal/config").param("token", school.routerToken())).andExpect(jsonPath("$.settings.schoolVisitors").value(true));
        mvc.perform(put("/api/school/settings").header("Authorization", "Bearer " + school.jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"staffRateLimit\":\"fast please\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/school/staff-exceptions/" + UUID.randomUUID() + "/decision").header("Authorization", "Bearer " + school.jwt())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approve\":true}")).andExpect(status().isOk());
        verify(identity).decideStaffException(eq(school.tenant().getId()), any(), eq(true), contains("settings-school@example.test"));
    }

    @Test
    void superAdminCreatesSchoolTenantsAndAFailedSchoolLinkCreatesNothing() {
        when(identity.createSchool(anyLong(), eq("Kampala High"), eq("it@kampalahigh.test"), eq("IT Officer")))
                .thenReturn(json.createObjectNode().put("temporaryPassword", "temporary-secret").set("admin", json.createObjectNode().put("email", "it@kampalahigh.test").put("fullName", "IT Officer")));
        var created = adminTenants.createTenant(new com.wifibilling.dto.AdminTenantDto.CreateRequest("Kampala High", "owner@kampalahigh.test", "+256700000020", "SCHOOL", "it@kampalahigh.test", "IT Officer"));
        assertTrue(tenants.findById(created.tenant().id()).orElseThrow().isSchool());
        assertFalse(created.schoolItEmailSent());
        assertEquals("temporary-secret", created.schoolItTemporaryPassword());

        when(identity.createSchool(anyLong(), eq("Broken School"), anyString(), anyString())).thenThrow(IdentityException.unavailable());
        assertThrows(IdentityException.class, () -> adminTenants.createTenant(new com.wifibilling.dto.AdminTenantDto.CreateRequest("Broken School", "owner@broken.test", "+256700000021", "SCHOOL", "it@broken.test", "IT")));
        assertFalse(tenants.existsByEmailIgnoreCase("owner@broken.test"), "the tenant is rolled back when the school cannot be created");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> adminTenants.createTenant(new com.wifibilling.dto.AdminTenantDto.CreateRequest("No IT", "owner@noit.test", "+256700000022", "SCHOOL", null, null)));
    }

    @Test
    void staffAndVisitorDataCapsWriteMikrotikTotalLimitAndCutOffWhenExceeded() throws Exception {
        Site school = site("datacap-school", true);
        when(identity.updateSchool(eq(school.tenant().getId()), anyMap()))
                .thenReturn(json.createObjectNode().put("visitorsEnabled", true).put("visitorDailyCap", 15));

        mvc.perform(put("/api/school/settings").header("Authorization", "Bearer " + school.jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"staffEnabled\":true,\"staffRateLimit\":\"3M/3M\",\"staffDailyDataCapMb\":500,\"visitorsEnabled\":true,\"visitorDailyCap\":15,\"visitorRateLimit\":\"2M/2M\",\"visitorDataCapMb\":250,\"applyImmediately\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffEnabled").value(true))
                .andExpect(jsonPath("$.staffDailyDataCapMb").value(500))
                .andExpect(jsonPath("$.visitorDataCapMb").value(250));

        verified(school.tenant().getId(), "OK", "STAFF", "staff-cap-1");
        String code = JsonPath.read(portal("verify", verifyBody(school, PHONE_MAC)), "$.code");
        assertEquals(String.valueOf(500L * 1024L * 1024L),
                radReply.findFirstByUsernameAndAttribute(code, "Mikrotik-Total-Limit").orElseThrow().getValue());
        assertEquals("3M/3M",
                radReply.findFirstByUsernameAndAttribute(code, "Mikrotik-Rate-Limit").orElseThrow().getValue());

        // Simulate 500 MB consumed today in radacct
        radAcct.saveAndFlush(com.wifibilling.radius.RadAcct.builder()
                .acctSessionId("sess-staff-cap-1").acctUniqueId("uniq-staff-cap-1").username(code)
                .nasIpAddress("10.9.0.2").acctStartTime(OffsetDateTime.now().minusMinutes(10))
                .acctInputOctets(100L * 1024L * 1024L).acctOutputOctets(400L * 1024L * 1024L).build());

        // Worker holds voucher and clears radcheck/radreply when cap is reached
        lifecycle.processVoucherLifecycle();
        assertTrue(vouchers.findByCode(code).orElseThrow().getSchoolHold());
        assertFalse(radCheck.existsByUsername(code));

        // Portal blocks re-sign-in today with a clear daily cap message
        mvc.perform(post("/api/portal/school/verify").contentType(MediaType.APPLICATION_JSON).content(verifyBody(school, PHONE_MAC)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("daily free data limit")));

        // Pausing staff WiFi blocks staff sign-in
        mvc.perform(put("/api/school/settings").header("Authorization", "Bearer " + school.jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"staffEnabled\":false,\"staffDailyDataCapMb\":0,\"applyImmediately\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffEnabled").value(false));
        verified(school.tenant().getId(), "OK", "STAFF", "staff-cap-2");
        mvc.perform(post("/api/portal/school/verify").contentType(MediaType.APPLICATION_JSON).content(verifyBody(school, PHONE_MAC)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("paused")));
    }

    @Test
    void staffAndStudentsAutoReconnectByMacWithoutReEnteringIdAndPin() throws Exception {
        Site school = site("resume-school", true);

        // Unknown MAC gets SIGN_IN without error
        String initial = portal("resume", "{\"token\":\"" + school.routerToken() + "\",\"mac\":\"" + PHONE_MAC + "\"}");
        assertEquals("SIGN_IN", JsonPath.read(initial, "$.action"));

        // 1. Staff member signs in once -> voucher is valid for the full 1-year period (not 24h)
        verified(school.tenant().getId(), "OK", "STAFF", "staff-resume-1");
        String staffCode = JsonPath.read(portal("verify", verifyBody(school, PHONE_MAC)), "$.code");
        Voucher staffVoucher = vouchers.findByCode(staffCode).orElseThrow();
        var staffPlan = plans.findFirstByTenantIdAndAvailabilityAndName(school.tenant().getId(), "SCHOOL", "School staff access").orElseThrow();
        assertEquals(0, (int) staffPlan.getSessionTimeout(), "staff vouchers do not expire every 24h");
        assertTrue(staffVoucher.getExpiresAt().isAfter(OffsetDateTime.now().plusDays(300)), "staff voucher lasts until accessUntil (1 year)");

        // Clear Mockito invocations to prove resume does NOT call identity.verify again
        clearInvocations(identity);
        String staffResume = portal("resume", "{\"token\":\"" + school.routerToken() + "\",\"mac\":\"" + PHONE_MAC + "\"}");
        assertEquals("CONNECT", JsonPath.read(staffResume, "$.action"));
        assertEquals(staffCode, JsonPath.read(staffResume, "$.code"));
        assertEquals("STAFF", JsonPath.read(staffResume, "$.category"));
        verifyNoInteractions(identity);

        // 2. Student signs in once and purchases a package on TABLET_MAC -> auto-reconnects while voucher has remaining time
        verified(school.tenant().getId(), "OK", "STUDENT", "student-resume-1");
        String ticket = JsonPath.read(portal("verify", verifyBody(school, TABLET_MAC)), "$.ticket");
        String reference = JsonPath.read(pay(school, TABLET_MAC, ticket), "$.reference");
        mvc.perform(post("/api/payments/webhook").header("X-Webhook-Secret", "school-test-webhook-secret").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"" + reference + "\",\"status\":\"SUCCESSFUL\"}")).andExpect(status().isOk());
        String studentCode = JsonPath.read(mvc.perform(get("/api/payments/status/" + reference)).andReturn().getResponse().getContentAsString(), "$.voucherCode");

        clearInvocations(identity);
        String studentResume = portal("resume", "{\"token\":\"" + school.routerToken() + "\",\"mac\":\"" + TABLET_MAC + "\"}");
        assertEquals("CONNECT", JsonPath.read(studentResume, "$.action"));
        assertEquals(studentCode, JsonPath.read(studentResume, "$.code"));
        assertEquals("STUDENT", JsonPath.read(studentResume, "$.category"));
        verifyNoInteractions(identity);

        // If IT suspends/disconnects the student, auto-resume stops and requires fresh ID + PIN
        String body = "{\"tenantId\":" + school.tenant().getId() + ",\"personId\":\"student-resume-1\"}";
        long now = Instant.now().getEpochSecond();
        mvc.perform(signedDisconnect(body, SECRET, now, UUID.randomUUID().toString())).andExpect(status().isOk());
        String afterSuspend = portal("resume", "{\"token\":\"" + school.routerToken() + "\",\"mac\":\"" + TABLET_MAC + "\"}");
        assertEquals("SIGN_IN", JsonPath.read(afterSuspend, "$.action"));

        // 3. School settings expose and update anti-tethering / hotspot blocking
        mvc.perform(put("/api/school/settings").header("Authorization", "Bearer " + school.jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tetheringProtection\":\"BLOCK\",\"applyImmediately\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tetheringProtection").value("BLOCK"));
        assertEquals("BLOCK", tenants.findById(school.tenant().getId()).orElseThrow().getTetheringProtection());
    }

    @Test
    void studentDailyFreeDataAllowsStudySitesAndUpgradesToPaidPackageCleanly() throws Exception {
        Site school = site("student-free-school", true);
        when(identity.updateSchool(eq(school.tenant().getId()), anyMap()))
                .thenReturn(json.createObjectNode().put("visitorsEnabled", false).put("visitorDailyCap", 20));

        mvc.perform(put("/api/school/settings").header("Authorization", "Bearer " + school.jwt()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentFreeEnabled\":true,\"studentDailyFreeMb\":200,\"studentFreeRateLimit\":\"2M/2M\",\"studentAllowedSites\":\"elearning.school.ac.ug, @wikipedia\",\"staffSiteMode\":\"ALLOW_LIST\",\"staffSiteRules\":\"@whatsapp, @youtube, @chatgpt, @tiktok\",\"applyImmediately\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentFreeEnabled").value(true))
                .andExpect(jsonPath("$.studentDailyFreeMb").value(200))
                .andExpect(jsonPath("$.studentFreeRateLimit").value("2M/2M"))
                .andExpect(jsonPath("$.studentAllowedSites").value("elearning.school.ac.ug, @wikipedia"))
                .andExpect(jsonPath("$.staffSiteMode").value("ALLOW_LIST"));

        // Student signs in without a paid package -> portal returns BUY with studentFreeAvailable=true & 200 MB remaining
        verified(school.tenant().getId(), "OK", "STUDENT", "student-free-1");
        String verifyResp = portal("verify", verifyBody(school, PHONE_MAC));
        assertEquals("BUY", JsonPath.read(verifyResp, "$.action"));
        assertEquals(true, JsonPath.read(verifyResp, "$.studentFreeAvailable"));
        assertEquals(200, (int) JsonPath.read(verifyResp, "$.studentFreeDailyCapMb"));
        assertEquals(200, (int) JsonPath.read(verifyResp, "$.studentFreeRemainingMb"));
        String ticket = JsonPath.read(verifyResp, "$.ticket");

        // Student chooses to connect using their free 200 MB daily study data
        String freeResp = portal("student-free", "{\"token\":\"" + school.routerToken() + "\",\"ticket\":\"" + ticket + "\",\"mac\":\"" + PHONE_MAC + "\"}");
        assertEquals("CONNECT", JsonPath.read(freeResp, "$.action"));
        String freeCode = JsonPath.read(freeResp, "$.code");
        Voucher freeVoucher = vouchers.findByCode(freeCode).orElseThrow();
        assertEquals("SCHOOL_STUDENT_FREE", freeVoucher.getSaleType());
        assertEquals("wb-student-free",
                radReply.findFirstByUsernameAndAttribute(freeCode, "Mikrotik-Address-List").orElseThrow().getValue(),
                "student free session is placed in the wb-student-free firewall address list");
        assertEquals(String.valueOf(200L * 1024L * 1024L),
                radReply.findFirstByUsernameAndAttribute(freeCode, "Mikrotik-Total-Limit").orElseThrow().getValue());
        assertEquals("2M/2M",
                radReply.findFirstByUsernameAndAttribute(freeCode, "Mikrotik-Rate-Limit").orElseThrow().getValue());

        // Student later buys a paid full-internet package -> restricted free voucher is held & paid voucher has NO address-list filter
        verified(school.tenant().getId(), "OK", "STUDENT", "student-free-1");
        String buyTicket = JsonPath.read(portal("verify", verifyBody(school, PHONE_MAC)), "$.ticket");
        String reference = JsonPath.read(pay(school, PHONE_MAC, buyTicket), "$.reference");
        mvc.perform(post("/api/payments/webhook").header("X-Webhook-Secret", "school-test-webhook-secret").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"" + reference + "\",\"status\":\"SUCCESSFUL\"}")).andExpect(status().isOk());
        String paidCode = JsonPath.read(mvc.perform(get("/api/payments/status/" + reference)).andReturn().getResponse().getContentAsString(), "$.voucherCode");

        assertTrue(vouchers.findByCode(freeCode).orElseThrow().getSchoolHold(), "free study voucher is held once student upgrades to paid");
        assertFalse(radCheck.existsByUsername(freeCode));
        assertTrue(radReply.findFirstByUsernameAndAttribute(paidCode, "Mikrotik-Address-List").isEmpty(),
                "paid student package has full unrestricted internet");

        // Staff member gets wb-staff-free address list so staff site filter rules apply
        verified(school.tenant().getId(), "OK", "STAFF", "staff-filter-1");
        String staffCode = JsonPath.read(portal("verify", verifyBody(school, TABLET_MAC)), "$.code");
        assertEquals("wb-staff-free",
                radReply.findFirstByUsernameAndAttribute(staffCode, "Mikrotik-Address-List").orElseThrow().getValue());
    }

    @Autowired com.wifibilling.service.RouterManagementService routerManagement;
    @Autowired RouterTaskRepository routerTasks;
    @Autowired RouterRepository routerRepository;

    @Test
    void routersWithOutdatedSchoolRulesAreResentOnStartupExactlyOnce() throws Exception {
        Site school = site("resync-school", true);
        var router = routerRepository.findByToken(school.routerToken()).orElseThrow();
        routerTasks.save(com.wifibilling.model.RouterTask.builder().router(router).action("SYNC_SCHOOL_FILTER")
                .script("/ip/firewall/filter/add comment=\"wb-school-filter: rules from an older release\"").status("APPLIED").build());
        int before = routerTasks.findAllByRouterIdOrderByCreatedAtDesc(router.getId()).size();
        assertTrue(routerManagement.resyncChangedRules() >= 1);
        var latest = routerTasks.findAllByRouterIdOrderByCreatedAtDesc(router.getId()).getFirst();
        assertEquals("SYNC_SCHOOL_FILTER", latest.getAction());
        assertEquals("PENDING", latest.getStatus());
        assertTrue(latest.getScript().contains("/ip/firewall/filter/move [find where comment~\"^wb-school-filter\"]"));
        assertEquals(before + 1, routerTasks.findAllByRouterIdOrderByCreatedAtDesc(router.getId()).size());
        routerManagement.resyncChangedRules();
        assertEquals(before + 1, routerTasks.findAllByRouterIdOrderByCreatedAtDesc(router.getId()).size(), "an up-to-date router gets nothing new");
    }
}
