package com.wifibilling.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * FreeRADIUS reads its allowed clients from the {@code nas} table only at
 * startup, so a freshly provisioned router gets silently ignored ("unknown
 * client") until the daemon restarts. This runs the privileged helper
 * {@code /usr/local/bin/airpairy-radius-reload} (a passwordless sudoers
 * entry) after provisioning commits a nas row. Calls are debounced on a
 * single background thread so back-to-back provisions can't thrash the
 * daemon; if the helper isn't installed yet, the failure is only logged —
 * provisioning itself still succeeds.
 */
@Service
public class RadiusClientReloader {

    private static final Logger log = LoggerFactory.getLogger(RadiusClientReloader.class);
    private static final String HELPER = "/usr/local/bin/airpairy-radius-reload";
    private static final long MIN_INTERVAL_MS = 15_000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "radius-client-reload");
        t.setDaemon(true);
        return t;
    });
    private long lastRun;

    /** Queue a FreeRADIUS client reload; never blocks or throws. */
    public void reload() {
        executor.execute(this::runGuarded);
    }

    private synchronized void runGuarded() {
        long wait = lastRun + MIN_INTERVAL_MS - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        lastRun = System.currentTimeMillis();
        try {
            Process p = new ProcessBuilder("sudo", "-n", HELPER)
                    .redirectErrorStream(true).start();
            boolean done = p.waitFor(30, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                log.warn("FreeRADIUS client reload timed out — new routers may not authenticate until the next restart");
            } else if (p.exitValue() != 0) {
                log.warn("FreeRADIUS client reload helper exited {} — is /usr/local/bin/airpairy-radius-reload installed with a sudoers rule?", p.exitValue());
            } else {
                log.info("FreeRADIUS reloaded: nas table clients refreshed");
            }
        } catch (Exception e) {
            log.warn("FreeRADIUS client reload failed: {}", e.getMessage());
        }
    }
}
