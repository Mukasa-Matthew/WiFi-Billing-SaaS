package com.wifibilling.controller;

import com.wifibilling.config.SecurityConfig;
import com.wifibilling.dto.RegisterKeyResponse;
import com.wifibilling.model.Router;
import com.wifibilling.model.RouterSetupCode;
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
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Provisioning security over HTTP, through the real SecurityConfig filter chain
 * and the real RouterSetupService. Repositories and side-effecting services are
 * mocks; no database, no schedulers, no network. Credential values are fake markers.
 */
@WebMvcTest(controllers = {RouterController.class, RouterSetupController.class, ProvisioningController.class, RouterManagementController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RouterSetupService.class})
class ProvisioningSecurityWebTest {

    private static final String PUBLIC_ID = "tok_PUBLICportalIdMarker123";
    private static final String SCRIPT_MARKER = "# SCRIPT_WITH_RADIUS_SECRET_MARKER";

    @Autowired private MockMvc mvc;
    @Autowired private RouterSetupService setupService;

    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserSessionRepository userSessionRepository;
    @MockitoBean private TenantRepository tenantRepository;
    @MockitoBean private RouterRepository routerRepository;
    @MockitoBean private IpBindingRepository bindingRepository;
    @MockitoBean private RouterTaskRepository taskRepository;
    @MockitoBean private WireGuardIpAllocator ipAllocator;
    @MockitoBean private TransactionRepository transactionRepository;
    @MockitoBean private NasRepository nasRepository;
    @MockitoBean private WireGuardPeerService wireGuardPeerService;
    @MockitoBean private RouterSetupCodeRepository setupCodeRepository;
    @MockitoBean private ProvisioningService provisioningService;
    @MockitoBean private RouterManagementService managementService;
    @MockitoBean private SubscriptionService subscriptionService;

    private final Map<Long, RouterSetupCode> store = new HashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final JsonMapper json = JsonMapper.builder().build();
    private Router router;

    @BeforeEach
    void setUp() {
        router = Router.builder().id(42L).tenant(Tenant.builder().id(7L).name("T").build())
                .name("Test Router").token(PUBLIC_ID).radiusSecret("RADIUS_TEST_MARKER").wgIp("10.9.0.42").status("online").build();
        when(routerRepository.findByIdAndTenantId(42L, 7L)).thenReturn(Optional.of(router));
        when(routerRepository.findByIdAndTenantId(42L, 8L)).thenReturn(Optional.empty());
        when(routerRepository.findByToken(PUBLIC_ID)).thenReturn(Optional.of(router));
        // Redemption happens inside these services; delegate to the real setup service
        // so single-use, expiry and purpose rules are exercised over HTTP.
        when(provisioningService.buildScriptForSetup(anyString())).thenAnswer(i -> {
            setupService.redeem(i.getArgument(0), RouterSetupCode.Purpose.FULL);
            return SCRIPT_MARKER;
        });
        when(managementService.installerForSetup(anyString())).thenAnswer(i -> {
            setupService.redeem(i.getArgument(0), RouterSetupCode.Purpose.MANAGEMENT);
            return "# installer";
        });
        when(provisioningService.registerPublicKey(anyLong(), anyString())).thenReturn(new RegisterKeyResponse("registered", "10.9.0.42", true));

        when(setupCodeRepository.save(any())).thenAnswer(i -> {
            RouterSetupCode c = i.getArgument(0);
            if (c.getId() == null) c.setId(ids.incrementAndGet());
            store.put(c.getId(), c);
            return c;
        });
        when(setupCodeRepository.findByCodeHash(anyString())).thenAnswer(i -> store.values().stream()
                .filter(c -> c.getCodeHash().equals(i.getArgument(0))).findFirst());
        when(setupCodeRepository.bindRegisteredKey(anyLong(), anyString())).thenAnswer(i -> {
            RouterSetupCode c = store.get((Long) i.getArgument(0));
            if (c == null || c.getRegisteredKeyHash() != null) return 0;
            c.setRegisteredKeyHash(i.getArgument(1));
            return 1;
        });
        when(setupCodeRepository.markScriptFetched(anyLong(), any())).thenAnswer(i -> {
            RouterSetupCode c = store.get((Long) i.getArgument(0));
            if (c == null || c.getScriptFetchedAt() != null || c.getRevokedAt() != null) return 0;
            c.setScriptFetchedAt(i.getArgument(1));
            return 1;
        });
    }

