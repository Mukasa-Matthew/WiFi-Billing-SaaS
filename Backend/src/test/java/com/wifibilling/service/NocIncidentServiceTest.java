package com.wifibilling.service;

import com.wifibilling.model.NocIncident;
import com.wifibilling.model.Router;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NocIncidentServiceTest {

    @Mock RouterRepository routers;
    @Mock NocIncidentRepository incidents;
    @Mock RouterTaskRepository tasks;
    @Mock RadAcctRepository radAcct;
    @Mock RouterManagementService management;

    NocIncidentService service;

    Router router;

    @BeforeEach
    void setUp() {
        service = new NocIncidentService(routers, incidents, tasks, radAcct, management);
        router = Router.builder().id(24L).name("Zone8").wgIp("10.9.0.4/16")
                .tenant(Tenant.builder().id(1L).name("T").build())
                .status("online").cpuLoad(10).memoryUsage(20).activeUsers(0)
                .hotspotReady(true).radiusReady(true).portalReady(true)
                .lastSeen(OffsetDateTime.now()).build();
    }

    @Test
    void opensIncidentOnceAndResolvesOnRecovery() {
        router.setStatus("offline");
        when(routers.findAll()).thenReturn(List.of(router));
        when(tasks.findAllByRouterIdOrderByCreatedAtDesc(24L)).thenReturn(List.of());
        when(incidents.findFirstByRouterIdAndTypeAndResolvedAtIsNull(eq(24L), anyString()))
                .thenReturn(Optional.empty());

        service.evaluate();
        var captor = ArgumentCaptor.forClass(NocIncident.class);
        verify(incidents).save(captor.capture());
        assertEquals(NocIncident.ROUTER_OFFLINE, captor.getValue().getType());
        assertEquals("CRITICAL", captor.getValue().getSeverity());

        // Second run while still offline — dedupe, no second incident.
        var open = captor.getValue();
        when(incidents.findFirstByRouterIdAndTypeAndResolvedAtIsNull(eq(24L), eq(NocIncident.ROUTER_OFFLINE)))
                .thenReturn(Optional.of(open));
        service.evaluate();
        verify(incidents, times(1)).save(any());

        // Recovery closes the open incident.
        router.setStatus("online");
        service.evaluate();
        assertNotNull(open.getResolvedAt());
    }

    @Test
    void pendingRouterDoesNotRaiseIncidents() {
        router.setStatus("pending");
        router.setLastSeen(null);
        router.setHotspotReady(null);
        router.setRadiusReady(null);
        router.setPortalReady(null);
        when(routers.findAll()).thenReturn(List.of(router));
        when(tasks.findAllByRouterIdOrderByCreatedAtDesc(24L)).thenReturn(List.of());
        when(incidents.findFirstByRouterIdAndTypeAndResolvedAtIsNull(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        service.evaluate();
        verify(incidents, never()).save(any());
    }

    @Test
    void degradedReadinessOpensWarning() {
        router.setPortalReady(false);
        router.setStatus("degraded");
        when(routers.findAll()).thenReturn(List.of(router));
        when(tasks.findAllByRouterIdOrderByCreatedAtDesc(24L)).thenReturn(List.of());
        when(incidents.findFirstByRouterIdAndTypeAndResolvedAtIsNull(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        service.evaluate();
        var captor = ArgumentCaptor.forClass(NocIncident.class);
        verify(incidents).save(captor.capture());
        assertEquals(NocIncident.ROUTER_DEGRADED, captor.getValue().getType());
        assertEquals("WARNING", captor.getValue().getSeverity());
    }
}
