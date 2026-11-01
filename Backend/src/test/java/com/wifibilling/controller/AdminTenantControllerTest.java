package com.wifibilling.controller;

import com.wifibilling.dto.AdminTenantDto;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.Router;
import com.wifibilling.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.wifibilling.service.EmailService;
import com.wifibilling.service.EmailTemplates;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminTenantControllerTest {

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private RouterRepository routerRepository;
    @Mock
    private VoucherRepository voucherRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private RadAcctRepository radAcctRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private EmailService emailService;
    @Mock
    private EmailTemplates emailTemplates;
    @Mock
    private com.wifibilling.service.SubscriptionService subscriptionService;

    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private com.wifibilling.service.GatewayConfigService gatewayConfig;

    private AdminTenantController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminTenantController(
                tenantRepository,
                routerRepository,
                voucherRepository,
                transactionRepository,
                radAcctRepository,
                passwordEncoder,
                emailService,
                emailTemplates,
                subscriptionService,
                mock(com.wifibilling.service.WinboxAccessService.class),
                auditLogRepository,
                gatewayConfig
        );
    }

    @Test
    void createTenant_Success() {
        when(tenantRepository.existsByEmailIgnoreCase("newtenant@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed_secret");
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> {
            Tenant t = i.getArgument(0);
            t.setId(10L);
            return t;
        });

        AdminTenantDto.CreateRequest req = new AdminTenantDto.CreateRequest(
                "New Cafe", "newtenant@example.com", "+256770000000"
        );
        AdminTenantDto.CreateResponse created = controller.createTenant(req);
        AdminTenantDto.SummaryResponse res = created.tenant();

        assertNotNull(res);
        assertEquals(10L, res.id());
        assertEquals("New Cafe", res.name());
        assertEquals("ROLE_ADMIN", res.role());
        assertEquals("ACTIVE", res.status());
        assertNotNull(created.temporaryPassword());
        assertFalse(created.welcomeEmailSent());
        verify(tenantRepository).save(argThat(t -> Boolean.TRUE.equals(t.getMustChangePassword())));
    }

    @Test
    void createTenant_DuplicateEmailThrowsConflict() {
        when(tenantRepository.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);

        AdminTenantDto.CreateRequest req = new AdminTenantDto.CreateRequest(
                "Dup Cafe", "taken@example.com", "+256770000000"
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.createTenant(req));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void updateTenantStatus_ModifiesStatus() {
        Tenant tenant = Tenant.builder().id(5L).name("Cafe").role("ROLE_ADMIN").status("ACTIVE").build();
        when(tenantRepository.findById(5L)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> i.getArgument(0));
        when(routerRepository.findAllByTenantId(5L)).thenReturn(Collections.emptyList());

        AdminTenantDto.SummaryResponse res = controller.updateTenantStatus(5L, new AdminTenantDto.UpdateStatusRequest("SUSPENDED"));
        assertEquals("SUSPENDED", res.status());
    }

    @Test
    void listRouters_ReturnsOperationalFieldsWithoutSecrets() {
        Tenant tenant = Tenant.builder().id(5L).name("Cafe").role("ROLE_ADMIN").build();
        OffsetDateTime seen = OffsetDateTime.now();
        Router router = Router.builder()
                .id(9L).tenant(tenant).name("Main router").status("online")
                .wgIp("10.9.0.9").token("secret-token").radiusSecret("radius-secret")
                .cpuLoad(12).memoryUsage(34).activeUsers(7).lastSeen(seen)
                .hotspotReady(true).radiusReady(true).portalReady(false)
                .build();
        when(routerRepository.findAll()).thenReturn(List.of(router));

        var rows = controller.listRouters();

        assertEquals(1, rows.size());
        assertEquals("Cafe", rows.getFirst().tenantName());
        assertEquals("10.9.0.9", rows.getFirst().managementIp());
        assertEquals(7, rows.getFirst().activeUsers());
        assertFalse(rows.getFirst().portalReady());
    }

    // ---------- platform fee ----------

    @Test
    void platformFeeSwitchSavesTheRateAndIsAudited() {
        Tenant t = Tenant.builder().id(7L).name("Campus Connect").role("ROLE_ADMIN").build();
        when(tenantRepository.findById(7L)).thenReturn(Optional.of(t));
        when(gatewayConfig.modeFor(t)).thenReturn("PLATFORM");
        var principal = mock(com.wifibilling.security.TenantPrincipal.class);
        when(principal.getEmail()).thenReturn("admin@example.com");

        var view = controller.updatePlatformFee(7L,
                new AdminTenantController.PlatformFeeUpdate(true, new java.math.BigDecimal("3")), principal);

        assertTrue(view.enabled());
        assertTrue(view.appliesNow());
        assertEquals(300, t.getPlatformFeeBps());
        verify(auditLogRepository).save(argThat(a -> "PLATFORM_FEE_CHANGED".equals(a.getAction())
                && a.getDetails().contains("off (rate 3%) -> on at 3%") && "admin@example.com".equals(a.getActorEmail())));
    }

    @Test
    void platformFeeOnOwnGatewayIsSavedButDoesNotApply() {
        Tenant t = Tenant.builder().id(8L).name("Own Gateway Co").role("ROLE_ADMIN").build();
        when(tenantRepository.findById(8L)).thenReturn(Optional.of(t));
        when(gatewayConfig.modeFor(t)).thenReturn("OWN");

        var view = controller.updatePlatformFee(8L,
                new AdminTenantController.PlatformFeeUpdate(true, new java.math.BigDecimal("2.5")), null);

        assertTrue(view.enabled());
        assertFalse(view.appliesNow());
        assertEquals(250, t.getPlatformFeeBps());
    }

    @Test
    void platformFeeRejectsOutOfRangeRates() {
        for (String bad : new String[]{"0", "25", "1.555"}) {
            var ex = assertThrows(ResponseStatusException.class, () -> controller.updatePlatformFee(9L,
                    new AdminTenantController.PlatformFeeUpdate(true, new java.math.BigDecimal(bad)), null));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }
        verify(tenantRepository, never()).save(any());
    }
}
