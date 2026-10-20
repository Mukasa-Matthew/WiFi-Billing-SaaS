package com.wifibilling.service;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.model.Router;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.util.Ipv4Subnet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * {@code wg set} changes are not persisted across a hub restart, so on startup we
 * re-apply every registered router as a peer. The database is the source of truth.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WireGuardPeerSync {

    private final RouterRepository routerRepository;
    private final WireGuardPeerService peerService;
    private final PlatformProperties props;

    @EventListener(ApplicationReadyEvent.class)
    public void syncPeersOnStartup() {
        if (!props.isPeerManagementEnabled()) {
            log.info("WireGuard peer management disabled; skipping hub peer sync");
            return;
        }
        int ok = 0;
        var routers = routerRepository.findAllByWgPublicKeyIsNotNull();
        for (Router r : routers) {
            String ip = Ipv4Subnet.stripPrefix(r.getWgIp());
            if (ip != null && !ip.isBlank() && peerService.addPeer(r.getWgPublicKey(), ip)) {
                ok++;
            }
        }
        log.info("WireGuard hub peer sync complete: {}/{} peers applied", ok, routers.size());
    }
}
