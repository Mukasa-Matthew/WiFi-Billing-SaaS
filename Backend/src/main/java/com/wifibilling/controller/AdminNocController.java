package com.wifibilling.controller;

import com.wifibilling.dto.NocDto;
import com.wifibilling.service.NocService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Super-admin Network Operations Center API. Read-only by design: no router
 * mutation, disconnect, or provisioning actions are exposed here. All routes
 * are additionally guarded by /api/admin/** → ROLE_SUPERADMIN in SecurityConfig.
 */
@RestController
@RequestMapping("/api/admin/noc")
@PreAuthorize("hasAuthority('ROLE_SUPERADMIN')")
@RequiredArgsConstructor
public class AdminNocController {

    private final NocService noc;
    private final com.wifibilling.service.RouterOperationsService ops;

    /** Cross-tenant router views are audited (throttled per admin, router and section). */
    private Long seen(com.wifibilling.security.TenantPrincipal p, Long id, String section, jakarta.servlet.http.HttpServletRequest req) {
        var r = ops.authorize(p, id);
        ops.auditView(p, r, section, com.wifibilling.service.AuthService.extractClientIp(req));
        return r.getId();
    }

    @GetMapping("/overview")
    public NocDto.Overview overview() { return noc.overview(); }

    @GetMapping("/tenants")
    public List<NocDto.TenantItem> tenants() { return noc.tenantItems(); }

    @GetMapping("/routers")
    public List<NocDto.RouterRow> routers() { return noc.fleet(); }

    @GetMapping("/routers/{id}")
    public NocDto.RouterDetail router(@org.springframework.security.core.annotation.AuthenticationPrincipal com.wifibilling.security.TenantPrincipal principal, jakarta.servlet.http.HttpServletRequest httpRequest,
            @PathVariable Long id) { return noc.detail(seen(principal, id, "detail", httpRequest)); }

    /** Optional live probe — performs a real read-only REST query over WireGuard. */
    @GetMapping("/routers/{id}/live")
    public Map<String, Object> live(@org.springframework.security.core.annotation.AuthenticationPrincipal com.wifibilling.security.TenantPrincipal principal, jakarta.servlet.http.HttpServletRequest httpRequest,
            @PathVariable Long id) { return noc.live(seen(principal, id, "live", httpRequest)); }

    @GetMapping("/routers/{id}/sessions")
    public NocDto.PageResponse<NocDto.SessionRow> sessions(@org.springframework.security.core.annotation.AuthenticationPrincipal com.wifibilling.security.TenantPrincipal principal, jakarta.servlet.http.HttpServletRequest httpRequest,
            @PathVariable Long id,
            @RequestParam(required = false) String username,
            @RequestParam(defaultValue = "false") boolean openOnly,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return noc.sessions(seen(principal, id, "sessions", httpRequest), username, openOnly, state, page, size);
    }

    @GetMapping("/routers/{id}/accounting")
    public NocDto.PageResponse<NocDto.SessionRow> accounting(@org.springframework.security.core.annotation.AuthenticationPrincipal com.wifibilling.security.TenantPrincipal principal, jakarta.servlet.http.HttpServletRequest httpRequest,
            @PathVariable Long id,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String cause,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return noc.accounting(seen(principal, id, "accounting", httpRequest), username, cause, from, to, page, size);
    }

    @GetMapping("/routers/{id}/usage")
    public List<NocDto.UsagePoint> usage(@org.springframework.security.core.annotation.AuthenticationPrincipal com.wifibilling.security.TenantPrincipal principal, jakarta.servlet.http.HttpServletRequest httpRequest,
            @PathVariable Long id,
                                         @RequestParam(defaultValue = "14") int days) {
        return noc.usage(seen(principal, id, "usage", httpRequest), days);
    }

    @GetMapping("/routers/{id}/auth-events")
    public NocDto.PageResponse<NocDto.AuthEventRow> authEvents(@org.springframework.security.core.annotation.AuthenticationPrincipal com.wifibilling.security.TenantPrincipal principal, jakarta.servlet.http.HttpServletRequest httpRequest,
            @PathVariable Long id,
            @RequestParam(required = false) String reply,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return noc.authEvents(seen(principal, id, "auth-events", httpRequest), reply, username, from, to, page, size);
    }

    @GetMapping("/routers/{id}/tasks")
    public List<NocDto.TaskItem> tasks(@org.springframework.security.core.annotation.AuthenticationPrincipal com.wifibilling.security.TenantPrincipal principal, jakarta.servlet.http.HttpServletRequest httpRequest,
            @PathVariable Long id) { return noc.taskHistory(seen(principal, id, "tasks", httpRequest)); }

    @GetMapping("/routers/{id}/hosts")
    public Map<String, Object> hosts(@org.springframework.security.core.annotation.AuthenticationPrincipal com.wifibilling.security.TenantPrincipal principal, jakarta.servlet.http.HttpServletRequest httpRequest,
            @PathVariable Long id) { return noc.hosts(seen(principal, id, "hosts", httpRequest)); }

    @GetMapping("/routers/{id}/events")
    public List<NocDto.EventItem> events(@org.springframework.security.core.annotation.AuthenticationPrincipal com.wifibilling.security.TenantPrincipal principal, jakarta.servlet.http.HttpServletRequest httpRequest,
            @PathVariable Long id) { return noc.events(seen(principal, id, "events", httpRequest)); }

    @GetMapping("/routers/{id}/incidents")
    public List<NocDto.IncidentItem> incidents(@org.springframework.security.core.annotation.AuthenticationPrincipal com.wifibilling.security.TenantPrincipal principal, jakarta.servlet.http.HttpServletRequest httpRequest,
            @PathVariable Long id) { return noc.incidentsFor(seen(principal, id, "incidents", httpRequest)); }

    public record DisconnectRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 64) String username) {}

    /** Approved action: disconnect one hotspot user from any tenant's router (audited, rate limited). */
    @org.springframework.web.bind.annotation.PostMapping("/routers/{id}/disconnect")
    public com.wifibilling.service.RouterOperationsService.DisconnectOutcome disconnect(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.wifibilling.security.TenantPrincipal principal,
            @PathVariable Long id, @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody DisconnectRequest body,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        return ops.disconnect(principal, id, body.username(), com.wifibilling.service.AuthService.extractClientIp(httpRequest));
    }

    @GetMapping("/routers/{id}/disconnect-status")
    public com.wifibilling.service.RouterOperationsService.DisconnectStatus disconnectStatus(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.wifibilling.security.TenantPrincipal principal,
            @PathVariable Long id, @RequestParam String username, @RequestParam(required = false) Long taskId) {
        return ops.disconnectStatus(principal, id, username, taskId);
    }

    @GetMapping("/routers/{id}/diagnostics")
    public NocDto.Diagnostics diagnostics(@org.springframework.security.core.annotation.AuthenticationPrincipal com.wifibilling.security.TenantPrincipal principal, jakarta.servlet.http.HttpServletRequest httpRequest,
            @PathVariable Long id) { return noc.diagnostics(seen(principal, id, "diagnostics", httpRequest)); }
}
