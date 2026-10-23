package com.wifibilling.service;

import com.wifibilling.config.PlatformProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages peers on the hub WireGuard interface by invoking {@code wg set}.
 *
 * <p>Arguments are passed as a list (no shell), and keys are validated upstream,
 * so there's no command-injection surface. When
 * {@code wireguard.peer-management.enabled=false} (e.g. local dev), commands are
 * only logged.
 *
 * <p>Requires a sudoers entry on cloudserv such as:
 * {@code <app-user> ALL=(root) NOPASSWD: /usr/bin/wg set wg0 *}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WireGuardPeerService {

    private static final long TIMEOUT_SECONDS = 10;

    private final PlatformProperties props;

    /** Adds the new peer and removes the previous key if the router re-keyed. */
    public boolean replacePeer(String oldPublicKey, String newPublicKey, String wgIp) {
        if (oldPublicKey != null && !oldPublicKey.isBlank() && !oldPublicKey.equals(newPublicKey)) {
            removePeer(oldPublicKey);
        }
        return addPeer(newPublicKey, wgIp);
    }

    public boolean addPeer(String publicKey, String wgIp) {
        return run(List.of("set", props.getWgInterface(), "peer", publicKey, "allowed-ips", wgIp + "/32"));
    }

    public boolean removePeer(String publicKey) {
        return run(List.of("set", props.getWgInterface(), "peer", publicKey, "remove"));
    }

    private boolean run(List<String> wgArgs) {
        List<String> cmd = new ArrayList<>();
        String sshTarget = props.getPeerManagementSshTarget();
        if (sshTarget != null && !sshTarget.isBlank()) {
            List<String> remote = new ArrayList<>();
            if (props.isPeerManagementUseSudo()) {
                remote.add("sudo");
                remote.add("-n");
            }
            remote.add(props.getWgBinary());
            remote.addAll(wgArgs);
            cmd.add("ssh");
            cmd.add("-o");
            cmd.add("BatchMode=yes");
            cmd.add("-o");
            cmd.add("ConnectTimeout=5");
            cmd.add("-o");
            cmd.add("StrictHostKeyChecking=accept-new");
            cmd.add(sshTarget);
            cmd.add(String.join(" ", remote));
        } else {
            if (props.isPeerManagementUseSudo()) {
                cmd.add("sudo");
                cmd.add("-n"); // never prompt for a password; fail fast instead
            }
            cmd.add(props.getWgBinary());
            cmd.addAll(wgArgs);
        }

        if (!props.isPeerManagementEnabled()) {
            log.info("[wg dry-run] {}", String.join(" ", cmd));
            return false;
        }

        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.error("WireGuard command timed out: {}", String.join(" ", cmd));
                return false;
            }
            String output;
            try (InputStream in = p.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (p.exitValue() != 0) {
                log.error("WireGuard command failed (exit {}): {} -> {}", p.exitValue(), String.join(" ", cmd), output);
                return false;
            }
            log.info("WireGuard peer updated: {}", String.join(" ", cmd));
            return true;
        } catch (IOException e) {
            log.error("Unable to execute WireGuard command: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
