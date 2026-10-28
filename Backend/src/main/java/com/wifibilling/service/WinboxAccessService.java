package com.wifibilling.service;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.config.WinboxProperties;
import com.wifibilling.model.*;
import com.wifibilling.repository.*;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.util.Ipv4Subnet;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
@Transactional
public class WinboxAccessService {
    private final WinboxAccessRepository accesses;
    private final RouterRepository routers;
    private final RouterOperationsService authorization;
    private final EncryptionService encryption;
    private final RouterSetupService setup;
    private final AuditLogRepository audit;
    private final WinboxProperties config;
    private final PlatformProperties platform;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Status(boolean enabled, String state, String message, String address, String username,
                         String tunnelIp, OffsetDateTime lastSuccessfulCheck, boolean installed, boolean available) {}
    public record Credentials(String username, String password) {
        @Override public String toString() { return "WinboxCredentials[redacted]"; }
    }
    public record Installation(String path, OffsetDateTime expiresAt) {}
    public record Route(Long routerId, Long tenantId, int port, String tunnelIp, String peerKey, long revision) {}
    public record Routes(String publicHost, String hubIp, String subnet, int leaseSeconds, List<Route> routes) {}
    public record Check(Long routerId, long revision, String result) {}

    private Router allowed(TenantPrincipal p, Long id) {
        if (p == null || !("ROLE_ADMIN".equals(p.getRole()) || "ROLE_SUPERADMIN".equals(p.getRole())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return authorization.authorize(p, id);
    }

    private Router locked(TenantPrincipal p, Long id) {
        allowed(p, id);
        return routers.lockById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public Status status(TenantPrincipal p, Long id, String ip) {
        Router r = allowed(p, id);
        record(p, r, "WINBOX_VIEW", ip);
        WinboxAccess a = accesses.findByRouterId(id).orElse(null);
        if (a != null) revokeIfInvalid(a);
        return describe(r, a);
    }

    public Status change(TenantPrincipal p, Long id, String operation, String ip) {
        Router r = locked(p, id);
        WinboxAccess a = accesses.findByRouterId(id).orElse(null);
        if ("disable".equals(operation)) {
            if (a != null) { a.setEnabled(false); advance(a); }
        } else {
            if (!config.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Remote WinBox infrastructure is not configured.");
            if (!active(r)) throw new ResponseStatusException(HttpStatus.CONFLICT, "An active tenant and enrolled router are required.");
            if (a != null && !bound(a)) {
                if (!"enable".equals(operation)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Re-enable remote access and install a new agent after router ownership or tunnel changes.");
                audit.save(AuditLog.builder().tenantId(a.getTenantId()).actorEmail(p.getEmail()).action("WINBOX_BINDING_RETIRED")
                        .details("router=" + id).createdAt(OffsetDateTime.now()).build());
                accesses.delete(a); accesses.flush(); a = null;
            }
            if (a == null) {
                if (!"enable".equals(operation)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Enable remote access first.");
                a = new WinboxAccess();
                a.setRouter(r); a.setTenantId(r.getTenant().getId());
                a.setTunnelIp(Ipv4Subnet.stripPrefix(r.getWgIp())); a.setPeerKey(r.getWgPublicKey());
                a.setUsername("ap-wb-" + id + "-" + random().substring(0, 8));
                a.setPasswordCiphertext(encryption.encrypt(random()));
                a = accesses.saveAndFlush(a);
                if (a.getPort() < 30000 || a.getPort() > 39999)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "The dedicated WinBox port range is exhausted.");
            }
            if ("rotate".equals(operation)) {
                if (!a.isEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Enable remote access before rotating credentials.");
                a.setPasswordCiphertext(encryption.encrypt(random()));
            } else if (!"enable".equals(operation)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown remote-access operation.");
            a.setEnabled(true); advance(a);
        }
        record(p, r, "WINBOX_" + operation.toUpperCase(Locale.ROOT), ip);
        return describe(r, a);
    }

    public Installation installation(TenantPrincipal p, Long id, String ip) {
        Router r = locked(p, id);
        WinboxAccess a = accesses.findByRouterId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Enable remote access first."));
        if (!config.configured() || !a.isEnabled() || !active(r) || !bound(a))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Remote access is not eligible for installation.");
        var issued = setup.issueWinbox(r);
        record(p, r, "WINBOX_INSTALL_CODE", ip);
        return new Installation("/api/router/setup/" + issued.code() + "/install-winbox.rsc", issued.expiresAt());
    }

    public String installer(String code) {
        Long id = setup.redeem(code, RouterSetupCode.Purpose.WINBOX);
        WinboxAccess a = accesses.findByRouterId(id).orElseThrow(WinboxAccessService::notFound);
        if (!config.configured() || !a.isEnabled() || !active(a.getRouter()) || !bound(a)) throw notFound();
        String agent = random();
        a.setAgentCiphertext(encryption.encrypt(agent)); a.setAgentHash(hash(agent)); advance(a);
        return WinboxScripts.installer(a, agent, httpsBase());
    }

    public Credentials credentials(TenantPrincipal p, Long id, String ip) {
        Router r = allowed(p, id);
        WinboxAccess a = accesses.findByRouterId(id).orElseThrow(WinboxAccessService::notFound);
        revokeIfInvalid(a);
        if (!"READY".equals(describe(r, a).state())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Credentials are available only after router configuration and forwarding checks pass.");
        record(p, r, "WINBOX_CREDENTIALS_REVEALED", ip);
        return new Credentials(a.getUsername(), encryption.decrypt(a.getPasswordCiphertext()));
    }

    public String agentConfig(Long id, String bearer) {
        WinboxAccess a = agent(id, bearer);
        revokeIfInvalid(a);
        boolean enabled = config.configured() && a.isEnabled() && active(a.getRouter()) && bound(a);
        return WinboxScripts.configure(a, enabled, enabled ? encryption.decrypt(a.getPasswordCiphertext()) : null,
                config.getHubIp(), httpsBase(), bearer);
    }

    public void acknowledge(Long id, String bearer, long revision, boolean success) {
        WinboxAccess a = agent(id, bearer);
        if (revision != a.getRevision()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Configuration changed; fetch it again.");
        a.setAppliedRevision(revision); a.setRouterResult(success ? "APPLIED" : "FAILED"); a.setRouterCheckedAt(OffsetDateTime.now());
    }

    public Routes routes(String bearer) {
        gateway(bearer);
        List<Route> result = new ArrayList<>();
        for (WinboxAccess a : accesses.findAll()) {
            revokeIfInvalid(a);
            if (config.configured() && a.isEnabled() && active(a.getRouter()) && bound(a)
                    && a.getAppliedRevision() == a.getRevision() && "APPLIED".equals(a.getRouterResult())
                    && a.getRouterCheckedAt() != null && a.getRouterCheckedAt().isAfter(OffsetDateTime.now().minusSeconds(75)))
                result.add(new Route(a.getRouter().getId(), a.getTenantId(), a.getPort(), a.getTunnelIp(), a.getPeerKey(), a.getRevision()));
        }
        return new Routes(config.getPublicHost(), config.getHubIp(), platform.getWgSubnet(), 15, result);
    }

    public void checked(String bearer, List<Check> checks) {
        gateway(bearer);
        if (checks == null || checks.size() > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        for (Check check : checks) {
            if (check == null || check.routerId() == null || check.routerId() <= 0 || check.revision() <= 0 || check.result() == null
                    || !Set.of("READY", "OFFLINE", "FORWARDING_FAILED", "DNS_FAILED", "CHECK_FAILED").contains(check.result()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            WinboxAccess a = accesses.findByRouterId(check.routerId()).orElse(null);
            if (a == null || check.revision() != a.getRevision() || !a.isEnabled() || !active(a.getRouter()) || !bound(a)) continue;
            a.setGatewayRevision(check.revision()); a.setGatewayResult(check.result());
            a.setGatewayCheckedAt(OffsetDateTime.now());
            if ("READY".equals(check.result())) a.setLastSuccessfulCheck(OffsetDateTime.now());
        }
    }

    public void revokeTenant(Long tenantId) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String actor = authentication != null && authentication.getPrincipal() instanceof TenantPrincipal p ? p.getEmail() : "SYSTEM";
        for (WinboxAccess a : accesses.findAll()) if (tenantId.equals(a.getTenantId()) && a.isEnabled()) {
            a.setEnabled(false); advance(a);
            audit.save(AuditLog.builder().tenantId(tenantId).actorEmail(actor).action("WINBOX_TENANT_REVOKED")
                    .details("router=" + a.getRouter().getId()).createdAt(OffsetDateTime.now()).build());
        }
    }

    private Status describe(Router r, WinboxAccess a) {
        String state;
        if (!config.configured()) state = "UNAVAILABLE";
        else if (a == null || !a.isEnabled()) state = "DISABLED";
        else if (!bound(a) || !active(r)) state = "REVOKED";
        else if (a.getAgentHash() == null) state = "INSTALL_REQUIRED";
        else if (a.getAppliedRevision() != a.getRevision()) state = "PENDING";
        else if ("FAILED".equals(a.getRouterResult())) state = "CONFIGURATION_FAILED";
        else if (a.getRouterCheckedAt() == null || a.getRouterCheckedAt().isBefore(OffsetDateTime.now().minusSeconds(75))) state = "PENDING";
        else if (a.getGatewayCheckedAt() == null || a.getGatewayCheckedAt().isBefore(OffsetDateTime.now().minusSeconds(15))
                || a.getGatewayRevision() != a.getRevision()) state = "PENDING";
        else state = a.getGatewayResult() == null ? "PENDING" : a.getGatewayResult();
        String message = switch (state) {
            case "READY" -> "Router configuration and hub listener checks passed. External WinBox login is not automatically verified.";
            case "DISABLED", "REVOKED" -> "Public access is revoked within the relay's 15-second lease. The dedicated account is disabled at the next agent check-in; agent failures also disable it.";
            case "UNAVAILABLE" -> "The private relay, public DNS and dedicated encryption key must be configured by the platform operator.";
            case "INSTALL_REQUIRED" -> "Install the one-time WinBox agent in this router's local administrator terminal.";
            case "OFFLINE" -> "The WireGuard peer or router WinBox service is unavailable.";
            case "CONFIGURATION_FAILED" -> "Router configuration failed. Check the local WinBox agent log; no public endpoint is released.";
            case "DNS_FAILED" -> "The public hostname does not resolve to the configured relay address.";
            case "FORWARDING_FAILED" -> "The relay could not bind the assigned public TCP port.";
            case "CHECK_FAILED" -> "The relay connectivity check is unavailable or busy; the public endpoint is closed.";
            default -> "Waiting for the router acknowledgment and a fresh relay connectivity check.";
        };
        return new Status(a != null && a.isEnabled(), state, message,
                "READY".equals(state) ? config.getPublicHost() + ":" + a.getPort() : null,
                a == null ? null : a.getUsername(), r.getWgIp(), a == null ? null : a.getLastSuccessfulCheck(),
                a != null && a.getAgentHash() != null, config.configured());
    }

    private boolean active(Router r) {
        if (r.getTenant() == null || !"ACTIVE".equalsIgnoreCase(r.getTenant().getStatus()) || "DELETED".equalsIgnoreCase(r.getStatus())
                || r.getWgPublicKey() == null || !r.getWgPublicKey().matches("[A-Za-z0-9+/]{43}=")) return false;
        String ip = Ipv4Subnet.stripPrefix(r.getWgIp());
        try { return ip != null && !ip.equals(config.getHubIp()) && Ipv4Subnet.parse(platform.getWgSubnet()).contains(ip); }
        catch (RuntimeException e) { return false; }
    }

    private boolean bound(WinboxAccess a) {
        Router r = a.getRouter();
        return r.getTenant() != null && a.getTenantId().equals(r.getTenant().getId())
                && a.getTunnelIp().equals(Ipv4Subnet.stripPrefix(r.getWgIp())) && a.getPeerKey().equals(r.getWgPublicKey());
    }

    private void revokeIfInvalid(WinboxAccess a) {
        if (a.isEnabled() && (!active(a.getRouter()) || !bound(a))) {
            a.setEnabled(false); advance(a);
            audit.save(AuditLog.builder().tenantId(a.getTenantId()).actorEmail("SYSTEM").action("WINBOX_BINDING_REVOKED")
                    .details("router=" + a.getRouter().getId()).createdAt(OffsetDateTime.now()).build());
        }
    }

    private void advance(WinboxAccess a) {
        a.setRevision(a.getRevision() + 1); a.setGatewayResult(null); a.setGatewayCheckedAt(null);
    }

    private WinboxAccess agent(Long id, String bearer) {
        WinboxAccess a = accesses.findByRouterId(id).orElseThrow(WinboxAccessService::notFound);
        if (a.getAgentHash() == null || bearer == null || bearer.length() > 128 || !equal(a.getAgentHash(), hash(bearer))) throw notFound();
        return a;
    }

    private void gateway(String bearer) {
        if (config.getGatewayToken() == null || config.getGatewayToken().length() < 32 || bearer == null || !equal(config.getGatewayToken(), bearer))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    private String httpsBase() {
        String base = platform.getPublicBaseUrl();
        if (!base.startsWith("https://")) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "WinBox enrollment requires HTTPS.");
        return base;
    }

    private void record(TenantPrincipal p, Router r, String action, String ip) {
        audit.save(AuditLog.builder().tenantId(r.getTenant().getId()).actorEmail(p.getEmail()).ipAddress(ip)
                .action(action).details("router=" + r.getId()).createdAt(OffsetDateTime.now()).build());
    }
    private static String random() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static boolean equal(String a, String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Remote access not found"); }
}
