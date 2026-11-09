package com.wifibilling.service;

import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small in-memory sliding-window rate limiter for the public captive-portal
 * endpoints. Keyed by (action, device MAC) because portal calls arrive from
 * many devices behind the router's NAT — a single client IP cannot be used.
 *
 * Fixed one-minute buckets; entries self-clean when the map grows so a burst
 * of unique MACs can't grow memory without bound. Limits come from
 * {@code portal.rate-limit.<action>} (falling back to
 * {@code portal.rate-limit.default}, then 30/min) so tests and deployments
 * can tune them.
 */
@Service
public class PortalRateLimiter {

    private static final long WINDOW_MS = 60_000;
    private static final int MAX_KEYS = 50_000;
    private static final int DEFAULT_LIMIT = 30;

    private record Bucket(long windowStart, AtomicInteger count) {
        Bucket roll(long now) {
            return now - windowStart >= WINDOW_MS ? new Bucket(now, new AtomicInteger()) : this;
        }
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Environment env;

    public PortalRateLimiter(Environment env) { this.env = env; }

    /**
     * Allows {@code limit} calls per minute per (action, mac). Throws 429 past
     * the limit so the portal shows "too many attempts, wait a minute".
     * Disabled entirely by {@code portal.rate-limit.enabled=false} (tests).
     */
    public void check(String action, String mac, int limit) {
        if ("false".equalsIgnoreCase(env.getProperty("portal.rate-limit.enabled", "true"))) return;
        String normalized = mac == null || mac.isBlank() ? "unknown" : mac.toUpperCase();
        String key = action + "|" + normalized;
        long now = System.currentTimeMillis();
        if (buckets.size() > MAX_KEYS) buckets.clear();
        int count = buckets.compute(key, (k, b) -> {
            Bucket bucket = b == null ? new Bucket(now, new AtomicInteger()) : b.roll(now);
            bucket.count().incrementAndGet();
            return bucket;
        }).count().get();
        if (count > limit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts — wait a minute and try again");
        }
    }

    /** Same as {@link #check} with the configured limit for this action. */
    public void check(String action, String mac) {
        check(action, mac, env.getProperty("portal.rate-limit." + action, Integer.class,
                env.getProperty("portal.rate-limit.default", Integer.class, DEFAULT_LIMIT)));
    }
}
