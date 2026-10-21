package com.wifibilling.controller;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.dto.PortalPackageResponse;
import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Router;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.PackagePlanRepository;
import com.wifibilling.repository.RouterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PortalControllerTest {

    @Mock
    private RouterRepository routerRepository;

    @Mock
    private PackagePlanRepository packagePlanRepository;

    @Mock
    private PlatformProperties platformProperties;

    private PortalController controller;

    @BeforeEach
    void setUp() {
        controller = new PortalController(routerRepository, packagePlanRepository, platformProperties);
    }

    @Test
    void getPackages_ReturnsOnlyTenantPackages() {
        Tenant tenantA = Tenant.builder().id(10L).name("Tenant A").build();
        Router routerA = Router.builder().id(100L).token("tok_A1234567").tenant(tenantA).build();

        PackagePlan plan1 = PackagePlan.builder().id(1L).tenant(tenantA).name("1 Hour Fast").price(new BigDecimal("1000")).rateLimit("10M/10M").sessionTimeout(3600).build();
        PackagePlan plan2 = PackagePlan.builder().id(2L).tenant(tenantA).name("24 Hours Unlimited").price(new BigDecimal("5000")).rateLimit("5M/5M").sessionTimeout(86400).build();

        when(routerRepository.findByToken("tok_A1234567")).thenReturn(Optional.of(routerA));
        when(packagePlanRepository.findAllByTenantIdOrderByPriceAsc(10L)).thenReturn(List.of(plan1, plan2));

        List<PortalPackageResponse> result = controller.getPackages("tok_A1234567");
        assertEquals(2, result.size());
        assertEquals("1 Hour Fast", result.get(0).name());
        assertEquals(new BigDecimal("1000"), result.get(0).price());
        assertEquals("24 Hours Unlimited", result.get(1).name());

        verify(packagePlanRepository).findAllByTenantIdOrderByPriceAsc(10L);
    }

    @Test
    void portalExcludesHiddenAndDisabledAndPrioritizesFeatured() {
        Tenant tenant = Tenant.builder().id(10L).build();
        when(routerRepository.findByToken("tok_A1234567")).thenReturn(Optional.of(
                Router.builder().tenant(tenant).build()));
        PackagePlan normal = PackagePlan.builder().id(1L).price(BigDecimal.ONE).build();
        PackagePlan hidden = PackagePlan.builder().id(2L).price(BigDecimal.ONE).availability("HIDDEN").build();
        PackagePlan disabled = PackagePlan.builder().id(3L).price(BigDecimal.ONE).availability("DISABLED").build();
        PackagePlan featured = PackagePlan.builder().id(4L).price(BigDecimal.TEN).featured(true).build();
        when(packagePlanRepository.findAllByTenantIdOrderByPriceAsc(10L))
                .thenReturn(List.of(normal, hidden, disabled, featured));
        var result = controller.getPackages("tok_A1234567");
        assertEquals(List.of(4L, 1L), result.stream().map(PortalPackageResponse::id).toList());
        assertTrue(result.getFirst().featured());
    }

    @Test
    void getPackages_ThrowsNotFoundForInvalidOrMissingToken() {
        when(routerRepository.findByToken("tok_INVALID123")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> {
            controller.getPackages("tok_INVALID123");
        });
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void getTemplate_SubstitutesPlaceholdersInLoginHtml() {
        Tenant tenantA = Tenant.builder().id(10L).build();
        Router routerA = Router.builder().id(100L).token("tok_A1234567").tenant(tenantA).build();

        when(routerRepository.findByToken("tok_A1234567")).thenReturn(Optional.of(routerA));
        when(platformProperties.getPublicBaseUrl()).thenReturn("http://cloudserv.example.com:8080");

        ResponseEntity<String> response = controller.getTemplate("login.html", "tok_A1234567");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        assertTrue(response.getBody().contains("var ROUTER_TOKEN = 'tok_A1234567';"));
        assertTrue(response.getBody().contains("var SERVER_BASE_URL = 'http://cloudserv.example.com:8080';"));
        assertFalse(response.getBody().contains("ROUTER_TOKEN_PLACEHOLDER"));
        assertFalse(response.getBody().contains("SERVER_BASE_URL_PLACEHOLDER"));
    }
}
