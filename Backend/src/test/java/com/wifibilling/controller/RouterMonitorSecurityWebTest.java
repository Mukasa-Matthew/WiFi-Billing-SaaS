package com.wifibilling.controller;

import com.wifibilling.config.SecurityConfig;
import com.wifibilling.model.AuditLog;
import com.wifibilling.model.Router;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.*;
import com.wifibilling.security.JwtAuthenticationFilter;
import com.wifibilling.security.JwtService;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Router monitoring and remote actions over HTTP, through the real security
 * filter chain and the real RouterOperationsService. NocService and all
 * repositories are mocks; no database, network or router is touched.
 */
@WebMvcTest(controllers = {RouterMonitorController.class, AdminNocController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RouterOperationsService.class})
class RouterMonitorSecurityWebTest {

    static final String SECRET = "RADIUS_SECRET_MARKER";

    @Autowired MockMvc mvc;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserSessionRepository userSessionRepository;
    @MockitoBean TenantRepository tenantRepository;
    @MockitoBean NocService noc;
    @MockitoBean RouterRepository routers;
    @MockitoBean RadAcctRepository accounting;
    @MockitoBean VoucherRepository vouchers;
    @MockitoBean RouterTaskRepository tasks;
    @MockitoBean RouterManagementService management;
    @MockitoBean RadiusDisconnectService radius;
    @MockitoBean AuditLogRepository audit;

    Router router;

    @BeforeEach
    void setUp() {
        router = Router.builder().id(42L).tenant(Tenant.builder().id(7L).name("Seven").build()).name("Main")
                .wgIp("10.9.0.42").radiusSecret(SECRET).status("online").build();
        when(routers.findById(42L)).thenReturn(Optional.of(router));
        when(routers.findByIdAndTenantId(42L, 7L)).thenReturn(Optional.of(router));
        when(routers.findByIdAndTenantId(42L, 8L)).thenReturn(Optional.empty());
        when(noc.live(42L)).thenReturn(Map.of("reachable", true, "source", "LIVE", "hotspotUsers", List.of()));
    }

    private static UsernamePasswordAuthenticationToken as(long tenantId, String role) {
        TenantPrincipal p = new TenantPrincipal(tenantId, "user" + tenantId + "@example.test", "User", role, false);
        return new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/routers/42/monitor/live")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/noc/routers/42/live")).andExpect(status().isUnauthorized());
        verifyNoInteractions(noc);
    }

    @Test
    void ownerSeesOwnRouterAndNoSecretIsInTheResponse() throws Exception {
        mvc.perform(get("/api/routers/42/monitor/live").with(authentication(as(7, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(SECRET))))
                .andExpect(content().string(containsString("\"source\":\"LIVE\"")));
        verify(noc).live(42L);
    }

    @Test
    void otherTenantGetsA404ForEveryMonitorViewAndAction() throws Exception {
        for (String path : List.of("", "/live", "/sessions", "/accounting", "/auth-events", "/diagnostics", "/tasks", "/hosts", "/events", "/incidents", "/usage")) {
            mvc.perform(get("/api/routers/42/monitor" + path).with(authentication(as(8, "ROLE_ADMIN")))).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/routers/42/monitor/disconnect").with(authentication(as(8, "ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ABC123\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/routers/42/monitor/disconnect-status").param("username", "ABC123").with(authentication(as(8, "ROLE_ADMIN"))))
                .andExpect(status().isNotFound());
        verifyNoInteractions(noc, management, radius);
    }

    @Test
    void missingOrMalformedRouterIdsAreRejected() throws Exception {
        mvc.perform(get("/api/routers/999/monitor/live").with(authentication(as(7, "ROLE_ADMIN")))).andExpect(status().isNotFound());
        mvc.perform(get("/api/routers/abc/monitor/live").with(authentication(as(7, "ROLE_ADMIN")))).andExpect(status().isBadRequest());
        verifyNoInteractions(noc);
    }

    @Test
    void businessAdminCannotUseThePlatformNoc() throws Exception {
        mvc.perform(get("/api/admin/noc/routers/42/live").with(authentication(as(7, "ROLE_ADMIN")))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/noc/routers/42/disconnect").with(authentication(as(7, "ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ABC123\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(noc, management, radius);
    }

    @Test
    void superadminReachesAnyTenantsRouterAndTheViewIsAudited() throws Exception {
        mvc.perform(get("/api/admin/noc/routers/42/live").with(authentication(as(1, "ROLE_SUPERADMIN")))).andExpect(status().isOk());
        verify(noc).live(42L);
        verify(audit).save(argThat((AuditLog a) -> "NOC_ROUTER_VIEWED".equals(a.getAction()) && Long.valueOf(7L).equals(a.getTenantId())));
    }

    @Test
    void disconnectRejectsMalformedUsernamesBeforeTouchingTheRouter() throws Exception {
        mvc.perform(post("/api/routers/42/monitor/disconnect").with(authentication(as(7, "ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"x\\\";/system reboot\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/routers/42/monitor/disconnect").with(authentication(as(7, "ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(management, radius);
    }
}