    private static UsernamePasswordAuthenticationToken as(long tenantId) {
        TenantPrincipal p = new TenantPrincipal(tenantId, "owner" + tenantId + "@example.test", "Owner", "ROLE_ADMIN", false);
        return new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private String issue(String purpose) throws Exception {
        String body = mvc.perform(post("/api/routers/42/setup-code").with(authentication(as(7)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"" + purpose + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("path").asString();
    }

    /* ---- legacy routes: the public id can no longer reach setup ---- */

    @Test
    void legacyScriptRouteIsGoneAndBuildsNothing() throws Exception {
        mvc.perform(get("/api/router/provision/" + PUBLIC_ID))
                .andExpect(status().isGone())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().string(not(containsString("RADIUS"))));
        verify(provisioningService, never()).buildScriptForSetup(any());
    }

    @Test
    void legacyKeyRegistrationAndCompletionAreGone() throws Exception {
        mvc.perform(post("/api/router/provision/" + PUBLIC_ID + "/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicKey\":\"QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=\"}"))
                .andExpect(status().isGone());
        mvc.perform(post("/api/router/provision/" + PUBLIC_ID + "/complete")).andExpect(status().isGone());
        verify(provisioningService, never()).registerPublicKey(anyLong(), any());
        verify(provisioningService, never()).markComplete(anyLong());
    }

    @Test
    void legacyManagementInstallerIsGone() throws Exception {
        mvc.perform(get("/api/router/provision/" + PUBLIC_ID + "/install-management.rsc")).andExpect(status().isGone());
        verify(managementService, never()).installerForSetup(any());
    }

    @Test
    void publicIdIsNotASetupCode() throws Exception {
        mvc.perform(get("/api/router/setup/" + PUBLIC_ID)).andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString(SCRIPT_MARKER))));
        mvc.perform(post("/api/router/setup/" + PUBLIC_ID + "/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"publicKey\":\"QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=\"}")).andExpect(status().isNotFound());
        verify(provisioningService, never()).registerPublicKey(anyLong(), any());
    }

    /* ---- issuing codes ---- */

    @Test
    void anonymousCallersCannotIssueSetupCodesOrReadThePortalId() throws Exception {
        mvc.perform(post("/api/routers/42/setup-code")).andExpect(status().is(anyOf(is(401), is(403))));
        mvc.perform(get("/api/routers/42/portal-id")).andExpect(status().is(anyOf(is(401), is(403))));
        verify(setupCodeRepository, never()).save(any());
    }

    @Test
    void anotherBusinessGetsNotFound() throws Exception {
        mvc.perform(post("/api/routers/42/setup-code").with(authentication(as(8)))).andExpect(status().isNotFound());
        mvc.perform(get("/api/routers/42/portal-id").with(authentication(as(8)))).andExpect(status().isNotFound());
        verify(setupCodeRepository, never()).save(any());
    }

    @Test
    void routerListAndDetailCarryNoSecrets() throws Exception {
        when(routerRepository.findAllByTenantId(7L)).thenReturn(List.of(router));
        when(routerRepository.findById(42L)).thenReturn(Optional.of(router));
        for (String url : List.of("/api/routers", "/api/routers/42")) {
            mvc.perform(get(url).with(authentication(as(7))))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString(PUBLIC_ID))))
                    .andExpect(content().string(not(containsString("RADIUS_TEST_MARKER"))))
                    .andExpect(content().string(not(containsString("\"token\""))));
        }
    }

    /* ---- the authorized workflow still works end to end ---- */

    @Test
    void ownerIssuedCodeProvisionsOnceThenOnlyAuthorizesItsCallbacks() throws Exception {
        String path = issue("FULL");
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().string(SCRIPT_MARKER))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(get(path)).andExpect(status().isNotFound());                 // single use

        String key = "{\"publicKey\":\"QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=\"}";
        mvc.perform(post(path + "/register").contentType(MediaType.APPLICATION_JSON).content(key)).andExpect(status().isOk());
        mvc.perform(post(path + "/register").contentType(MediaType.APPLICATION_JSON).content(key)).andExpect(status().isOk()); // router retries
        mvc.perform(post(path + "/complete")).andExpect(status().isOk());
        mvc.perform(post(path + "/register").contentType(MediaType.APPLICATION_JSON).content(key)).andExpect(status().isNotFound());
        verify(provisioningService, times(2)).buildScriptForSetup(anyString());  // second call was refused inside redeem
        verify(provisioningService, times(2)).registerPublicKey(eq(42L), anyString());
        verify(provisioningService, times(1)).markComplete(42L);
    }

    @Test
    void leakedCodeCannotReplaceTheRegisteredKey() throws Exception {
        String path = issue("FULL");
        mvc.perform(get(path)).andExpect(status().isOk());
        String routerKey = "{\"publicKey\":\"QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=\"}";
        String otherKey = "{\"publicKey\":\"QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=\"}";
        mvc.perform(post(path + "/register").contentType(MediaType.APPLICATION_JSON).content(routerKey)).andExpect(status().isOk());
        mvc.perform(post(path + "/register").contentType(MediaType.APPLICATION_JSON).content(otherKey)).andExpect(status().isNotFound());
        verify(provisioningService, times(1)).registerPublicKey(eq(42L), eq("QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE="));
        verify(provisioningService, never()).registerPublicKey(anyLong(), eq("QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI="));
    }

    @Test
    void managementCodeInstallsTheAgentButNeverReleasesTheCredentialScript() throws Exception {
        String path = issue("MANAGEMENT");
        String code = path.replace("/api/router/setup/", "").replace("/install-management.rsc", "");
        mvc.perform(get("/api/router/setup/" + code)).andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString(SCRIPT_MARKER))));
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string("# installer"));
        mvc.perform(get(path)).andExpect(status().isNotFound());
    }

    @Test
    void recurringRouterRoutesStillWorkForDeployedRouters() throws Exception {
        when(managementService.script(PUBLIC_ID, 0)).thenReturn("# management sync");
        mvc.perform(get("/api/router/provision/" + PUBLIC_ID + "/management.rsc")).andExpect(status().isOk());
    }
}
