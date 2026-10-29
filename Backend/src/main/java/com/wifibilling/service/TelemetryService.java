package com.wifibilling.service;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.dto.TelemetryRequest;
import com.wifibilling.model.Router;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.util.RouterTokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryService {

    public static final String STATUS_ONLINE = "online";
    public static final String STATUS_DEGRADED = "degraded";
    public static final String STATUS_OFFLINE = "offline";

    private final RouterRepository routerRepository;
    private final PlatformProperties props;
    private final EmailService emailService;
    private final EmailTemplates emailTemplates;
    private LeaderLockService leaderLockService = new LeaderLockService();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLeaderLockService(LeaderLockService leaderLockService) {
        this.leaderLockService = leaderLockService;
    }

    @Transactional
    public void record(TelemetryRequest req) {
        if (!RouterTokens.isValid(req.token())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown router token");
        }
        Router router = routerRepository.findByToken(req.token())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown router token"));

        if (req.cpuLoad() != null) {
            router.setCpuLoad(Math.min(100, Math.max(0, req.cpuLoad())));
        }
        if (req.totalMemory() != null && req.totalMemory() > 0 && req.freeMemory() != null) {
            long used = Math.max(0, req.totalMemory() - req.freeMemory());
            router.setMemoryUsage((int) Math.round(used * 100.0 / req.totalMemory()));
        }
        if (req.uptime() != null) {
            router.setUptime(req.uptime().length() > 50 ? req.uptime().substring(0, 50) : req.uptime());
        }
        if (req.activeUsers() != null) {
            router.setActiveUsers(req.activeUsers());
        }
        router.setLastSeen(OffsetDateTime.now());
        boolean reportsReadiness = req.hotspotReady() != null || req.radiusReady() != null || req.portalReady() != null;
        if (reportsReadiness) {
            router.setHotspotReady(req.hotspotReady());
            router.setRadiusReady(req.radiusReady());
            router.setPortalReady(req.portalReady());
        }
        boolean ready = Boolean.TRUE.equals(req.hotspotReady())
                && Boolean.TRUE.equals(req.radiusReady())
                && Boolean.TRUE.equals(req.portalReady());
        String previousStatus = router.getStatus();
        boolean wasOffline = STATUS_OFFLINE.equalsIgnoreCase(previousStatus) || router.getOfflineNotifiedAt() != null;
        // Older installed agents do not send readiness flags; preserve compatibility.
        String newStatus = !reportsReadiness || ready ? STATUS_ONLINE : STATUS_DEGRADED;
        router.setStatus(newStatus);

        if (wasOffline && (STATUS_ONLINE.equals(newStatus) || STATUS_DEGRADED.equals(newStatus))) {
            router.setOfflineNotifiedAt(null);
            notifyRouterOnline(router);
        }
    }

    /** Routers push every 2m; mark them offline after a few missed reports. */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    @Transactional
    public void markStaleRoutersOffline() {
        if (!leaderLockService.executeIfLeader(LeaderLockService.JOB_TELEMETRY_SWEEP, 120, 20, this::doMarkStaleRoutersOffline)) return;
    }

    @Transactional
    public void doMarkStaleRoutersOffline() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(props.getOfflineAfterMinutes());
        var staleList = routerRepository.findStaleRouters(cutoff);
        OffsetDateTime now = OffsetDateTime.now();
        int changed = routerRepository.markOfflineSeenBefore(cutoff, now);
        if (changed > 0) {
            log.info("Marked {} router(s) offline (no telemetry since {})", changed, cutoff);
            for (Router r : staleList) {
                notifyRouterOffline(r, cutoff);
            }
        }
    }

    private void notifyRouterOffline(Router router, OffsetDateTime lastSeenCutoff) {
        if (router.getTenant() == null || !Boolean.TRUE.equals(router.getTenant().getNotifyRouterOffline())) {
            return;
        }
        String recipient = router.getTenant().getEmail();
        if (recipient == null || recipient.isBlank()) return;

        String routerName = router.getName() != null ? router.getName() : "Router #" + router.getId();
        String subject = "🚨 Alert: Router \"" + routerName + "\" is Offline";
        String body = """
                <p style="margin:0 0 16px;font-size:14px;color:#334155;line-height:1.6;">
                  Your MikroTik router <strong>%s</strong> has lost connectivity or power and stopped reporting to AirPairy.
                </p>
                <div style="background:#FEF2F2;border:1px solid #FEE2E2;border-radius:10px;padding:16px;margin:18px 0;">
                  <p style="margin:0 0 8px;font-size:13px;color:#991B1B;"><strong>Details:</strong></p>
                  <ul style="margin:0;padding-left:18px;font-size:13px;color:#7F1D1D;line-height:1.5;">
                    <li><strong>Router:</strong> %s</li>
                    <li><strong>Tunnel IP:</strong> %s</li>
                    <li><strong>Last Seen:</strong> %s</li>
                    <li><strong>Likely Cause:</strong> Power failure, ISP outage, or physical disconnection.</li>
                  </ul>
                </div>
                <p style="margin:16px 0 0;font-size:13px;color:#64748B;line-height:1.6;">
                  We will monitor the connection and notify you immediately once the router comes back online.
                </p>
                """.formatted(routerName, routerName, router.getWgIp() != null ? router.getWgIp() : "—",
                router.getLastSeen() != null ? router.getLastSeen().toString() : "Unknown");

        try {
            emailService.send(recipient, subject, emailTemplates.shell("Router Offline Notice", body, ""));
            // Also notify any additional comma-separated alert emails configured
            sendAdditionalAlerts(router.getTenant().getNotifyEmails(), subject, body);
        } catch (Exception e) {
            log.warn("Failed sending offline alert email for router {}: {}", router.getId(), e.getMessage());
        }
    }

    private void notifyRouterOnline(Router router) {
        if (router.getTenant() == null || !Boolean.TRUE.equals(router.getTenant().getNotifyRouterOffline())) {
            return;
        }
        String recipient = router.getTenant().getEmail();
        if (recipient == null || recipient.isBlank()) return;

        String routerName = router.getName() != null ? router.getName() : "Router #" + router.getId();
        String subject = "✅ Resolved: Router \"" + routerName + "\" is Back Online";
        String body = """
                <p style="margin:0 0 16px;font-size:14px;color:#334155;line-height:1.6;">
                  Good news! Your MikroTik router <strong>%s</strong> has restored connectivity and is operating normally.
                </p>
                <div style="background:#F0FDF4;border:1px solid #DCFCE7;border-radius:10px;padding:16px;margin:18px 0;">
                  <p style="margin:0 0 8px;font-size:13px;color:#166534;"><strong>Connection Details:</strong></p>
                  <ul style="margin:0;padding-left:18px;font-size:13px;color:#14532D;line-height:1.5;">
                    <li><strong>Router:</strong> %s</li>
                    <li><strong>Tunnel IP:</strong> %s</li>
                    <li><strong>Active Users:</strong> %d</li>
                    <li><strong>Uptime:</strong> %s</li>
                  </ul>
                </div>
                <p style="margin:16px 0 0;font-size:13px;color:#64748B;line-height:1.6;">
                  The hotspot captive portal and voucher authentication are active and serving customers.
                </p>
                """.formatted(routerName, routerName, router.getWgIp() != null ? router.getWgIp() : "—",
                router.getActiveUsers() != null ? router.getActiveUsers() : 0,
                router.getUptime() != null ? router.getUptime() : "Just started");

        try {
            emailService.send(recipient, subject, emailTemplates.shell("Router Restored Notice", body, ""));
            sendAdditionalAlerts(router.getTenant().getNotifyEmails(), subject, body);
        } catch (Exception e) {
            log.warn("Failed sending online recovery email for router {}: {}", router.getId(), e.getMessage());
        }
    }

    private void sendAdditionalAlerts(String notifyEmails, String subject, String body) {
        if (notifyEmails == null || notifyEmails.isBlank()) return;
        for (String email : notifyEmails.split(",")) {
            String clean = email.trim();
            if (clean.contains("@")) {
                try {
                    emailService.send(clean, subject, emailTemplates.shell(subject, body, ""));
                } catch (Exception e) {
                    log.warn("Failed to send notification to secondary email {}: {}", clean, e.getMessage());
                }
            }
        }
    }
}
