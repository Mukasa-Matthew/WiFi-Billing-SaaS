package com.wifibilling.service;

import com.wifibilling.config.PlatformProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Live probes for the services the platform depends on, surfaced on the super-admin
 * System health page. Each probe is read-only, times out fast, and reports one of:
 * UP (working), DEGRADED (running but something looks wrong), DOWN (not working),
 * UNKNOWN (cannot tell, e.g. probing not applicable on this host).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemHealthService {

    public record ComponentHealth(String key, String status, String detail) {}
    public record Report(Instant checkedAt, List<ComponentHealth> components) {}

    private final JdbcTemplate jdbc;
    private final PlatformProperties props;

    public Report probe() {
        var components = new ArrayList<ComponentHealth>();
        components.add(postgres());
        components.add(freeradius());
        components.add(wireguard());
        components.add(voucherWorker());
        return new Report(Instant.now(), components);
    }

    private ComponentHealth postgres() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return new ComponentHealth("postgres", "UP", "radius_db answering queries");
        } catch (Exception e) {
            return new ComponentHealth("postgres", "DOWN", "query failed: " + e.getMessage());
        }
    }

    private ComponentHealth freeradius() {
        String service = exec("systemctl", "is-active", "freeradius");
        if (service == null) return new ComponentHealth("freeradius", "UNKNOWN", "cannot query systemd on this host");
        try {
            Integer recent = jdbc.queryForObject(
                    "SELECT count(*) FROM radacct WHERE acctupdatetime > now() - interval '5 minutes' OR acctstarttime > now() - interval '5 minutes'",
                    Integer.class);
            if (!"active".equals(service)) return new ComponentHealth("freeradius", "DOWN", "systemd reports " + service);
            String traffic = (recent != null && recent > 0) ? recent + " accounting updates in the last 5 min" : "no accounting traffic in the last 5 min";
            return new ComponentHealth("freeradius", "UP", "service active · " + traffic);
        } catch (Exception e) {
            return new ComponentHealth("freeradius", "active".equals(service) ? "UP" : "DOWN",
                    "service " + service + " · accounting check failed");
        }
    }

    private ComponentHealth wireguard() {
        if (!props.isPeerManagementEnabled()) {
            return new ComponentHealth("wireguard", "UNKNOWN", "peer management disabled on this host");
        }
        String dump = execSudo(props.getWgBinary(), "show", props.getWgInterface(), "dump");
        if (dump == null || dump.isBlank()) return new ComponentHealth("wireguard", "DOWN", "wg show failed or " + props.getWgInterface() + " is down");
        long now = Instant.now().getEpochSecond();
        int peers = 0, active = 0;
        for (String line : dump.split("\n")) {
            String[] f = line.split("\t");
            if (f.length < 8) continue; // first line is the interface itself
            peers++;
            try { if (now - Long.parseLong(f[5]) < 300) active++; } catch (NumberFormatException ignored) {}
        }
        String detail = peers + " peers · " + active + " handshook in the last 5 min";
        return new ComponentHealth("wireguard", peers == 0 ? "DEGRADED" : "UP", detail);
    }

    private ComponentHealth voucherWorker() {
        // The lifecycle pass runs in the dedicated wifi-worker process (scheduling is off on the API slots),
        // so freshness comes from the shared lease row it updates every cycle, not from anything in-process.
        try {
            java.sql.Timestamp last = jdbc.query("SELECT last_cycle_start FROM app_job_leases WHERE job_name = 'VOUCHER_EXPIRATION'",
                    (rs, i) -> rs.getTimestamp(1)).stream().findFirst().orElse(null);
            if (last == null) return new ComponentHealth("worker", "DOWN", "no lease row — worker has never run");
            long age = Instant.now().getEpochSecond() - last.toInstant().getEpochSecond();
            String service = exec("systemctl", "is-active", "wifi-worker");
            if (age < 120) {
                return new ComponentHealth("worker", "UP", "service " + service + " · last cycle " + age + "s ago");
            }
            return new ComponentHealth("worker", "DOWN", "service " + service + " · last cycle " + age + "s ago — expected every ~30s");
        } catch (Exception e) {
            return new ComponentHealth("worker", "UNKNOWN", "lease check failed: " + e.getMessage());
        }
    }

    private String execSudo(String binary, String... args) {
        var cmd = new ArrayList<String>();
        cmd.add("sudo");
        cmd.add("-n");
        cmd.add(binary);
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            try (InputStream in = p.getInputStream()) {
                String out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                return p.exitValue() == 0 ? out : null;
            }
        } catch (Exception e) {
            log.warn("health probe sudo exec failed: {}", e.getMessage());
            return null;
        }
    }

    /** Runs a command and returns stdout even on a non-zero exit, so callers can read states like "inactive". */
    private String exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            try (InputStream in = p.getInputStream()) {
                String out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                return out.isBlank() && p.exitValue() != 0 ? null : out;
            }
        } catch (Exception e) {
            log.warn("health probe exec failed: {}", e.getMessage());
            return null;
        }
    }
}
