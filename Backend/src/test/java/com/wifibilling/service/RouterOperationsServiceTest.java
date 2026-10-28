package com.wifibilling.service;

import com.wifibilling.model.AuditLog;
import com.wifibilling.model.Router;
import com.wifibilling.model.RouterTask;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.Voucher;
import com.wifibilling.radius.RadAcct;
import com.wifibilling.repository.AuditLogRepository;
import com.wifibilling.repository.RadAcctRepository;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.repository.RouterTaskRepository;
import com.wifibilling.repository.VoucherRepository;
import com.wifibilling.security.TenantPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Who may reach which router, and the one approved service-affecting action. */
class RouterOperationsServiceTest {

    static final String SECRET = "RADIUS_SECRET_MARKER";

    RouterRepository routers = mock(RouterRepository.class);
    RadAcctRepository accounting = mock(RadAcctRepository.class);
    VoucherRepository vouchers = mock(VoucherRepository.class);
    RouterTaskRepository tasks = mock(RouterTaskRepository.class);
    RouterManagementService management = mock(RouterManagementService.class);
    RadiusDisconnectService radius = mock(RadiusDisconnectService.class);
    AuditLogRepository audit = mock(AuditLogRepository.class);
    RouterOperationsService ops = new RouterOperationsService(routers, accounting, vouchers, tasks, management, radius, audit);

    Tenant tenant7 = Tenant.builder().id(7L).name("Seven").build();
    Router router = Router.builder().id(42L).tenant(tenant7).name("Main").wgIp("10.9.0.42/32").radiusSecret(SECRET).build();
    TenantPrincipal owner = new TenantPrincipal(7L, "owner@seven.test", "Owner", "ROLE_ADMIN", false);
    TenantPrincipal otherOwner = new TenantPrincipal(8L, "owner@eight.test", "Other", "ROLE_ADMIN", false);
    TenantPrincipal superadmin = new TenantPrincipal(1L, "root@platform.test", "Root", "ROLE_SUPERADMIN", false);

