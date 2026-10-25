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

    @GetMapping("/overview")
    public NocDto.Overview overview() { return noc.overview(); }

    @GetMapping("/tenants")
    public List<NocDto.TenantItem> tenants() { return noc.tenantItems(); }

    @GetMapping("/routers")
    public List<NocDto.RouterRow> routers() { return noc.fleet(); }

    @GetMapping("/routers/{id}")
    public NocDto.RouterDetail router(@PathVariable Long id) { return noc.detail(id); }

    /** Optional live probe — performs a real read-only REST query over WireGuard. */
    @GetMapping("/routers/{id}/live")
    public Map<String, Object> live(@PathVariable Long id) { return noc.live(id); }

    @GetMapping("/routers/{id}/sessions")
    public NocDto.PageResponse<NocDto.SessionRow> sessions(
            @PathVariable Long id,
            @RequestParam(required = false) String username,
            @RequestParam(defaultValue = "false") boolean openOnly,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return noc.sessions(id, username, openOnly, state, page, size);
    }

    @GetMapping("/routers/{id}/accounting")
    public NocDto.PageResponse<NocDto.SessionRow> accounting(
            @PathVariable Long id,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String cause,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return noc.accounting(id, username, cause, from, to, page, size);
    }

    @GetMapping("/routers/{id}/usage")
    public List<NocDto.UsagePoint> usage(@PathVariable Long id,
                                         @RequestParam(defaultValue = "14") int days) {
        return noc.usage(id, days);
    }

    @GetMapping("/routers/{id}/auth-events")
    public NocDto.PageResponse<NocDto.AuthEventRow> authEvents(
            @PathVariable Long id,
            @RequestParam(required = false) String reply,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return noc.authEvents(id, reply, username, from, to, page, size);
    }

    @GetMapping("/routers/{id}/tasks")
    public List<NocDto.TaskItem> tasks(@PathVariable Long id) { return noc.taskHistory(id); }

    @GetMapping("/routers/{id}/hosts")
    public Map<String, Object> hosts(@PathVariable Long id) { return noc.hosts(id); }

    @GetMapping("/routers/{id}/events")
    public List<NocDto.EventItem> events(@PathVariable Long id) { return noc.events(id); }

    @GetMapping("/routers/{id}/incidents")
    public List<NocDto.IncidentItem> incidents(@PathVariable Long id) { return noc.incidentsFor(id); }

    @GetMapping("/routers/{id}/diagnostics")
    public NocDto.Diagnostics diagnostics(@PathVariable Long id) { return noc.diagnostics(id); }
}
