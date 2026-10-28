package com.wifibilling.service;

import com.wifibilling.dto.NocDto;
import com.wifibilling.model.*;
import com.wifibilling.radius.RadAcct;
import com.wifibilling.radius.RadPostAuth;
import com.wifibilling.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NocServiceTest {

    @Mock RouterRepository routers;
    @Mock TenantRepository tenants;
    @Mock RouterTaskRepository tasks;
    @Mock RadAcctRepository radAcct;
    @Mock RadPostAuthRepository radPostAuth;
    @Mock NasRepository nasRepository;
    @Mock VoucherRepository voucherRepository;
    @Mock PackagePlanRepository packagePlanRepository;
    @Mock NocIncidentRepository incidents;
    @Mock RouterManagementService management;
    @Mock RouterLiveProbe liveProbe;
    ObjectMapper mapper = new ObjectMapper();

    NocService noc;

    Tenant tenant;
    Router router;

    @BeforeEach
    void setUp() {
        noc = new NocService(routers, tenants, tasks, radAcct, radPostAuth, nasRepository,
                voucherRepository, packagePlanRepository, incidents, management, liveProbe, mapper);
        tenant = Tenant.builder().id(1L).name("Zone8 Cafe").role("ROLE_ADMIN").build();
        router = Router.builder().id(24L).tenant(tenant).name("Zone8")
                .token("tok_x").wgIp("10.9.0.4/16").status("online")
                .cpuLoad(12).memoryUsage(30).uptime("1d2h").activeUsers(3)
                .lastSeen(OffsetDateTime.now().minusMinutes(1))
                .hotspotReady(true).radiusReady(true).portalReady(true)
                .build();
    }

    @Test
    void routerWithoutWgIpReportsUnavailableAccounting() {
        router.setWgIp(null);
        when(routers.findById(24L)).thenReturn(Optional.of(router));
        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> noc.sessions(24L, null, true, null, 0, 25));
        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    void liveSessionIsDistinguishedFromStaleOpen() {
        OffsetDateTime now = OffsetDateTime.now();
        RadAcct live = RadAcct.builder().radAcctId(1L).username("VOU1").nasIpAddress("10.9.0.4")
                .acctSessionId("s1").acctStartTime(now.minusMinutes(10)).acctUpdateTime(now.minusMinutes(1))
                .acctSessionTime(600L).acctInputOctets(100L).acctOutputOctets(5000L).build();
        RadAcct stale = RadAcct.builder().radAcctId(2L).username("VOU2").nasIpAddress("10.9.0.4")
                .acctSessionId("s2").acctStartTime(now.minusHours(3)).acctUpdateTime(now.minusHours(2))
                .acctSessionTime(300L).build();
        when(routers.findById(24L)).thenReturn(Optional.of(router));
        when(radAcct.findByNas(eq("10.9.0.4"), any(), eq(true), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(live, stale)));
        when(voucherRepository.findAllByCodeIn(anySet())).thenReturn(List.of());
        when(packagePlanRepository.findAllByTenantIdOrderByPriceAsc(1L)).thenReturn(List.of());

        var res = noc.sessions(24L, null, true, null, 0, 25);
        assertEquals("LIVE", res.content().get(0).state());
        assertEquals("OPEN_STALE", res.content().get(1).state());
    }

    @Test
    void closedSessionReportsTerminateCause() {
        OffsetDateTime now = OffsetDateTime.now();
        RadAcct closed = RadAcct.builder().radAcctId(3L).username("VOU3").nasIpAddress("10.9.0.4")
                .acctSessionId("s3").acctStartTime(now.minusHours(2)).acctStopTime(now.minusHours(1))
                .acctSessionTime(3600L).acctTerminateCause("Session-Timeout").build();
        when(routers.findById(24L)).thenReturn(Optional.of(router));
        when(radAcct.findByNas(eq("10.9.0.4"), any(), eq(false), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(closed)));
        when(voucherRepository.findAllByCodeIn(anySet())).thenReturn(List.of());
        when(packagePlanRepository.findAllByTenantIdOrderByPriceAsc(1L)).thenReturn(List.of());

        var res = noc.accounting(24L, null, null, null, null, 0, 25);
        assertEquals("CLOSED", res.content().get(0).state());
        assertEquals("Session-Timeout", res.content().get(0).terminateCause());
    }

    @Test
    void authEventsNeverExposeCredentials() {
        Router r2 = router;
        RadPostAuth reject = mock(RadPostAuth.class);
        when(reject.getId()).thenReturn(9L);
        when(reject.getAuthDate()).thenReturn(OffsetDateTime.now());
        when(reject.getUsername()).thenReturn("VOU9");
        when(reject.getReply()).thenReturn("Access-Reject");
        when(reject.getCallingStationId()).thenReturn("AA:BB:CC:DD:EE:FF");
        when(routers.findById(24L)).thenReturn(Optional.of(r2));
        when(voucherRepository.codesByTenant(1L)).thenReturn(Set.of("VOU9"));
        when(radPostAuth.findEvents(anySet(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(reject)));

        var res = noc.authEvents(24L, null, null, null, null, 0, 25);
        assertEquals(1, res.totalElements());
        assertEquals("Access-Reject", res.content().get(0).reply());
        assertEquals("WARNING", res.content().get(0).severity());
        // The record type has no pass field at all — credentials cannot leak.
        assertFalse(res.content().get(0).toString().toLowerCase().contains("pass"));
    }

    @Test
    void authEventsEmptyWhenTenantHasNoVouchers() {
        when(routers.findById(24L)).thenReturn(Optional.of(router));
        when(voucherRepository.codesByTenant(1L)).thenReturn(Set.of());
        var res = noc.authEvents(24L, null, null, null, null, 0, 25);
        assertEquals(0, res.totalElements());
        verifyNoInteractions(radPostAuth);
    }

    @Test
    void diagnosticsLabelLiveAndInferredSources() {
        when(routers.findById(24L)).thenReturn(Optional.of(router));
        when(nasRepository.findByNasname("10.9.0.4")).thenReturn(Optional.of(new com.wifibilling.radius.Nas()));
        when(radAcct.lastAccountingAt("10.9.0.4")).thenReturn(OffsetDateTime.now().minusMinutes(5));
        when(management.bindingsSynced(router)).thenReturn(true);
        when(tasks.findAllByRouterIdOrderByCreatedAtDesc(24L)).thenReturn(List.of());
        when(liveProbe.probe(router)).thenReturn(new RouterLiveProbe.LiveResult(true,
                java.util.Map.of("uptime", "1d", "cpu-load", 12), 2, 30L, null, null));

        var diag = noc.diagnostics(24L);
        var live = diag.checks().stream().filter(c -> c.key().equals("live")).findFirst().orElseThrow();
        assertEquals("LIVE", live.source());
        assertEquals("HEALTHY", live.status());
        var telemetry = diag.checks().stream().filter(c -> c.key().equals("telemetry")).findFirst().orElseThrow();
        assertEquals("INFERRED", telemetry.source());
        assertEquals("HEALTHY", telemetry.status());
    }

    @Test
    void diagnosticsFlagsUnregisteredNasAsCritical() {
        when(routers.findById(24L)).thenReturn(Optional.of(router));
        when(nasRepository.findByNasname("10.9.0.4")).thenReturn(Optional.empty());
        when(radAcct.lastAccountingAt("10.9.0.4")).thenReturn(null);
        when(management.bindingsSynced(router)).thenReturn(true);
        when(tasks.findAllByRouterIdOrderByCreatedAtDesc(24L)).thenReturn(List.of());
        when(liveProbe.probe(router)).thenReturn(new RouterLiveProbe.LiveResult(false, null, null, null, null, "offline"));

        var diag = noc.diagnostics(24L);
        var radius = diag.checks().stream().filter(c -> c.key().equals("radius")).findFirst().orElseThrow();
        assertEquals("CRITICAL", radius.status());
        assertNotNull(radius.recommendation());
    }

    @Test
    void diagnosticsHandleReadinessNotYetReported() {
        router.setHotspotReady(null);
        router.setRadiusReady(null);
        router.setPortalReady(null);
        when(routers.findById(24L)).thenReturn(Optional.of(router));
        when(liveProbe.probe(router)).thenReturn(new RouterLiveProbe.LiveResult(false, null, null, null, null, "unavailable"));
        var result = noc.diagnostics(24L);
        assertEquals("UNKNOWN", result.checks().stream().filter(c -> c.key().equals("services")).findFirst().orElseThrow().status());
    }

    @Test
    void unavailableHotspotListIsNotReportedAsZeroUsers() {
        when(routers.findById(24L)).thenReturn(Optional.of(router));
        when(liveProbe.probe(router)).thenReturn(new RouterLiveProbe.LiveResult(true,
                java.util.Map.of("uptime", "1d"), null, null, null, null));
        var live = noc.live(24L);
        assertNull(live.get("hotspotUsers"));
        assertNull(live.get("hotspotActive"));
    }

    @Test
    void liveReturnsTheRoutersConnectedUsersAndNeverItsSecrets() {
        router.setRadiusSecret("RADIUS_SECRET_MARKER");
        when(routers.findById(24L)).thenReturn(Optional.of(router));
        java.util.Map<String, Object> host = new java.util.LinkedHashMap<>();
        host.put("user", "ABC123"); host.put("address", "10.5.50.9"); host.put("macAddress", "AA:BB:CC:DD:EE:FF");
        when(liveProbe.probe(router)).thenReturn(new RouterLiveProbe.LiveResult(true,
                java.util.Map.of("uptime", "1d", "cpu-load", 12, "free-memory", 50, "total-memory", 100), 1, 30L, List.of(host), null));
        when(liveProbe.probedAt(24L)).thenReturn(java.time.Instant.parse("2026-10-28T12:00:00Z"));

        var live = noc.live(24L);

        assertEquals("LIVE", live.get("source"));
        assertEquals("2026-10-28T12:00Z", live.get("probedAt"));
        assertEquals(List.of(host), live.get("hotspotUsers"));
        assertEquals(50L, live.get("memoryUsagePct"));
        org.assertj.core.api.Assertions.assertThat(live.toString()).doesNotContain("RADIUS_SECRET_MARKER").doesNotContain("tok_x");
    }
}
