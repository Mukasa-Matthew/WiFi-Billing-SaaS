package com.wifibilling.service;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.model.Router;
import com.wifibilling.util.Ipv4Subnet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Read-only live queries against a router's REST API, reached through the
 * existing management channel: SSH to the WireGuard hub, then curl the
 * router over the private tunnel with the least-privilege wb-api identity
 * (same mechanism as triggerPortalSync). Results are cached briefly so the
 * NOC page never hammers a router, and every call has a hard timeout so an
 * offline router cannot stall the dashboard.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouterLiveProbe {
    private static final long CACHE_MS = 30_000;
    /** Hard ceiling for one probe (ssh connect 6 s + three curls of 5 s, plus slack). */
    static final long PROBE_TIMEOUT_MS = 25_000;
    /** Concurrent ssh probes across all routers, so a wall of offline routers can't pile up processes. */
    private static final int MAX_CONCURRENT = 4;
    private final PlatformProperties props;
    private final ObjectMapper mapper;
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<LiveResult>> inFlight = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);
    private record CacheEntry(Instant at, LiveResult result) {}
    /**
     * hotspotHosts: the router's /ip/hotspot/active list reduced to display
     * fields (user, address, macAddress, uptimeSeconds, bytesIn, bytesOut,
     * loginBy) — live state, distinct from RADIUS accounting records.
     */
    public record LiveResult(boolean reachable, Map<String, Object> resource,
                             Integer hotspotActive, Long wgHandshakeSecondsAgo,
                             List<Map<String, Object>> hotspotHosts, String error) {}

    /** Returns cached or freshly probed live state; never throws, never blocks past PROBE_TIMEOUT_MS. */
    public LiveResult probe(Router r) {
        CacheEntry e = cache.get(r.getId());
        if (e != null && e.at().isAfter(Instant.now().minusMillis(CACHE_MS))) return e.result();
        // One probe per router at a time; concurrent callers share its result.
        CompletableFuture<LiveResult> mine = new CompletableFuture<>();
        CompletableFuture<LiveResult> running = inFlight.putIfAbsent(r.getId(), mine);
        if (running != null) {
            try { return running.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
            catch (Exception ex) { return unavailable("probe timed out"); }
        }
        try {
            LiveResult result;
            if (!slots.tryAcquire()) {
                result = unavailable("management channel busy, try again shortly");
            } else {
                try { result = doProbe(r); } finally { slots.release(); }
                cache.put(r.getId(), new CacheEntry(Instant.now(), result));
            }
            mine.complete(result);
            return result;
        } finally {
            inFlight.remove(r.getId(), mine);
            mine.complete(unavailable("probe failed"));
        }
    }

    /** When the cached result for this router was taken, or null if none is cached. */
    public Instant probedAt(Long routerId) {
        CacheEntry e = cache.get(routerId);
        return e == null ? null : e.at();
    }

    private static LiveResult unavailable(String why) {
        return new LiveResult(false, null, null, null, null, why);
    }

    private LiveResult doProbe(Router r) {
        String ip = Ipv4Subnet.stripPrefix(r.getWgIp());
        String sshTarget = props.getPeerManagementSshTarget();
        if (ip == null || ip.isBlank() || sshTarget == null || sshTarget.isBlank()) {
            return unavailable("management channel not configured");
        }
        if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return unavailable("router tunnel address is invalid");
        Process p = null;
        try {
            String base = "curl -sS --max-time 5 -u " + shellQuote("wb-api:" + r.getRadiusSecret()) + " ";
            String remote = "(" + base + "http://" + ip + "/rest/system/resource; echo '\u0001';"
                    + base + "http://" + ip + "/rest/ip/hotspot/active; echo '\u0001';"
                    + base + "http://" + ip + "/rest/interface/wireguard/peers)";
            List<String> cmd = List.of("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=6",
                    "-o", "StrictHostKeyChecking=accept-new", sshTarget, remote);
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // Read on another thread: readAllBytes() blocks until the process
            // exits, so reading first would make the timeout below dead code.
            final Process proc = p;
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try { return new String(proc.getInputStream().readAllBytes()); } catch (Exception ex) { return ""; }
            });
            if (!p.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return unavailable("probe timed out");
            }
            return parse(output.get(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable("probe interrupted");
        } catch (Exception e) {
            // Never surface the exception text: it can echo the command line.
            log.debug("Live probe failed for router {}: {}", r.getId(), e.getClass().getSimpleName());
            return unavailable("probe failed");
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    /** Parses the three \u0001-separated REST responses. Output text is never echoed back. */
    LiveResult parse(String out) {
        String[] parts = out.split("\u0001", -1);
        Map<String, Object> resource = null;
        Integer hotspotActive = null;
        Long handshake = null;
        List<Map<String, Object>> hosts = null;
        try {
            JsonNode res = mapper.readTree(parts[0].trim());
            if (res.isObject() && res.has("uptime")) {
                resource = mapper.convertValue(res, Map.class);
            }
        } catch (Exception ignored) {}
        try {
            JsonNode active = mapper.readTree(parts.length > 1 ? parts[1].trim() : "");
            if (active.isArray()) {
                hotspotActive = active.size();
                hosts = new java.util.ArrayList<>();
                for (JsonNode a : active) {
                    Map<String, Object> h = new java.util.LinkedHashMap<>();
                    h.put("user", text(a, "user"));
                    h.put("address", text(a, "address"));
                    h.put("macAddress", text(a, "mac-address"));
                    h.put("uptimeSeconds", parseRosDuration(text(a, "uptime")));
                    h.put("bytesIn", number(a, "bytes-in"));
                    h.put("bytesOut", number(a, "bytes-out"));
                    h.put("loginBy", text(a, "login-by"));
                    hosts.add(h);
                }
            }
        } catch (Exception ignored) {}
        try {
            JsonNode peers = mapper.readTree(parts.length > 2 ? parts[2].trim() : "[]");
            if (peers.isArray() && !peers.isEmpty()) {
                String hs = peers.get(0).path("last-handshake").asText("");
                handshake = parseRosDuration(hs);
            }
        } catch (Exception ignored) {}
        if (resource == null && hotspotActive == null) {
            return unavailable("router REST unreachable or unauthorised");
        }
        return new LiveResult(true, resource, hotspotActive, handshake, hosts, null);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Long number(JsonNode n, String field) {
        String v = text(n, field);
        try { return v == null ? null : Long.parseLong(v); } catch (NumberFormatException e) { return null; }
    }

    /** RouterOS durations like "1m23s", "45s", "2h5m10s". */
    static Long parseRosDuration(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            long total = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)([wdhms])").matcher(s);
            while (m.find()) {
                long v = Long.parseLong(m.group(1));
                total += switch (m.group(2)) {
                    case "w" -> v * 604800; case "d" -> v * 86400;
                    case "h" -> v * 3600; case "m" -> v * 60; default -> v;
                };
            }
            return total;
        } catch (Exception e) {
            return null;
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
