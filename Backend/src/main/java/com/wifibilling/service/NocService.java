package com.wifibilling.service;

import com.wifibilling.dto.NocDto;
import com.wifibilling.model.*;
import com.wifibilling.radius.RadAcct;
import com.wifibilling.radius.RadPostAuth;
import com.wifibilling.repository.*;
import com.wifibilling.util.Ipv4Subnet;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Super-admin Network Operations Center — read-only visibility across every
 * tenant's routers, RADIUS sessions/accounting, auth events, provisioning
 * history, incidents and diagnostics. All data comes from stored records
 * (routers, router_tasks, radacct, radpostauth, nas, vouchers) or the
 * read-only live REST probe; nothing is fabricated — unavailable values are
 * surfaced as null and rendered as "Unavailable".
 */
@Service
@RequiredArgsConstructor
public class NocService {

    /** A session with no Stop record is only "live" if an interim update landed recently. */
    private static final long STALE_AFTER_MINUTES = 15;
    private static final int MAX_PAGE = 100;

    private final RouterRepository routers;
    private final TenantRepository tenants;
    private final RouterTaskRepository tasks;
    private final RadAcctRepository radAcct;
    private final RadPostAuthRepository radPostAuth;
    private final NasRepository nasRepository;
    private final VoucherRepository voucherRepository;
    private final PackagePlanRepository packagePlanRepository;
    private final NocIncidentRepository incidents;
    private final RouterManagementService management;
    private final RouterLiveProbe liveProbe;
    private final ObjectMapper mapper;

    /* ---------- overview ---------- */

    @Transactional(readOnly = true)
    public NocDto.Overview overview() {
        List<Router> all = routers.findAll();
        long online = all.stream().filter(r -> "online".equalsIgnoreCase(r.getStatus())).count();
        long offline = all.stream().filter(r -> "offline".equalsIgnoreCase(r.getStatus())).count();
        long attention = all.stream().filter(this::needsAttention).count();
        long tenantCount = tenants.findAll().stream()
                .filter(t -> !"ROLE_SUPERADMIN".equals(t.getRole())).count();

        OffsetDateTime staleCut = OffsetDateTime.now().minusMinutes(STALE_AFTER_MINUTES);
        long freshOpen = radAcct.countFreshOpenSince(staleCut);
        long allOpen = radAcct.countAllOpen();

        long data24 = 0;
        List<Object[]> d = radAcct.platformDataSince(OffsetDateTime.now().minusHours(24));
        if (!d.isEmpty()) {
            data24 = ((Number) d.get(0)[0]).longValue() + ((Number) d.get(0)[1]).longValue();
        }
        long authFails = radPostAuth.countByReplySince("Access-Reject", OffsetDateTime.now().minusHours(24));
        long failedTasks = tasks.findAll().stream()
                .filter(t -> "FAILED".equals(t.getStatus())
                        && t.getCreatedAt() != null && t.getCreatedAt().isAfter(OffsetDateTime.now().minusDays(7)))
                .count();
        List<NocIncident> open = incidents.findAllByResolvedAtIsNullOrderByOpenedAtDesc();

        return new NocDto.Overview(
                tenantCount, all.size(), online, offline, attention,
                freshOpen, Math.max(0, allOpen - freshOpen), data24,
                authFails, failedTasks, open.size(),
                open.stream().map(this::toIncidentItem).toList());
    }

    /* ---------- tenants ---------- */

