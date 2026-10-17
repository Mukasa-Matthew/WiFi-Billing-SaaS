package world.edubridge.wifibilling;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthAndTenantIsolationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("1. Health check endpoint returns UP and CONNECTED database status")
    void healthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", is("UP")))
            .andExpect(jsonPath("$.database", is("CONNECTED")));
    }

    @Test
    @DisplayName("2. Unauthenticated requests to protected endpoints return 401 Unauthorized")
    void unauthenticatedRequestsReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/super-admin/overview"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/business/dashboard"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("3. Public Dynamic Captive Portal endpoint serves tenant-specific branding and active packages")
    void publicCaptivePortalServesDynamicTenantData() throws Exception {
        mockMvc.perform(get("/api/v1/public/portal/config").param("tenantSlug", "default-hotspot"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.branding.tenantSlug", is("default-hotspot")))
            .andExpect(jsonPath("$.branding.currencyCode", is("UGX")))
            .andExpect(jsonPath("$.packages", hasSize(greaterThanOrEqualTo(2))));

        mockMvc.perform(get("/api/v1/public/portal/config").param("tenantSlug", "tenant-2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.branding.tenantSlug", is("tenant-2")))
            .andExpect(jsonPath("$.packages", hasSize(greaterThanOrEqualTo(1))))
            .andExpect(jsonPath("$.packages[0].name", is("Savannah 2-Hour Coffee Pass")));
    }

    @Test
    @DisplayName("4. Super Admin can authenticate, inspect /me, and access Super Admin overview")
    void superAdminAuthenticationAndAccess() throws Exception {
        MockHttpSession session = loginAndGetSession("superadmin@edubridge.world", "Admin@2026!");

        mockMvc.perform(get("/api/v1/auth/me").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role", is("super_admin")))
            .andExpect(jsonPath("$.superAdmin", is(true)));

        mockMvc.perform(get("/api/v1/super-admin/overview").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalTenants", greaterThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("5. Business Admin is strictly isolated to their own tenant and cannot spoof headers or access Super Admin")
    void businessAdminTenantAndRoleIsolation() throws Exception {
        MockHttpSession tenant1AdminSession = loginAndGetSession("admin@default-hotspot.local", "Admin@2026!");

        // Can access own tenant (Tenant 1)
        mockMvc.perform(get("/api/v1/business/dashboard").session(tenant1AdminSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tenant.id", is(1)))
            .andExpect(jsonPath("$.tenant.slug", is("default-hotspot")));

        // Cannot access Super Admin endpoints even if spoofing X-User-Role header
        mockMvc.perform(get("/api/v1/super-admin/overview")
                .session(tenant1AdminSession)
                .header("X-User-Role", "super_admin"))
            .andExpect(status().isForbidden());

        // Cannot access Tenant 2 data via query param or spoofed X-Tenant-Id header
        mockMvc.perform(get("/api/v1/business/dashboard")
                .session(tenant1AdminSession)
                .param("tenantId", "2")
                .header("X-Tenant-Id", "2"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("6. Business Admin updating Captive Portal settings immediately updates the public customer portal")
    void dynamicCaptivePortalLiveUpdate() throws Exception {
        MockHttpSession tenant1AdminSession = loginAndGetSession("admin@default-hotspot.local", "Admin@2026!");

        Map<String, Object> updatePayload = Map.ofEntries(
            Map.entry("businessName", "Default Hotspot Uganda"),
            Map.entry("logoUrl", "https://portal.edubridge.world/assets/logo.svg"),
            Map.entry("primaryColor", "#0284c7"),
            Map.entry("secondaryColor", "#0f172a"),
            Map.entry("whatsappSupportNumber", "+256701234567"),
            Map.entry("supportPhoneNumber", "+256701234567"),
            Map.entry("portalWelcomeTitle", "Connect to Kampala Express High-Speed WiFi"),
            Map.entry("portalWelcomeMessage", "Instant Mobile Money & Voucher activation"),
            Map.entry("promotionalBannerText", "Weekend Promo: 24 Hours Unlimited for UGX 2,000!"),
            Map.entry("enableMtnMomo", true),
            Map.entry("enableAirtelMoney", true),
            Map.entry("enableVoucherCode", true)
        );

        mockMvc.perform(put("/api/v1/business/portal-settings")
                .session(tenant1AdminSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updatePayload)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.portalWelcomeTitle", is("Connect to Kampala Express High-Speed WiFi")));

        // Verify the public captive portal reflects the change immediately without router reconfiguration
        mockMvc.perform(get("/api/v1/public/portal/config").param("tenantSlug", "default-hotspot"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.branding.welcomeTitle", is("Connect to Kampala Express High-Speed WiFi")))
            .andExpect(jsonPath("$.branding.primaryColor", is("#0284c7")))
            .andExpect(jsonPath("$.branding.whatsappSupportNumber", is("+256701234567")))
            .andExpect(jsonPath("$.branding.promotionalBannerText", is("Weekend Promo: 24 Hours Unlimited for UGX 2,000!")));
    }

    private MockHttpSession loginAndGetSession(String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "email", email,
            "password", password
        ));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
