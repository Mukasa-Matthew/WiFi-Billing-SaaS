package com.wifibilling.service;

import com.wifibilling.model.NocIncident;
import com.wifibilling.model.Router;
import com.wifibilling.repository.NocIncidentRepository;
import com.wifibilling.repository.RadAcctRepository;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.repository.RouterTaskRepository;
import com.wifibilling.util.Ipv4Subnet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Evaluates router conditions every minute and opens/resolves persisted
 * incidents. One open incident per (router, type); recovery closes it with a
 * resolved_at stamp so history survives restarts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NocIncidentService {

    private final RouterRepository routers;
    private final NocIncidentRepository incidents;
    private final RouterTaskRepository tasks;
    private final RadAcctRepository radAcct;
    private final RouterManagementService management;
    private LeaderLockService leaderLockService = new LeaderLockService();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLeaderLockService(LeaderLockService leaderLockService) {
        this.leaderLockService = leaderLockService;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT2M")
    @Transactional
    public void evaluate() {
        if (!leaderLockService.executeIfLeader(LeaderLockService.JOB_NOC_INCIDENT, 120, 20, this::doEvaluate)) return;
    }

    @Transactional
    public void doEvaluate() {
        List<Router> all = routers.findAll();
        OffsetDateTime now = OffsetDateTime.now();

        for (Router r : all) {
            // Skip routers that were never provisioned — nothing to alert on yet.
            boolean provisioned = !"pending".equalsIgnoreCase(r.getStatus())
                    && !"provisioning".equalsIgnoreCase(r.getStatus());

            // 1. Offline (was reporting, now stale)
            boolean offline = "offline".equalsIgnoreCase(r.getStatus());
            apply(r, NocIncident.ROUTER_OFFLINE, offline,
                    "CRITICAL",
                    "Router offline",
                    "No telemetry since " + r.getLastSeen());

            // 2. Degraded readiness
            boolean degraded = "degraded".equalsIgnoreCase(r.getStatus())
                    || Boolean.FALSE.equals(r.getHotspotReady())
                    || Boolean.FALSE.equals(r.getRadiusReady())
                    || Boolean.FALSE.equals(r.getPortalReady());
            apply(r, NocIncident.ROUTER_DEGRADED, provisioned && degraded && !offline,
                    "WARNING",
                    "Router reports service not ready",
                    "hotspot=" + r.getHotspotReady() + " radius=" + r.getRadiusReady()
                            + " portal=" + r.getPortalReady());

            // 3. Failed router task within the last day
            boolean failedTask = tasks.findAllByRouterIdOrderByCreatedAtDesc(r.getId()).stream()
                    .limit(20)
                    .anyMatch(t -> "FAILED".equals(t.getStatus()) && t.getCreatedAt() != null
                            && t.getCreatedAt().isAfter(now.minusHours(24)));
            apply(r, NocIncident.SYNC_FAILED, provisioned && failedTask,
                    "WARNING",
                    "Router task failed",
                    "A management/sync task reported failure within the last 24h");

            // 4. High resource usage (only meaningful while reporting)
            boolean highCpu = r.getCpuLoad() != null && r.getCpuLoad() >= 90 && !offline;
            apply(r, NocIncident.HIGH_CPU, provisioned && highCpu,
                    "WARNING", "High CPU usage", "CPU at " + r.getCpuLoad() + "%");

            boolean highMem = r.getMemoryUsage() != null && r.getMemoryUsage() >= 90 && !offline;
            apply(r, NocIncident.HIGH_MEMORY, provisioned && highMem,
                    "WARNING", "Low available memory", "Memory usage at " + r.getMemoryUsage() + "%");

            // 5. Stale accounting while router claims to be online and has users
            String ip = Ipv4Subnet.stripPrefix(r.getWgIp());
            if (ip != null && provisioned && !offline && r.getActiveUsers() != null && r.getActiveUsers() > 0) {
                OffsetDateTime last = radAcct.lastAccountingAt(ip);
                boolean stale = last == null || last.isBefore(now.minusMinutes(STALE_AFTER_MINUTES()));
                apply(r, NocIncident.ACCOUNTING_STALE, stale,
                        "WARNING",
                        "RADIUS accounting stale",
                        "Router reports " + r.getActiveUsers() + " active user(s) but last accounting update was "
                                + (last == null ? "never" : last.toString()));
            } else {
                apply(r, NocIncident.ACCOUNTING_STALE, false, "WARNING", null, null);
            }
        }
    }

    private static long STALE_AFTER_MINUTES() { return 30; }

    private void apply(Router r, String type, boolean active, String severity, String title, String detail) {
        var open = incidents.findFirstByRouterIdAndTypeAndResolvedAtIsNull(r.getId(), type);
        if (active && open.isEmpty()) {
            incidents.save(NocIncident.builder()
                    .router(r).type(type).severity(severity).title(title).detail(detail)
                    .openedAt(OffsetDateTime.now()).build());
            log.info("NOC incident opened: {} on router {} ({})", type, r.getId(), r.getName());
        } else if (!active && open.isPresent()) {
            NocIncident i = open.get();
            i.setResolvedAt(OffsetDateTime.now());
            incidents.save(i);
            log.info("NOC incident resolved: {} on router {} ({})", type, r.getId(), r.getName());
        }
    }
}
