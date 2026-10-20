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

    private final RouterRepository routerRepository;
    private final PlatformProperties props;

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
        router.setStatus(STATUS_ONLINE);
    }

    /** Routers push every 2m; mark them offline after a few missed reports. */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    @Transactional
    public void markStaleRoutersOffline() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(props.getOfflineAfterMinutes());
        int changed = routerRepository.markOfflineSeenBefore(cutoff);
        if (changed > 0) {
            log.info("Marked {} router(s) offline (no telemetry since {})", changed, cutoff);
        }
    }
}
