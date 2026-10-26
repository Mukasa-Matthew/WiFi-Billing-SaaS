package com.wifibilling.service;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.model.Router;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.util.Ipv4Subnet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * {@code wg set} changes are not persisted across a hub restart, so on startup we
 * re-apply every registered router as a peer. The database is the source of truth.
 *
 * <p>Can be disabled on standby slots or secondary instances via {@code wireguard.peer-sync.enabled=false}
 * to prevent unnecessary SSH command execution during deployment validation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "wireguard.peer-sync.enabled", havingValue = "true", matchIfMissing = true)
public class WireGuardPeerSync {

    private final RouterRepository routerRepository;
    private final WireGuardPeerService peerService;
    private final PlatformProperties props;
    private final LeaderLockService leaderLockService;

    @EventListener(ApplicationReadyEvent.class)
    public void syncPeersOnStartup() {
        if (!props.isPeerManagementEnabled()) {
            log.info("WireGuard peer management disabled; skipping hub peer sync");
            return;
        }
        leaderLockService.executeIfLeader(LeaderLockService.JOB_WIREGUARD_SYNC, 300, 60, () -> {
            int ok = 0;
            var routers = routerRepository.findAllByWgPublicKeyIsNotNull();
            for (Router r : routers) {
                String ip = Ipv4Subnet.stripPrefix(r.getWgIp());
                if (ip != null && !ip.isBlank() && peerService.addPeer(r.getWgPublicKey(), ip)) {
                    ok++;
                }
            }
            log.info("WireGuard hub peer sync complete: {}/{} peers applied", ok, routers.size());
        });
    }
}
