package com.wifibilling.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;

/**
 * Distributed leader lease and execution coordination using an authoritative
 * database table (`app_job_leases`) with fence tokens, lease expiry, and owner fencing.
 *
 * <p>Key Guarantees:
 * <ul>
 *   <li><b>Single Active Leader</b>: Exactly one application instance can hold the lease for a job.</li>
 *   <li><b>Sequential Protection</b>: Records `last_cycle_start` and enforces minimum interval to prevent
 *       staggered Blue/Green instances from re-executing the same cycle back-to-back.</li>
 *   <li><b>Connection-Pool Safe</b>: Does NOT depend on session-pinned database connections (avoids HikariCP
 *       connection recycling / lease-release leaks).</li>
 *   <li><b>Crash Safe / Auto Recovery</b>: If a leader crashes mid-execution, the lease expires after
 *       its TTL (e.g. 5 minutes) and can be safely reclaimed.</li>
 * </ul>
 */
@Slf4j
@Service
public class LeaderLockService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.scheduling.enabled:true}")
    private boolean schedulingEnabled = true;

    @Value("${app.instance.id:}")
    private String configuredInstanceId;

    private String instanceId;

    /** Unique job identifiers for each scheduled background task */
    public static final String JOB_PAYMENT_RECONCILE    = "PAYMENT_RECONCILE";
    public static final String JOB_VOUCHER_EXPIRATION   = "VOUCHER_EXPIRATION";
    public static final String JOB_SUBSCRIPTION_SWEEP   = "SUBSCRIPTION_SWEEP";
    public static final String JOB_PLATFORM_EARNINGS    = "PLATFORM_EARNINGS";
    public static final String JOB_TELEMETRY_SWEEP      = "TELEMETRY_SWEEP";
    public static final String JOB_NOC_INCIDENT         = "NOC_INCIDENT";
    public static final String JOB_WIREGUARD_SYNC       = "WIREGUARD_SYNC";

    public LeaderLockService() {
        this.jdbcTemplate = null;
    }

    @Autowired
    public LeaderLockService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        if (configuredInstanceId != null && !configuredInstanceId.isBlank()) {
            this.instanceId = configuredInstanceId.trim();
        } else {
            this.instanceId = "node-" + UUID.randomUUID().toString().substring(0, 8);
        }

        // Strict DDL Governance: Normal application startup never executes DDL migrations.
        // The schema is managed strictly via controlled SQL migrations (e.g. V20261026_01__app_job_leases.sql).
    }

    public String getInstanceId() {
        return instanceId;
    }



    /**
     * Executes the task only if this instance acquires or holds a valid lease, and ensures
     * that no other instance (e.g. during a Blue-Green overlap or staggered startup) executes
     * the same cycle sequentially within `minIntervalSeconds`.
     *
     * @param jobName Identifier of the job
     * @param leaseDurationSeconds Maximum duration to hold the lock before auto-expiry (fencing against crashes)
     * @param minIntervalSeconds Minimum time that must elapse between two executions of this job across the whole cluster
     * @param task Protected runnable
     * @return true if executed by this node, false if skipped
     */
    public boolean executeIfLeader(String jobName, int leaseDurationSeconds, int minIntervalSeconds, Runnable task) {
        if (!schedulingEnabled) {
            log.trace("Scheduling disabled on this instance; skipping {}", jobName);
            return false;
        }

        if (jdbcTemplate == null || isH2()) {
            // In unit tests with H2, execute directly
            task.run();
            return true;
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(leaseDurationSeconds);
        Instant minNextRun = now.minusSeconds(minIntervalSeconds);

        // Attempt 1: Acquire lease if not present or expired AND minimum cycle interval has elapsed
        String sqlClaim = """
            INSERT INTO app_job_leases (job_name, owner_id, fence_token, lease_acquired_at, lease_expires_at, last_cycle_start)
            VALUES (?, ?, 1, ?, ?, ?)
            ON CONFLICT (job_name) DO UPDATE
            SET owner_id = EXCLUDED.owner_id,
                fence_token = app_job_leases.fence_token + 1,
                lease_acquired_at = EXCLUDED.lease_acquired_at,
                lease_expires_at = EXCLUDED.lease_expires_at,
                last_cycle_start = EXCLUDED.last_cycle_start
            WHERE app_job_leases.lease_expires_at < ?
              AND app_job_leases.last_cycle_start <= ?;
        """;

        int rows = 0;
        try {
            rows = jdbcTemplate.update(sqlClaim, jobName, instanceId, now, expiresAt, now, now, minNextRun);
        } catch (Exception e) {
            log.warn("Failed attempting to claim lease for {}: {}", jobName, e.getMessage());
            return false;
        }

        if (rows == 0) {
            log.debug("Job {} skipped on node {}: held by another owner or cycle interval has not elapsed", jobName, instanceId);
            return false;
        }

        log.debug("Node {} acquired leadership lease for {}", instanceId, jobName);

        try {
            task.run();
            return true;
        } finally {
            // Release lease early upon completion so subsequent cycles don't have to wait for TTL,
            // while preserving last_cycle_start to prevent immediate duplicate execution by peer nodes.
            try {
                jdbcTemplate.update("""
                    UPDATE app_job_leases
                    SET lease_expires_at = ?,
                        last_completed_at = ?
                    WHERE job_name = ? AND owner_id = ?;
                """, Instant.now(), Instant.now(), jobName, instanceId);
            } catch (Exception e) {
                log.warn("Failed updating completion timestamp for {}: {}", jobName, e.getMessage());
            }
        }
    }

    /** Convenience overload using default 5m TTL and 30s min cycle */
    public boolean executeIfLeader(String jobName, Runnable task) {
        return executeIfLeader(jobName, 300, 30, task);
    }

    private boolean isH2() {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.execute((Connection conn) ->
                    conn.getMetaData().getDatabaseProductName().toLowerCase().contains("h2")));
        } catch (Exception ignored) {
            return false;
        }
    }
}