    @BeforeEach
    void setUp() {
        ops.clock = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC);
        when(routers.findById(42L)).thenReturn(Optional.of(router));
        when(routers.findByIdAndTenantId(42L, 7L)).thenReturn(Optional.of(router));
        when(routers.findByIdAndTenantId(42L, 8L)).thenReturn(Optional.empty());
        when(management.enqueueDisconnect(eq(router), anyString())).thenReturn(RouterTask.builder().id(900L).router(router).action("DISCONNECT").script("x").build());
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(status));
    }

    @Test
    void ownerReachesOwnRouterAndOtherTenantsGetTheSame404AsAMissingRouter() {
        assertThat(ops.authorize(owner, 42L)).isSameAs(router);
        assertStatus(() -> ops.authorize(otherOwner, 42L), HttpStatus.NOT_FOUND);
        assertStatus(() -> ops.authorize(owner, 999L), HttpStatus.NOT_FOUND);
        assertStatus(() -> ops.authorize(owner, null), HttpStatus.NOT_FOUND);
        assertStatus(() -> ops.authorize(owner, -1L), HttpStatus.NOT_FOUND);
        // Tenant lookups always carry the caller's own tenant id, never one from the request.
        verify(routers, never()).findById(anyLong());
    }

    @Test
    void superadminReachesAnyRouterAndCrossTenantViewsAreAuditedOncePerWindow() {
        assertThat(ops.authorize(superadmin, 42L)).isSameAs(router);
        ops.auditView(superadmin, router, "live", "203.0.113.5");
        ops.auditView(superadmin, router, "live", "203.0.113.5");
        ops.auditView(superadmin, router, "sessions", "203.0.113.5");

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(audit, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(a -> {
            assertThat(a.getAction()).isEqualTo("NOC_ROUTER_VIEWED");
            assertThat(a.getTenantId()).isEqualTo(7L);
            assertThat(a.getActorEmail()).isEqualTo("root@platform.test");
        });
        assertThat(saved.getAllValues()).extracting(AuditLog::getDetails).containsExactly("router=42 section=live", "router=42 section=sessions");
    }

    @Test
    void ownersViewingTheirOwnRouterAreNotAuditedAsCrossTenant() {
        ops.auditView(owner, router, "live", "198.51.100.1");
        verifyNoInteractions(audit);
    }

    @Test
    void malformedUsernamesNeverReachRadiusOrARouterScript() {
        for (String bad : new String[]{"", "a b", "x\";/system reboot;\"", "$(id)", "a".repeat(65), null}) {
            assertStatus(() -> ops.disconnect(owner, 42L, bad, "1.2.3.4"), HttpStatus.BAD_REQUEST);
        }
        verifyNoInteractions(management, radius);
    }

    @Test
    void disconnectNeedsAnOpenSessionOnThisRouter() {
        when(accounting.findAllByUsernameAndNasIpAddressAndAcctStopTimeIsNull("ABC123", "10.9.0.42")).thenReturn(List.of());
        assertStatus(() -> ops.disconnect(owner, 42L, "ABC123", "1.2.3.4"), HttpStatus.CONFLICT);
        verifyNoInteractions(management, radius);
    }

    @Test
    void anotherTenantsVoucherIsNeverDisconnectedThroughThisRouter() {
        when(accounting.findAllByUsernameAndNasIpAddressAndAcctStopTimeIsNull("ABC123", "10.9.0.42")).thenReturn(List.of(new RadAcct()));
        when(vouchers.findByCode("ABC123")).thenReturn(Optional.of(Voucher.builder().code("ABC123").tenant(Tenant.builder().id(8L).build()).build()));
        assertStatus(() -> ops.disconnect(owner, 42L, "ABC123", "1.2.3.4"), HttpStatus.NOT_FOUND);
        verifyNoInteractions(management, radius);
    }

    @Test
    void crossTenantDisconnectIsRejectedBeforeAnything() {
        assertStatus(() -> ops.disconnect(otherOwner, 42L, "ABC123", "1.2.3.4"), HttpStatus.NOT_FOUND);
        verifyNoInteractions(accounting, management, radius, audit);
    }

    @Test
    void disconnectQueuesTheTaskSendsRadiusAndAuditsTheVerifiedResultWithoutSecrets() {
        RadAcct s = new RadAcct();
        s.setFramedIpAddress("10.5.50.9");
        when(accounting.findAllByUsernameAndNasIpAddressAndAcctStopTimeIsNull("ABC123", "10.9.0.42")).thenReturn(List.of(s));
        when(vouchers.findByCode("ABC123")).thenReturn(Optional.of(Voucher.builder().code("ABC123").tenant(tenant7).build()));
        when(radius.disconnect("10.9.0.42", 3799, SECRET, "ABC123", "10.5.50.9")).thenReturn(RadiusDisconnectService.Result.ACK);

        var out = ops.disconnect(owner, 42L, "ABC123", "198.51.100.1");

        assertThat(out.taskId()).isEqualTo(900L);
        assertThat(out.radiusReply()).isEqualTo("ACK");
        assertThat(out.status()).isEqualTo("REQUESTED");
        assertThat(out.toString()).doesNotContain(SECRET);
        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(audit).save(saved.capture());
        AuditLog a = saved.getValue();
        assertThat(a.getAction()).isEqualTo("ROUTER_USER_DISCONNECT");
        assertThat(a.getTenantId()).isEqualTo(7L);
        assertThat(a.getActorEmail()).isEqualTo("owner@seven.test");
        assertThat(a.getIpAddress()).isEqualTo("198.51.100.1");
        assertThat(a.getDetails()).contains("router=42", "user=ABC123", "radius=ACK", "task=900").doesNotContain(SECRET);
    }

    @Test
    void noRadiusReplyIsReportedHonestlyAsQueuedNotDone() {
        when(accounting.findAllByUsernameAndNasIpAddressAndAcctStopTimeIsNull("ABC123", "10.9.0.42")).thenReturn(List.of(new RadAcct()));
        when(radius.disconnect(anyString(), anyInt(), anyString(), anyString(), any())).thenReturn(RadiusDisconnectService.Result.NO_REPLY);

        var out = ops.disconnect(superadmin, 42L, "ABC123", "1.2.3.4");

        assertThat(out.radiusReply()).isEqualTo("NO_REPLY");
        assertThat(out.message()).contains("next check-in");
    }

    @Test
    void disconnectsAreRateLimitedPerAdmin() {
        when(accounting.findAllByUsernameAndNasIpAddressAndAcctStopTimeIsNull(anyString(), eq("10.9.0.42"))).thenReturn(List.of(new RadAcct()));
        when(radius.disconnect(anyString(), anyInt(), anyString(), anyString(), any())).thenReturn(RadiusDisconnectService.Result.NO_REPLY);
        for (int i = 0; i < RouterOperationsService.DISCONNECTS_PER_MINUTE; i++) ops.disconnect(owner, 42L, "U" + i, "1.2.3.4");
        assertStatus(() -> ops.disconnect(owner, 42L, "U99", "1.2.3.4"), HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void unrelatedAppliedTaskCannotVerifyADisconnect() {
        when(tasks.findByIdAndRouterId(900L, 42L)).thenReturn(Optional.of(
                RouterTask.builder().id(900L).router(router).action("SYNC_PORTAL").script("x").status("APPLIED").build()));
        when(accounting.findAllByUsernameAndNasIpAddressAndAcctStopTimeIsNull("ABC123", "10.9.0.42")).thenReturn(List.of(new RadAcct()));
        assertThat(ops.disconnectStatus(owner, 42L, "ABC123", 900L).verified()).isFalse();
    }

    @Test
    void oldPendingDisconnectReportsTimeoutWithoutClaimingSuccess() {
        RouterTask pending = RouterTask.builder().id(900L).router(router).action("DISCONNECT")
                .script("/ip/hotspot/active/remove [find user=\"ABC123\"]")
                .createdAt(ops.clock.instant().minusSeconds(180).atOffset(ZoneOffset.UTC)).build();
        when(tasks.findByIdAndRouterId(900L, 42L)).thenReturn(Optional.of(pending));
        when(accounting.findAllByUsernameAndNasIpAddressAndAcctStopTimeIsNull("ABC123", "10.9.0.42")).thenReturn(List.of(new RadAcct()));
        var result = ops.disconnectStatus(owner, 42L, "ABC123", 900L);
        assertThat(result.verified()).isFalse();
        assertThat(result.taskStatus()).isEqualTo("TIMED_OUT");
        assertThat(result.message()).contains("may apply later");
    }

    @Test
    void missingTunnelAddressCannotVerifyAbsenceOfSessions() {
        router.setWgIp(null);
        assertThat(ops.disconnectStatus(owner, 42L, "ABC123", null).verified()).isFalse();
    }

    @Test
    void statusIsVerifiedOnlyWhenTheSessionClosedOrTheRouterAppliedTheTask() {
        RouterTask pending = RouterTask.builder().id(900L).router(router).action("DISCONNECT").script("/ip/hotspot/active/remove [find user=\"ABC123\"]").build();
        when(tasks.findByIdAndRouterId(900L, 42L)).thenReturn(Optional.of(pending));
        when(accounting.findAllByUsernameAndNasIpAddressAndAcctStopTimeIsNull("ABC123", "10.9.0.42")).thenReturn(List.of(new RadAcct()));
        assertThat(ops.disconnectStatus(owner, 42L, "ABC123", 900L).verified()).isFalse();

        pending.setStatus("APPLIED");
        assertThat(ops.disconnectStatus(owner, 42L, "ABC123", 900L).verified()).isTrue();

        pending.setStatus("FAILED");
        var failed = ops.disconnectStatus(owner, 42L, "ABC123", 900L);
        assertThat(failed.verified()).isFalse();
        assertThat(failed.message()).contains("error");

        when(accounting.findAllByUsernameAndNasIpAddressAndAcctStopTimeIsNull("ABC123", "10.9.0.42")).thenReturn(List.of());
        assertThat(ops.disconnectStatus(owner, 42L, "ABC123", 900L).verified()).isTrue();

        assertStatus(() -> ops.disconnectStatus(otherOwner, 42L, "ABC123", 900L), HttpStatus.NOT_FOUND);
    }
}
