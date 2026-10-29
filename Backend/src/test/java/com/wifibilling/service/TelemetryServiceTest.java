package com.wifibilling.service;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.dto.TelemetryRequest;
import com.wifibilling.model.Router;
import com.wifibilling.repository.RouterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelemetryServiceTest {

    @Mock RouterRepository routers;
    @Mock PlatformProperties properties;
    @Mock EmailService emailService;
    @Mock EmailTemplates emailTemplates;
    private TelemetryService service;
    private Router router;

    @BeforeEach
    void setUp() {
        service = new TelemetryService(routers, properties, emailService, emailTemplates);
        router = Router.builder().token("tok_valid123456").build();
        org.mockito.Mockito.lenient().when(routers.findByToken("tok_valid123456")).thenReturn(Optional.of(router));
    }

    @Test
    void marksNewAgentOnlineOnlyWhenAllReadinessChecksPass() {
        service.record(report(true, true, true));
        assertEquals(TelemetryService.STATUS_ONLINE, router.getStatus());

        service.record(report(true, false, true));
        assertEquals(TelemetryService.STATUS_DEGRADED, router.getStatus());
    }

    @Test
    void keepsLegacyTelemetryBackwardCompatible() {
        service.record(report(null, null, null));
        assertEquals(TelemetryService.STATUS_ONLINE, router.getStatus());
    }

    private TelemetryRequest report(Boolean hotspot, Boolean radius, Boolean portal) {
        return new TelemetryRequest("tok_valid123456", 10, 80L, 100L, "1h", 2,
                hotspot, radius, portal);
    }
    @Test
    void notifiesWhenRouterTransitionsFromOfflineToOnline() {
        com.wifibilling.model.Tenant tenant = com.wifibilling.model.Tenant.builder()
                .email("owner@example.com")
                .notifyRouterOffline(true)
                .notifyEmails("ops@example.com")
                .build();
        router.setTenant(tenant);
        router.setStatus(TelemetryService.STATUS_OFFLINE);
        router.setOfflineNotifiedAt(java.time.OffsetDateTime.now());

        org.mockito.Mockito.when(emailTemplates.shell(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("<html>mock</html>");

        service.record(report(true, true, true));

        assertEquals(TelemetryService.STATUS_ONLINE, router.getStatus());
        org.junit.jupiter.api.Assertions.assertNull(router.getOfflineNotifiedAt());
        org.mockito.Mockito.verify(emailService, org.mockito.Mockito.atLeastOnce())
                .send(org.mockito.ArgumentMatchers.eq("owner@example.com"),
                      org.mockito.ArgumentMatchers.contains("Back Online"),
                      org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void sendsOfflineEmailWhenRouterSwept() {
        com.wifibilling.model.Tenant tenant = com.wifibilling.model.Tenant.builder()
                .email("owner@example.com")
                .notifyRouterOffline(true)
                .build();
        router.setTenant(tenant);
        router.setStatus(TelemetryService.STATUS_ONLINE);

        when(properties.getOfflineAfterMinutes()).thenReturn(6L);
        when(routers.findStaleRouters(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of(router));
        when(routers.markOfflineSeenBefore(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(Integer.valueOf(1));
        when(emailTemplates.shell(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("<html>mock</html>");

        service.doMarkStaleRoutersOffline();

        org.mockito.Mockito.verify(emailService, org.mockito.Mockito.atLeastOnce())
                .send(org.mockito.ArgumentMatchers.eq("owner@example.com"),
                      org.mockito.ArgumentMatchers.contains("is Offline"),
                      org.mockito.ArgumentMatchers.anyString());
    }
}