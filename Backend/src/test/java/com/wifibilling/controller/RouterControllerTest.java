package com.wifibilling.controller;

import com.wifibilling.dto.RouterDto;
import com.wifibilling.model.Router;
import com.wifibilling.model.RouterSetupCode;
import com.wifibilling.service.RouterSetupService;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.*;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.WireGuardIpAllocator;
import com.wifibilling.service.WireGuardPeerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression guard: router list/detail responses must never carry credentials.
 * Values below are obviously fake markers; assertions check field names and
 * that the markers never appear in serialized JSON.
 */
@ExtendWith(MockitoExtension.class)
class RouterControllerTest {

    private static final String FAKE_TOKEN = "tok_TEST_MARKER_not_a_real_token";
    private static final String FAKE_RADIUS = "RADIUS_TEST_MARKER";
    private static final String FAKE_WG_KEY = "WGKEY_TEST_MARKER=";
    private static final List<String> FORBIDDEN_FIELDS = List.of("token", "wgPublicKey", "radiusSecret", "wgPrivateKey", "password");

    @Mock private RouterRepository routerRepository;
    @Mock private IpBindingRepository bindingRepository;
    @Mock private RouterTaskRepository taskRepository;
    @Mock private WireGuardIpAllocator ipAllocator;
    @Mock private TenantRepository tenantRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private NasRepository nasRepository;
    @Mock private WireGuardPeerService wireGuardPeerService;
    @Mock private RouterSetupService setupService;
    @Mock private RouterSetupCodeRepository setupCodeRepository;
    @InjectMocks private RouterController controller;

    private final JsonMapper json = JsonMapper.builder().build();
    private final TenantPrincipal owner = new TenantPrincipal(7L, "owner@example.test", "Owner", "ROLE_ADMIN", false);
    private Router router;

    @BeforeEach
    void setUp() {
        Tenant tenant = Tenant.builder().id(7L).name("Test Tenant").build();
        router = Router.builder()
                .id(42L).tenant(tenant).name("Test Router")
                .token(FAKE_TOKEN).radiusSecret(FAKE_RADIUS).wgPublicKey(FAKE_WG_KEY)
                .wgIp("10.9.0.42").status("online").lastSeen(OffsetDateTime.now())
                .build();
    }

    private void assertNoSecrets(Object response) {
        String body = json.writeValueAsString(response);
        assertFalse(body.contains(FAKE_TOKEN), "provisioning token leaked");
        assertFalse(body.contains(FAKE_RADIUS), "RADIUS secret leaked");
        assertFalse(body.contains(FAKE_WG_KEY), "WireGuard key leaked");
        JsonNode node = json.readTree(body);
        for (JsonNode item : node.isArray() ? node : List.of(node)) {
            for (String field : FORBIDDEN_FIELDS) {
                assertFalse(item.has(field), "response exposes field '" + field + "'");
            }
        }
    }

    @Test
    void listNeverExposesCredentials() {
        when(routerRepository.findAllByTenantId(7L)).thenReturn(List.of(router));
        List<RouterDto.Response> list = controller.list(owner);
        assertEquals(1, list.size());
        assertTrue(list.get(0).registered(), "registration state still reported without the key");
        assertNoSecrets(list);
    }

    @Test
    void detailNeverExposesCredentials() {
        when(routerRepository.findById(42L)).thenReturn(Optional.of(router));
        assertNoSecrets(controller.get(owner, 42L));
    }

    @Test
    void detailIsTenantScoped() {
        when(routerRepository.findById(42L)).thenReturn(Optional.of(router));
        TenantPrincipal other = new TenantPrincipal(8L, "other@example.test", "Other", "ROLE_ADMIN", false);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.get(other, 42L));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void setupCodeIsIssuedOnlyForTheOwnersRouterAndCarriesNoSecrets() {
        when(routerRepository.findByIdAndTenantId(42L, 7L)).thenReturn(Optional.of(router));
        when(setupService.issue(router, RouterSetupCode.Purpose.FULL))
                .thenReturn(new RouterSetupService.Issued("su_TESTissuedCodeMarker000000000000", OffsetDateTime.now().plusMinutes(30)));
        RouterDto.SetupCode code = controller.setupCode(owner, 42L, null);
        assertEquals("FULL", code.purpose());
        assertTrue(code.path().startsWith("/api/router/setup/su_"));
        assertNoSecrets(code);
    }

    @Test
    void managementSetupCodePointsAtTheInstallerOnly() {
        when(routerRepository.findByIdAndTenantId(42L, 7L)).thenReturn(Optional.of(router));
        when(setupService.issue(router, RouterSetupCode.Purpose.MANAGEMENT))
                .thenReturn(new RouterSetupService.Issued("su_TESTissuedCodeMarker000000000000", OffsetDateTime.now().plusMinutes(30)));
        RouterDto.SetupCode code = controller.setupCode(owner, 42L, new RouterDto.SetupCodeRequest("MANAGEMENT"));
        assertTrue(code.path().endsWith("/install-management.rsc"));
    }

    @Test
    void anotherBusinessCannotIssueSetupCodesOrReadThePortalId() {
        when(routerRepository.findByIdAndTenantId(42L, 8L)).thenReturn(Optional.empty());
        TenantPrincipal other = new TenantPrincipal(8L, "other@example.test", "Other", "ROLE_ADMIN", false);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> controller.setupCode(other, 42L, null)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> controller.portalId(other, 42L)).getStatusCode());
        verifyNoInteractions(setupService);
    }

    @Test
    void unregisteredRouterReportsNotRegistered() {
        router.setWgPublicKey(null);
        when(routerRepository.findAllByTenantId(7L)).thenReturn(List.of(router));
        assertFalse(controller.list(owner).get(0).registered());
    }
}
