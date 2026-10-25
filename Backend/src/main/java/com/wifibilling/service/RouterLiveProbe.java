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
import java.util.concurrent.ConcurrentHashMap;
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

    private final PlatformProperties props;
    private final ObjectMapper mapper;

    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Instant at, Map<String, Object> result) {}

    public record LiveResult(boolean reachable, Map<String, Object> resource,
                             Integer hotspotActive, Long wgHandshakeSecondsAgo,
                             List<Map<String, Object>> hotspotHosts, String error) {}

    /** Returns cached or freshly probed live state; never throws. */
    public LiveResult probe(Router r) {
        CacheEntry e = cache.get(r.getId());
        if (e != null && e.at().isAfter(Instant.now().minusMillis(CACHE_MS))) {
            @SuppressWarnings("unchecked")
            LiveResult cached = (LiveResult) e.result().get("r");
            return cached;
        }
        LiveResult result = doProbe(r);
        cache.put(r.getId(), new CacheEntry(Instant.now(), Map.of("r", result)));
        return result;
    }

    private LiveResult doProbe(Router r) {
        String ip = Ipv4Subnet.stripPrefix(r.getWgIp());
        String sshTarget = props.getPeerManagementSshTarget();
        if (ip == null || ip.isBlank() || sshTarget == null || sshTarget.isBlank()) {
            return new LiveResult(false, null, null, null, null, "management channel not configured");
        }
        try {
            String base = "curl -sS --max-time 5 -u " + shellQuote("wb-api:" + r.getRadiusSecret()) + " ";
            String remote = "(" + base + "http://" + ip + "/rest/system/resource; echo '\u0001';"
                    + base + "http://" + ip + "/rest/ip/hotspot/active; echo '\u0001';"
                    + base + "http://" + ip + "/rest/interface/wireguard/peers)";
            List<String> cmd = List.of("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=6",
                    "-o", "StrictHostKeyChecking=accept-new", sshTarget, remote);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new LiveResult(false, null, null, null, null, "probe timed out");
            }
            String[] parts = out.split("\u0001", -1);
            Map<String, Object> resource = null;
            Integer hotspotActive = null;
            Long handshake = null;
            try {
                JsonNode res = mapper.readTree(parts[0].trim());
                if (res.isObject() && res.has("uptime")) {
                    resource = mapper.convertValue(res, Map.class);
                }
            } catch (Exception ignored) {}
            try {
                JsonNode active = mapper.readTree(parts.length > 1 ? parts[1].trim() : "[]");
                if (active.isArray()) hotspotActive = active.size();
            } catch (Exception ignored) {}
            try {
                JsonNode peers = mapper.readTree(parts.length > 2 ? parts[2].trim() : "[]");
                if (peers.isArray() && !peers.isEmpty()) {
                    String hs = peers.get(0).path("last-handshake").asText("");
                    handshake = parseRosDuration(hs);
                }
            } catch (Exception ignored) {}
            if (resource == null && hotspotActive == null) {
                return new LiveResult(false, null, null, null, null, "router REST unreachable or unauthorised");
            }
            return new LiveResult(true, resource, hotspotActive, handshake, null, null);
        } catch (Exception e) {
            log.debug("Live probe failed for router {}: {}", r.getId(), e.getMessage());
            return new LiveResult(false, null, null, null, null, "probe failed: " + e.getMessage());
        }
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
