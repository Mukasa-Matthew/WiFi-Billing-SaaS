package com.wifibilling.service;

import com.wifibilling.model.AuditLog;
import com.wifibilling.model.Router;
import com.wifibilling.model.RouterTask;
import com.wifibilling.radius.RadAcct;
import com.wifibilling.repository.AuditLogRepository;
import com.wifibilling.repository.RadAcctRepository;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.repository.RouterTaskRepository;
import com.wifibilling.repository.VoucherRepository;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.util.Ipv4Subnet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Authorization and the approved remote actions for tenant routers.
 *
 * Every router read or action goes through {@link #authorize}: a business
 * admin reaches only their own tenant's routers (anything else is a 404, so
 * router ids can't be probed), a superadmin reaches any router and those
 * cross-tenant views are audited. The only service-affecting action is
 * disconnecting one hotspot user; it is validated, rate limited, audited and
 * reported with a verifiable status rather than assumed to have worked.
 * There is no arbitrary command execution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouterOperationsService {

    static final String SUPERADMIN = "ROLE_SUPERADMIN";
    /** Voucher codes / hotspot usernames; anything else never reaches RADIUS or a router script. */
    static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._@:-]{1,64}");
    static final int DISCONNECTS_PER_MINUTE = 10;
    static final long VIEW_AUDIT_EVERY_MS = 10 * 60_000;

    private final RouterRepository routers;
    private final RadAcctRepository accounting;
    private final VoucherRepository vouchers;
    private final RouterTaskRepository tasks;
    private final RouterManagementService management;
    private final RadiusDisconnectService radius;
    private final AuditLogRepository audit;

    Clock clock = Clock.systemUTC();
    private final Map<String, Long> lastViewAudit = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> disconnects = new ConcurrentHashMap<>();

    /** The router if this principal may see it; 404 otherwise (same answer for "missing" and "not yours"). */
    public Router authorize(TenantPrincipal p, Long routerId) {
        if (p == null || routerId == null || routerId <= 0) throw notFound();
        if (SUPERADMIN.equals(p.getRole())) return routers.findById(routerId).orElseThrow(RouterOperationsService::notFound);
        return routers.findByIdAndTenantId(routerId, p.getTenantId()).orElseThrow(RouterOperationsService::notFound);
    }

    /** Records a superadmin looking at another tenant's router (at most once per section every 10 min). */
    public void auditView(TenantPrincipal p, Router r, String section, String ip) {
        if (!SUPERADMIN.equals(p.getRole()) || r.getTenant() == null || r.getTenant().getId().equals(p.getTenantId())) return;
        String key = p.getEmail() + "|" + r.getId() + "|" + section;
        long now = clock.millis();
        Long last = lastViewAudit.get(key);
        if (last != null && now - last < VIEW_AUDIT_EVERY_MS) return;
        lastViewAudit.put(key, now);
        record(r, "NOC_ROUTER_VIEWED", p, ip, "router=" + r.getId() + " section=" + section);
    }

    public record DisconnectOutcome(Long taskId, String radiusReply, int sessions, String status, String message) {}

    /**
     * Disconnects one hotspot user from this router. The router removes the
     * user when it applies the queued task; a RADIUS Disconnect-Request is
     * sent as well and its verified reply (ACK/NAK/NO_REPLY) is reported.
     */
    public DisconnectOutcome disconnect(TenantPrincipal p, Long routerId, String username, String ip) {
        Router r = authorize(p, routerId);
        String user = username == null ? "" : username.trim();
        if (!USERNAME.matcher(user).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That isn't a valid hotspot username.");
        }
        String nas = Ipv4Subnet.stripPrefix(r.getWgIp());
        List<RadAcct> open = nas == null ? List.of() : accounting.findAllByUsernameAndNasIpAddressAndAcctStopTimeIsNull(user, nas);
        if (open.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, user + " has no open session on " + r.getName() + ".");
        }
        // A voucher with this code that belongs to another tenant is never disconnected through this router.
        vouchers.findByCode(user).ifPresent(v -> {
            if (r.getTenant() == null || !v.getTenant().getId().equals(r.getTenant().getId())) throw notFound();
        });
        rateLimit(p);

        RouterTask task = management.enqueueDisconnect(r, user);
        String reply = "NO_REPLY";
        for (RadAcct s : open) {
            RadiusDisconnectService.Result res = radius.disconnect(nas, RadiusDisconnectService.DEFAULT_COA_PORT,
                    r.getRadiusSecret(), user, s.getFramedIpAddress());
            if (res == RadiusDisconnectService.Result.ACK || (res == RadiusDisconnectService.Result.NAK && !"ACK".equals(reply))) reply = res.name();
            else if (res == RadiusDisconnectService.Result.ERROR && "NO_REPLY".equals(reply)) reply = "ERROR";
        }
        String message = switch (reply) {
            case "ACK" -> "The router confirmed the disconnect.";
            case "NAK" -> "The router refused the RADIUS disconnect; it will still remove the user when it applies the queued task.";
            default -> "Disconnect queued. The router removes the user at its next check-in.";
        };
        record(r, "ROUTER_USER_DISCONNECT", p, ip, "router=" + r.getId() + " user=" + user + " sessions=" + open.size()
                + " radius=" + reply + " task=" + task.getId());
        return new DisconnectOutcome(task.getId(), reply, open.size(), "REQUESTED", message);
    }

    public record DisconnectStatus(String taskStatus, int openSessions, boolean verified, String message) {}

    /** Whether the disconnect actually happened: the session closed, or the router applied the task. */
    public DisconnectStatus disconnectStatus(TenantPrincipal p, Long routerId, String username, Long taskId) {
        Router r = authorize(p, routerId);
        String user = username == null ? "" : username.trim();
        if (!USERNAME.matcher(user).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That isn't a valid hotspot username.");
        RouterTask t = taskId == null ? null : tasks.findByIdAndRouterId(taskId, r.getId())
                .filter(task -> "DISCONNECT".equals(task.getAction()) &&
                        ("/ip/hotspot/active/remove [find user=" + RouterScriptGenerator.q(user) + "]").equals(task.getScript()))
                .orElse(null);
        String nas = Ipv4Subnet.stripPrefix(r.getWgIp());
        int open = nas == null || nas.isBlank() ? -1 : accounting.findAllByUsernameAndNasIpAddressAndAcctStopTimeIsNull(user, nas).size();
        String taskStatus = t == null ? "UNKNOWN" : t.getStatus();
        if (t != null && "PENDING".equals(taskStatus) && t.getCreatedAt() != null
                && t.getCreatedAt().toInstant().isBefore(clock.instant().minusSeconds(120))) taskStatus = "TIMED_OUT";
        boolean verified = t != null && (open == 0 || "APPLIED".equals(taskStatus));
        String message = t == null ? "No matching disconnect task was found."
                : open == 0 ? "RADIUS accounting reports no open sessions for " + user + "."
                : "APPLIED".equals(taskStatus) ? "The router removed " + user + "; accounting will close the session shortly."
                : "FAILED".equals(taskStatus) ? "The router reported an error applying the disconnect."
                : "TIMED_OUT".equals(taskStatus) ? "The router has not acknowledged the disconnect. It remains queued and may apply later."
                : "Waiting for the router to check in.";
        return new DisconnectStatus(taskStatus, open, verified, message);
    }

    private void rateLimit(TenantPrincipal p) {
        long now = clock.millis();
        Deque<Long> recent = disconnects.computeIfAbsent(p.getEmail() == null ? "?" : p.getEmail(), k -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && now - recent.peekFirst() > 60_000) recent.pollFirst();
            if (recent.size() >= DISCONNECTS_PER_MINUTE) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many disconnects in a minute. Wait a moment and try again.");
            }
            recent.addLast(now);
        }
    }

    private void record(Router r, String action, TenantPrincipal p, String ip, String details) {
        try {
            audit.save(AuditLog.builder()
                    .tenantId(r.getTenant() == null ? null : r.getTenant().getId())
                    .action(action)
                    .actorEmail(p.getEmail())
                    .ipAddress(ip)
                    .details(details.length() > 500 ? details.substring(0, 500) : details)
                    .createdAt(Instant.now(clock).atOffset(java.time.ZoneOffset.UTC))
                    .build());
        } catch (RuntimeException e) {
            log.warn("Could not record {} audit entry for router {}: {}", action, r.getId(), e.getMessage());
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found");
    }
}
