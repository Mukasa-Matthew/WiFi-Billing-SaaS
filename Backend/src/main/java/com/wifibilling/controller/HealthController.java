package com.wifibilling.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health and readiness endpoint for deployment orchestrators and load balancers.
 *
 * <p>Validates:
 * <ul>
 *   <li>HTTP / API application context is running</li>
 *   <li>PostgreSQL database connectivity (read query)</li>
 *   <li>Application version</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    @org.springframework.beans.factory.annotation.Value("${app.version:1.0.0}")
    private String appVersion;

    @org.springframework.beans.factory.annotation.Value("${app.deploy.commit:development}")
    private String deployCommit;

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        try {
            Integer dbOk = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (dbOk != null && dbOk == 1) {
                return ResponseEntity.ok(Map.of(
                        "status", "UP",
                        "database", "CONNECTED",
                        "timestamp", System.currentTimeMillis()
                ));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "DOWN",
                    "database", "DISCONNECTED",
                    "timestamp", System.currentTimeMillis()
            ));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
    }

    /** Detailed internal readiness for deployment automation on localhost. */
    @GetMapping("/version")
    public ResponseEntity<Map<String, Object>> version() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "version", appVersion,
                "commit", deployCommit,
                "timestamp", System.currentTimeMillis()
        ));
    }
}
