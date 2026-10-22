package com.wifibilling.service;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.dto.RegisterKeyResponse;
import com.wifibilling.model.Router;
import com.wifibilling.radius.Nas;
import com.wifibilling.repository.NasRepository;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.util.Ipv4Subnet;
import com.wifibilling.util.RouterTokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Router onboarding:
 * <ol>
 *   <li>{@link #buildProvisioningScript} — router fetches its .rsc; we assign a tunnel IP
 *       and register it as a FreeRADIUS NAS client.</li>
 *   <li>{@link #registerPublicKey} — router posts its WireGuard public key; we add it as a
 *       peer on the hub so the tunnel can come up.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProvisioningService {

    public static final String STATUS_PROVISIONING = "provisioning";
    public static final String STATUS_REGISTERED = "registered";

    private static final String SECRET_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RouterRepository routerRepository;
    private final NasRepository nasRepository;
    private final WireGuardIpAllocator ipAllocator;
    private final WireGuardPeerService peerService;
    private final RouterScriptGenerator scriptGenerator;
    private final PlatformProperties props;

    @Transactional
    public String buildProvisioningScript(String token) {
        Router router = findRouter(token);
        Ipv4Subnet subnet = Ipv4Subnet.parse(props.getWgSubnet());

        String wgIp = Ipv4Subnet.stripPrefix(router.getWgIp());
        if (wgIp == null || wgIp.isBlank()) {
            wgIp = ipAllocator.allocate();
            log.info("Allocated WireGuard IP {} to router {}", wgIp, router.getId());
        } else if (!subnet.contains(wgIp)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Router WireGuard IP " + wgIp + " is outside " + props.getWgSubnet());
        }
        router.setWgIp(wgIp);

        if (router.getRadiusSecret() == null || router.getRadiusSecret().isBlank()) {
            router.setRadiusSecret(randomSecret(24));
        }
        if ("pending".equalsIgnoreCase(router.getStatus())) {
            router.setStatus(STATUS_PROVISIONING);
        }
        routerRepository.save(router);
        upsertNas(router);

        return scriptGenerator.generate(new RouterScriptGenerator.ScriptContext(
                router.getName(),
                defaultValue(router.getWanInterface(), "ether1"),
                router.getHotspotInterfaces() == null || router.getHotspotInterfaces().isBlank()
                        ? List.of("ether2", "ether3", "ether4", "ether5", "wlan1")
                        : List.of(router.getHotspotInterfaces().split(",")),
                defaultValue(router.getWifiSsid(), "WiFi Hotspot"),
                router.getToken(),
                wgIp,
                subnet.prefix(),
                router.getRadiusSecret(),
                props.getRadiusServerIp(),
                props.getWgServerPublicKey(),
                props.getWgEndpointHost(),
                props.getWgEndpointPort(),
                props.getWgSubnet(),
                props.getPublicBaseUrl(),
                walledGardenIps(),
                walledGardenHosts()));
    }

    /**
     * Not @Transactional on purpose: the DB update commits first, then the hub is
     * reconfigured. If the wg command fails, the key is still stored and will be
     * applied by {@link WireGuardPeerSync} on next startup (or a re-run).
     */
    public RegisterKeyResponse registerPublicKey(String token, String publicKey) {
        Router router = findRouter(token);
        String wgIp = Ipv4Subnet.stripPrefix(router.getWgIp());
        if (wgIp == null || wgIp.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Router has not fetched its provisioning script yet");
        }

        routerRepository.findByWgPublicKey(publicKey)
                .filter(other -> !other.getId().equals(router.getId()))
                .ifPresent(other -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Public key already registered to another router");
                });

        String previousKey = router.getWgPublicKey();
        router.setWgPublicKey(publicKey);
        router.setBindingsHash(null);
        router.setStatus(STATUS_REGISTERED);
        routerRepository.save(router);

        boolean applied = peerService.replacePeer(previousKey, publicKey, wgIp);
        log.info("Router {} registered WireGuard key (hub peer configured: {})", router.getId(), applied);
        log.info("[WG Setup] Run on cloudserv: sudo wg set wg0 peer {} allowed-ips {}/32", publicKey, wgIp);
        return new RegisterKeyResponse(STATUS_REGISTERED, wgIp, applied);
    }

    private Router findRouter(String token) {
        if (!RouterTokens.isValid(token)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown router token");
        }
        return routerRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown router token"));
    }

    private void upsertNas(Router router) {
        Nas nas = nasRepository.findByNasname(router.getWgIp()).orElseGet(Nas::new);
        nas.setNasname(router.getWgIp());
        nas.setShortname(("rtr-" + router.getId()));
        nas.setType("other");
        nas.setSecret(router.getRadiusSecret());
        String description = "WiFi-Billing router #" + router.getId() + " " + router.getName();
        nas.setDescription(description.length() > 200 ? description.substring(0, 200) : description);
        nasRepository.save(nas);
    }

    private Set<String> walledGardenIps() {
        Set<String> ips = new LinkedHashSet<>();
        if (Ipv4Subnet.isIpv4(props.getServerPublicIp())) {
            ips.add(props.getServerPublicIp());
        }
        String host = baseUrlHost();
        if (Ipv4Subnet.isIpv4(host)) {
            ips.add(host);
        }
        return ips;
    }

    private Set<String> walledGardenHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        String host = baseUrlHost();
        if (host != null && !host.isBlank() && !Ipv4Subnet.isIpv4(host)) {
            hosts.add(host);
        }
        // Include payment gateway hostnames for pre-login USSD / webhooks
        hosts.addAll(props.getPaymentWalledGardenHostsList());
        return hosts;
    }

    private String baseUrlHost() {
        try {
            return URI.create(props.getPublicBaseUrl()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String randomSecret(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(SECRET_ALPHABET.charAt(RANDOM.nextInt(SECRET_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
