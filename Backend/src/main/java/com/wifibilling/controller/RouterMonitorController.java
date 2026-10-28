package com.wifibilling.controller;

import com.wifibilling.dto.NocDto;
import com.wifibilling.model.Router;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.AuthService;
import com.wifibilling.service.NocService;
import com.wifibilling.service.RouterOperationsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Router monitoring for a business's own routers — the same views as the
 * platform NOC, scoped by {@link RouterOperationsService#authorize}: a router
 * of another tenant answers 404 whatever id is sent. Responses carry no
 * router secrets (NocDto has none; the live probe returns display fields).
 */
@RestController
@RequestMapping("/api/routers/{id}/monitor")
@RequiredArgsConstructor
public class RouterMonitorController {

    private final NocService noc;
    private final RouterOperationsService ops;

    private Router allowed(TenantPrincipal p, Long id, String section, HttpServletRequest req) {
        Router r = ops.authorize(p, id);
        ops.auditView(p, r, section, AuthService.extractClientIp(req));
        return r;
    }

    @GetMapping
    public NocDto.RouterDetail detail(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id, HttpServletRequest req) {
        return noc.detail(allowed(p, id, "detail", req).getId());
    }

    /** Live read-only query over the tunnel (cached up to 30 s); "source" says LIVE or UNAVAILABLE. */
    @GetMapping("/live")
    public Map<String, Object> live(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id, HttpServletRequest req) {
        return noc.live(allowed(p, id, "live", req).getId());
    }

    @GetMapping("/sessions")
    public NocDto.PageResponse<NocDto.SessionRow> sessions(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id,
            @RequestParam(required = false) String username, @RequestParam(defaultValue = "false") boolean openOnly,
            @RequestParam(required = false) String state, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, HttpServletRequest req) {
        return noc.sessions(allowed(p, id, "sessions", req).getId(), username, openOnly, state, page, size);
    }

    @GetMapping("/accounting")
    public NocDto.PageResponse<NocDto.SessionRow> accounting(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id,
            @RequestParam(required = false) String username, @RequestParam(required = false) String cause,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, HttpServletRequest req) {
        return noc.accounting(allowed(p, id, "accounting", req).getId(), username, cause, from, to, page, size);
    }

    @GetMapping("/usage")
    public List<NocDto.UsagePoint> usage(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id,
            @RequestParam(defaultValue = "14") int days, HttpServletRequest req) {
        return noc.usage(allowed(p, id, "usage", req).getId(), days);
    }

    @GetMapping("/auth-events")
    public NocDto.PageResponse<NocDto.AuthEventRow> authEvents(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id,
            @RequestParam(required = false) String reply, @RequestParam(required = false) String username,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, HttpServletRequest req) {
        return noc.authEvents(allowed(p, id, "auth-events", req).getId(), reply, username, from, to, page, size);
    }

    @GetMapping("/tasks")
    public List<NocDto.TaskItem> tasks(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id, HttpServletRequest req) {
        return noc.taskHistory(allowed(p, id, "tasks", req).getId());
    }

    @GetMapping("/hosts")
    public Map<String, Object> hosts(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id, HttpServletRequest req) {
        return noc.hosts(allowed(p, id, "hosts", req).getId());
    }

    @GetMapping("/events")
    public List<NocDto.EventItem> events(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id, HttpServletRequest req) {
        return noc.events(allowed(p, id, "events", req).getId());
    }

    @GetMapping("/incidents")
    public List<NocDto.IncidentItem> incidents(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id, HttpServletRequest req) {
        return noc.incidentsFor(allowed(p, id, "incidents", req).getId());
    }

    @GetMapping("/diagnostics")
    public NocDto.Diagnostics diagnostics(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id, HttpServletRequest req) {
        return noc.diagnostics(allowed(p, id, "diagnostics", req).getId());
    }

    public record DisconnectRequest(@NotBlank @Size(max = 64) String username) {}

    /** Approved action: disconnect one hotspot user from this router (audited, rate limited). */
    @PostMapping("/disconnect")
    public RouterOperationsService.DisconnectOutcome disconnect(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id,
            @Valid @RequestBody DisconnectRequest body, HttpServletRequest req) {
        return ops.disconnect(p, id, body.username(), AuthService.extractClientIp(req));
    }

    @GetMapping("/disconnect-status")
    public RouterOperationsService.DisconnectStatus disconnectStatus(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id,
            @RequestParam String username, @RequestParam(required = false) Long taskId) {
        return ops.disconnectStatus(p, id, username, taskId);
    }
}