    @Transactional(readOnly = true)
    public List<NocDto.TenantItem> tenantItems() {
        Map<Long, List<Router>> byTenant = routers.findAll().stream()
                .collect(Collectors.groupingBy(r -> r.getTenant().getId()));
        return tenants.findAll().stream()
                .filter(t -> !"ROLE_SUPERADMIN".equals(t.getRole()))
                .map(t -> {
                    List<Router> rs = byTenant.getOrDefault(t.getId(), List.of());
                    return new NocDto.TenantItem(
                            t.getId(), t.getName(), t.getStatus(), rs.size(),
                            rs.stream().filter(r -> "online".equalsIgnoreCase(r.getStatus())).count(),
                            rs.stream().filter(this::needsAttention).count());
                })
                .sorted(Comparator.comparing(NocDto.TenantItem::tenantName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /* ---------- router fleet ---------- */

    @Transactional(readOnly = true)
    public List<NocDto.RouterRow> fleet() {
        Map<String, Long> openByNas = radAcct.openCountsByNas().stream()
                .collect(Collectors.toMap(o -> (String) o[0], o -> (Long) o[1]));
        Map<Long, Long> incidentsByRouter = incidents.findAllByResolvedAtIsNullOrderByOpenedAtDesc().stream()
                .collect(Collectors.groupingBy(i -> i.getRouter().getId(), Collectors.counting()));
        return routers.findAll().stream()
                .filter(r -> !"DELETED".equalsIgnoreCase(r.getStatus()))
                .map(r -> toRow(r, openByNas.getOrDefault(nasIp(r), 0L),
                        incidentsByRouter.getOrDefault(r.getId(), 0L)))
                .sorted(Comparator.comparing(NocDto.RouterRow::tenantName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(NocDto.RouterRow::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /* ---------- router detail ---------- */

    @Transactional(readOnly = true)
    public NocDto.RouterDetail detail(Long routerId) {
        Router r = find(routerId);
        String ip = nasIp(r);
        boolean nasRegistered = ip != null && nasRepository.findByNasname(ip).isPresent();
        OffsetDateTime lastAcct = ip == null ? null : radAcct.lastAccountingAt(ip);
        Map<Long, Long> inc = incidents.findAllByResolvedAtIsNullOrderByOpenedAtDesc().stream()
                .collect(Collectors.groupingBy(i -> i.getRouter().getId(), Collectors.counting()));
        long openSess = ip == null ? 0 : radAcct.findAllByNasIpAddressAndAcctStopTimeIsNull(ip).size();
        return new NocDto.RouterDetail(
                toRow(r, openSess, inc.getOrDefault(r.getId(), 0L)),
                nasRegistered ? "Registered" : ip == null ? "Unavailable" : "Not registered",
                lastAcct, null, null);
    }

    /** Live probe — performed on demand, never cached longer than 30s. */
    @Transactional(readOnly = true)
    public Map<String, Object> live(Long routerId) {
        Router r = find(routerId);
        var res = liveProbe.probe(r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reachable", res.reachable());
        out.put("source", res.reachable() ? "LIVE" : "UNAVAILABLE");
        // When the router actually answered (results are cached up to 30 s), not "now".
        java.time.Instant at = liveProbe.probedAt(r.getId());
        out.put("probedAt", (at != null ? at.atOffset(java.time.ZoneOffset.UTC) : OffsetDateTime.now()).toString());
        // Who the router reports as connected right now — live, unlike accounting rows.
        out.put("hotspotUsers", res.hotspotHosts());
        if (res.resource() != null) {
            Object up = res.resource().get("uptime");
            Object cpu = res.resource().get("cpu-load");
            Object free = res.resource().get("free-memory");
            Object total = res.resource().get("total-memory");
            Object ver = res.resource().get("version");
            Object board = res.resource().get("board-name");
            out.put("uptime", up);
            out.put("cpuLoad", cpu);
            out.put("boardName", board);
            out.put("routerOsVersion", ver);
            if (free != null && total != null) {
                long f = Long.parseLong(String.valueOf(free)), t = Long.parseLong(String.valueOf(total));
                if (t > 0) out.put("memoryUsagePct", Math.round((t - f) * 100.0 / t));
            }
        }
        out.put("hotspotActive", res.hotspotActive());
        out.put("wgHandshakeSecondsAgo", res.wgHandshakeSecondsAgo());
        out.put("error", res.error());
        return out;
    }

    /* ---------- sessions & accounting ---------- */

    @Transactional(readOnly = true)
    public NocDto.PageResponse<NocDto.SessionRow> sessions(Long routerId, String username,
                                                         boolean openOnly, String state,
                                                         int page, int size) {
        Router r = find(routerId);
        String ip = requireNas(r);
        Pageable p = page(page, size);
        Page<RadAcct> pg = radAcct.findByNas(ip, blankToNull(username), openOnly, null, null, null, p);
        Map<String, Voucher> vouchers = voucherLookup(r, pg.getContent());
        Map<Long, PackagePlan> plans = planLookup(r);
        OffsetDateTime staleCut = OffsetDateTime.now().minusMinutes(STALE_AFTER_MINUTES);
        List<NocDto.SessionRow> rows = pg.getContent().stream()
                .map(a -> toSessionRow(a, staleCut, vouchers, plans))
                .filter(s -> state == null || state.isBlank() || state.equalsIgnoreCase(s.state()))
                .toList();
        return new NocDto.PageResponse<>(rows, pg.getNumber(), pg.getSize(),
                pg.getTotalElements(), pg.getTotalPages());
    }

    @Transactional(readOnly = true)
    public NocDto.PageResponse<NocDto.SessionRow> accounting(Long routerId, String username,
                                                           String cause, OffsetDateTime from, OffsetDateTime to,
                                                           int page, int size) {
        Router r = find(routerId);
        String ip = requireNas(r);
        Page<RadAcct> pg = radAcct.findByNas(ip, blankToNull(username), false, blankToNull(cause),
                from, to, page(page, size));
        Map<String, Voucher> vouchers = voucherLookup(r, pg.getContent());
        Map<Long, PackagePlan> plans = planLookup(r);
        OffsetDateTime staleCut = OffsetDateTime.now().minusMinutes(STALE_AFTER_MINUTES);
        List<NocDto.SessionRow> rows = pg.getContent().stream()
                .map(a -> toSessionRow(a, staleCut, vouchers, plans)).toList();
        return new NocDto.PageResponse<>(rows, pg.getNumber(), pg.getSize(),
                pg.getTotalElements(), pg.getTotalPages());
    }

    @Transactional(readOnly = true)
    public List<NocDto.UsagePoint> usage(Long routerId, int days) {
        Router r = find(routerId);
        String ip = requireNas(r);
        return radAcct.nasDailyUsage(ip, OffsetDateTime.now().minusDays(Math.min(days, 90))).stream()
                .map(o -> new NocDto.UsagePoint(String.valueOf(o[0]),
                        ((Number) o[1]).longValue(), ((Number) o[2]).longValue(),
                        ((Number) o[3]).longValue()))
                .toList();
    }

    /* ---------- auth events ---------- */

    @Transactional(readOnly = true)
    public NocDto.PageResponse<NocDto.AuthEventRow> authEvents(Long routerId, String reply,
                                                             String username,
                                                             OffsetDateTime from, OffsetDateTime to,
                                                             int page, int size) {
        Router r = find(routerId);
        Set<String> codes = voucherRepository.codesByTenant(r.getTenant().getId());
        if (codes.isEmpty()) {
            return new NocDto.PageResponse<>(List.of(), 0, size, 0, 0);
        }
        Page<RadPostAuth> pg = radPostAuth.findEvents(codes, blankToNull(reply),
                blankToNull(username), from, to, page(page, size));
        List<NocDto.AuthEventRow> rows = pg.getContent().stream().map(e -> new NocDto.AuthEventRow(
                e.getId(), e.getAuthDate(), e.getUsername(),
                e.getReply() == null ? "UNKNOWN" : e.getReply(),
                e.getCallingStationId(),
                "Access-Reject".equals(e.getReply()) ? "WARNING" : "INFO",
                describe(e))).toList();
        return new NocDto.PageResponse<>(rows, pg.getNumber(), pg.getSize(),
                pg.getTotalElements(), pg.getTotalPages());
    }

    private static String describe(RadPostAuth e) {
        if ("Access-Accept".equals(e.getReply())) return "Authentication accepted";
        if ("Access-Reject".equals(e.getReply())) {
            return "Access rejected (expired voucher, already-in-use session, or invalid credentials)";
        }
        return e.getReply() == null ? "Authentication event" : "RADIUS reply: " + e.getReply();
    }

    /* ---------- provisioning & tasks ---------- */

    @Transactional(readOnly = true)
    public List<NocDto.TaskItem> taskHistory(Long routerId) {
        Router r = find(routerId);
        return tasks.findAllByRouterIdOrderByCreatedAtDesc(r.getId()).stream().limit(50)
                .map(t -> new NocDto.TaskItem(t.getId(), t.getAction(), t.getStatus(),
                        t.getCreatedAt(), t.getAppliedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> hosts(Long routerId) {
        Router r = find(routerId);
        try {
            JsonNode hosts = mapper.readTree(r.getHostsJson() == null ? "[]" : r.getHostsJson());
            return Map.of("hosts", hosts,
                    "seenAt", r.getHostsSeenAt() == null ? "" : r.getHostsSeenAt().toString(),
                    "source", "router telemetry push");
        } catch (Exception e) {
            return Map.of("hosts", List.of(), "seenAt", "", "source", "unavailable");
        }
    }

    /* ---------- events timeline ---------- */

    @Transactional(readOnly = true)
    public List<NocDto.EventItem> events(Long routerId) {
        Router r = find(routerId);
        List<NocDto.EventItem> ev = new ArrayList<>();

        tasks.findAllByRouterIdOrderByCreatedAtDesc(r.getId()).stream().limit(30).forEach(t ->
                ev.add(new NocDto.EventItem(t.getCreatedAt(), "TASK",
                        "FAILED".equals(t.getStatus()) ? "CRITICAL" : "INFO",
                        "Task " + t.getAction() + " — " + t.getStatus().toLowerCase(),
                        t.getAppliedAt() != null ? "Applied at " + t.getAppliedAt() : "Not yet applied")));

        incidents.findTop50ByRouterIdOrderByOpenedAtDesc(r.getId()).forEach(i ->
                ev.add(new NocDto.EventItem(i.getOpenedAt(), "INCIDENT", i.getSeverity(), i.getTitle(), i.getDetail())));

        String ip = nasIp(r);
        if (ip != null) {
            radAcct.findByNas(ip, null, false, null,
                    OffsetDateTime.now().minusDays(7), null, PageRequest.of(0, 20))
                    .getContent().forEach(a -> ev.add(new NocDto.EventItem(
                            a.getAcctStartTime() != null ? a.getAcctStartTime() : a.getAcctStopTime(),
                            "SESSION", "INFO",
                            (a.getAcctStopTime() == null ? "Session started" : "Session ended") + " — " + a.getUsername(),
                            a.getAcctTerminateCause() != null ? "Terminated: " + a.getAcctTerminateCause() : null)));
        }

        if (r.getLastSeen() != null) {
            ev.add(new NocDto.EventItem(r.getLastSeen(), "TELEMETRY", "INFO",
                    "Last telemetry report", "Router reported CPU " + r.getCpuLoad() + "%, "
                            + r.getActiveUsers() + " active user(s)"));
        }
        return ev.stream().filter(e -> e.at() != null)
                .sorted(Comparator.comparing(NocDto.EventItem::at).reversed())
                .limit(80).toList();
    }

    /* ---------- incidents ---------- */

    @Transactional(readOnly = true)
    public List<NocDto.IncidentItem> incidentsFor(Long routerId) {
        Router r = find(routerId);
        return incidents.findTop50ByRouterIdOrderByOpenedAtDesc(r.getId()).stream()
                .map(this::toIncidentItem).toList();
    }

    /* ---------- diagnostics ---------- */

    @Transactional(readOnly = true)
    public NocDto.Diagnostics diagnostics(Long routerId) {
        Router r = find(routerId);
        String ip = nasIp(r);
        List<NocDto.DiagnosticCheck> checks = new ArrayList<>();

        // Telemetry freshness — inferred
        boolean fresh = r.getLastSeen() != null
                && r.getLastSeen().isAfter(OffsetDateTime.now().minusMinutes(8));
        checks.add(new NocDto.DiagnosticCheck("telemetry", "Router telemetry",
                r.getLastSeen() == null ? "UNKNOWN" : fresh ? "HEALTHY" : "CRITICAL",
                "INFERRED",
                r.getLastSeen() == null ? "No telemetry ever received"
                        : "Last report " + r.getLastSeen(),
                fresh ? null : "Check the router is powered on and can reach the internet; verify the wb-telemetry scheduler exists"));

        // Readiness flags — inferred
        List<Boolean> flags = java.util.Arrays.asList(r.getHotspotReady(), r.getRadiusReady(), r.getPortalReady());
        boolean allTrue = flags.stream().allMatch(Boolean.TRUE::equals);
        boolean anyFalse = flags.stream().anyMatch(Boolean.FALSE::equals);
        checks.add(new NocDto.DiagnosticCheck("services", "Hotspot / RADIUS / portal readiness",
                allTrue ? "HEALTHY" : anyFalse ? "CRITICAL" : "UNKNOWN",
                "INFERRED",
                "hotspot=" + r.getHotspotReady() + " radius=" + r.getRadiusReady() + " portal=" + r.getPortalReady(),
                allTrue ? null : "A service reports not ready — check the hotspot server, RADIUS client config and portal files on the router"));

        // WireGuard — inferred from key registration + telemetry
        boolean keyReg = r.getWgPublicKey() != null && !r.getWgPublicKey().isBlank();
        checks.add(new NocDto.DiagnosticCheck("wireguard", "WireGuard tunnel",
                !keyReg ? "WARNING" : fresh ? "HEALTHY" : "WARNING",
                "INFERRED",
                !keyReg ? "Public key never registered"
                        : "Key registered; tunnel IP " + ip + (fresh ? "; telemetry flowing" : "; no recent telemetry"),
                !keyReg ? "Re-run provisioning or check the wb-register scheduler on the router"
                        : fresh ? null : "Tunnel may be down — check internet connectivity and the hub peer config"));

        // RADIUS NAS — inferred from nas table
        boolean nasOk = ip != null && nasRepository.findByNasname(ip).isPresent();
        checks.add(new NocDto.DiagnosticCheck("radius", "RADIUS client registration",
                nasOk ? "HEALTHY" : "CRITICAL", "INFERRED",
                nasOk ? "NAS registered for " + ip : "No nas entry for this router's tunnel IP",
                nasOk ? null : "Re-run provisioning so the router registers as a FreeRADIUS client"));

        // Accounting freshness — inferred
        OffsetDateTime lastAcct = ip == null ? null : radAcct.lastAccountingAt(ip);
        checks.add(new NocDto.DiagnosticCheck("accounting", "RADIUS accounting",
                lastAcct == null ? "UNKNOWN"
                        : lastAcct.isAfter(OffsetDateTime.now().minusHours(24)) ? "HEALTHY" : "WARNING",
                "INFERRED",
                lastAcct == null ? "No accounting records yet" : "Last accounting update " + lastAcct,
                lastAcct == null ? "Expected once a customer logs in"
                        : "Accounting is stale — verify interim updates are enabled on the router's RADIUS client"));

        // Bindings sync — inferred
        boolean synced = management.bindingsSynced(r);
        checks.add(new NocDto.DiagnosticCheck("bindings", "IP-binding synchronization",
                synced ? "HEALTHY" : "WARNING", "INFERRED",
                synced ? "Router acknowledged current bindings"
                        : "Pending or never acknowledged (last sync " + r.getBindingsSyncedAt() + ")",
                synced ? null : "Bindings push at next management poll; check router connectivity if stale"));

        // Failed tasks — inferred
        long failed = tasks.findAllByRouterIdOrderByCreatedAtDesc(r.getId()).stream()
                .filter(t -> "FAILED".equals(t.getStatus())).limit(50).count();
        checks.add(new NocDto.DiagnosticCheck("tasks", "Router task history",
                failed == 0 ? "HEALTHY" : "WARNING", "INFERRED",
                failed + " failed task(s) in recent history",
                failed == 0 ? null : "Inspect the provisioning/task log below for the failing script step"));

        // Live probe — an actual live test
        var live = liveProbe.probe(r);
        checks.add(new NocDto.DiagnosticCheck("live", "Live REST reachability",
                live.reachable() ? "HEALTHY" : (fresh ? "WARNING" : "CRITICAL"),
                "LIVE",
                live.reachable()
                        ? "Router REST answered over WireGuard (hotspot active: "
                          + (live.hotspotActive() == null ? "n/a" : live.hotspotActive()) + ")"
                        : "REST query failed: " + live.error(),
                live.reachable() ? null : "Router may be offline, the tunnel down, or the wb-api user missing — verify provisioning completed"));

        return new NocDto.Diagnostics(checks);
    }

    /* ---------- helpers ---------- */

    private NocDto.IncidentItem toIncidentItem(NocIncident i) {
        Router r = i.getRouter();
        return new NocDto.IncidentItem(i.getId(), r.getId(), r.getName(),
                r.getTenant().getName(), i.getType(), i.getSeverity(), i.getTitle(),
                i.getDetail(), i.getOpenedAt(), i.getResolvedAt());
    }

    private NocDto.RouterRow toRow(Router r, long openSess, long openInc) {
        return new NocDto.RouterRow(
                r.getId(), r.getTenant().getId(), r.getTenant().getName(), r.getName(),
                r.getStatus(), r.getWgIp(), r.getCpuLoad(), r.getMemoryUsage(), r.getUptime(),
                r.getActiveUsers(), r.getHotspotReady(), r.getRadiusReady(), r.getPortalReady(),
                management.bindingsSynced(r), provisioningState(r),
                r.getLastSeen(), r.getBindingsSyncedAt(), r.getHostsSeenAt(),
                openSess, openInc, r.getCreatedAt());
    }

    private String provisioningState(Router r) {
        return switch (r.getStatus() == null ? "" : r.getStatus().toLowerCase()) {
            case "pending" -> "Not started";
            case "provisioning" -> "In progress";
            case "registered" -> "Awaiting import";
            case "provisioned" -> "Provisioned";
            case "online", "degraded" -> "Provisioned";
            case "offline" -> "Provisioned";
            default -> r.getStatus();
        };
    }

    boolean needsAttention(Router r) {
        return "degraded".equalsIgnoreCase(r.getStatus())
                || Boolean.FALSE.equals(r.getHotspotReady())
                || Boolean.FALSE.equals(r.getRadiusReady())
                || Boolean.FALSE.equals(r.getPortalReady())
                || !management.bindingsSynced(r)
                || (r.getWgPublicKey() == null && !"pending".equalsIgnoreCase(r.getStatus()));
    }

    private NocDto.SessionRow toSessionRow(RadAcct a, OffsetDateTime staleCut,
                                           Map<String, Voucher> vouchers,
                                           Map<Long, PackagePlan> plans) {
        String state;
        if (a.getAcctStopTime() != null) state = "CLOSED";
        else if (Optional.ofNullable(a.getAcctUpdateTime()).orElse(a.getAcctStartTime()) != null
                && Optional.ofNullable(a.getAcctUpdateTime()).orElse(a.getAcctStartTime()).isAfter(staleCut))
            state = "LIVE";
        else state = "OPEN_STALE";
        Voucher v = vouchers.get(a.getUsername());
        PackagePlan plan = v == null ? null : plans.get(v.getPackagePlan() == null ? null : v.getPackagePlan().getId());
        Long remaining = null;
        if (v != null && plan != null && plan.getSessionTimeout() != null && a.getAcctStartTime() != null) {
            long elapsed = java.time.Duration.between(a.getAcctStartTime(), OffsetDateTime.now()).getSeconds();
            long used = a.getAcctSessionTime() != null ? Math.max(elapsed, a.getAcctSessionTime()) : elapsed;
            remaining = Math.max(0, plan.getSessionTimeout() - used);
        }
        return new NocDto.SessionRow(
                a.getRadAcctId(), a.getUsername(), a.getAcctSessionId(),
                a.getFramedIpAddress(), a.getCallingStationId(), a.getNasIpAddress(),
                a.getAcctStartTime(), a.getAcctUpdateTime(), a.getAcctStopTime(),
                a.getAcctSessionTime(), a.getAcctInputOctets(), a.getAcctOutputOctets(),
                a.getAcctTerminateCause(), state,
                plan == null ? null : plan.getId(), plan == null ? null : plan.getName(),
                remaining);
    }

    private Map<String, Voucher> voucherLookup(Router r, List<RadAcct> rows) {
        Set<String> names = rows.stream().map(RadAcct::getUsername).collect(Collectors.toSet());
        if (names.isEmpty()) return Map.of();
        return voucherRepository.findAllByCodeIn(names).stream()
                .filter(v -> v.getTenant().getId().equals(r.getTenant().getId()))
                .collect(Collectors.toMap(Voucher::getCode, v -> v, (a, b) -> a));
    }

    private Map<Long, PackagePlan> planLookup(Router r) {
        return packagePlanRepository.findAllByTenantIdOrderByPriceAsc(r.getTenant().getId()).stream()
                .collect(Collectors.toMap(PackagePlan::getId, p -> p));
    }

    private Router find(Long id) {
        return routers.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found"));
    }

    static String nasIp(Router r) {
        return Ipv4Subnet.stripPrefix(r.getWgIp());
    }

    private String requireNas(Router r) {
        String ip = nasIp(r);
        if (ip == null || ip.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Router has no WireGuard IP yet — RADIUS data cannot be scoped to it");
        }
        return ip;
    }

    private static Pageable page(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(MAX_PAGE, Math.max(1, size)));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
