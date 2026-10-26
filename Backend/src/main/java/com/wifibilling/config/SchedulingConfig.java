package com.wifibilling.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring task scheduling conditionally.
 *
 * <p>Separation of Concerns:
 * <ul>
 *   <li><b>Web / API Instances (Blue / Green)</b>: Run with {@code app.scheduling.enabled=false}.
 *       They process HTTP/REST/WebSocket traffic with zero background task overhead or scheduler conflicts.</li>
 *   <li><b>Dedicated Worker Service (wifi-worker)</b>: Runs with {@code app.scheduling.enabled=true}.
 *       It solely owns and executes background scheduled jobs protected by database leases.</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = false)
@EnableScheduling
public class SchedulingConfig {
}
