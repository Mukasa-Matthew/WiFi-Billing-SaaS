package com.wifibilling.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Central, typed access to platform settings from application.properties.
 */
@Getter
@Component
public class PlatformProperties {

    /** Public WireGuard hub endpoint, "host:port" (port defaults to 443). */
    @Value("${wireguard.server.endpoint}")
    private String wgEndpoint;

    @Value("${wireguard.server.public-key}")
    private String wgServerPublicKey;

    @Value("${wireguard.server.subnet:10.9.0.0/16}")
    private String wgSubnet;

    /** Name of the hub-side WireGuard interface (e.g. wg0). */
    @Value("${wireguard.server.interface:wg0}")
    private String wgInterface;

    @Value("${wireguard.peer-management.enabled:false}")
    private boolean peerManagementEnabled;

    @Value("${wireguard.peer-management.use-sudo:true}")
    private boolean peerManagementUseSudo;

    @Value("${wireguard.peer-management.wg-binary:wg}")
    private String wgBinary;

    /** When set (e.g. matthew@162.245.186.123), wg commands are executed remotely via SSH
     *  on the host that owns the hub interface. Requires key auth + sudoers NOPASSWD for wg. */
    @Value("${wireguard.peer-management.ssh-target:}")
    private String peerManagementSshTarget;

    @Value("${radius.server.ip:10.9.0.1}")
    private String radiusServerIp;

    /** Publicly routable base URL of this API, reachable by routers BEFORE the tunnel is up. */
    @Value("${provisioning.public-base-url}")
    private String publicBaseUrl;

    /** Public IP of cloudserv, whitelisted in the hotspot walled garden. Optional. */
    @Value("${provisioning.server-public-ip:}")
    private String serverPublicIp;

    @Value("${telemetry.offline-after-minutes:6}")
    private long offlineAfterMinutes;

    @Value("${payment.walled-garden.hosts:proxy.momoapi.mtn.com,openapi.airtel.africa,api.flutterwave.com}")
    private String paymentWalledGardenHosts;

    public List<String> getPaymentWalledGardenHostsList() {
        if (paymentWalledGardenHosts == null || paymentWalledGardenHosts.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(paymentWalledGardenHosts.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }

    public String getWgEndpointHost() {
        int idx = wgEndpoint.lastIndexOf(':');
        return (idx > 0 && wgEndpoint.indexOf(':') == idx) ? wgEndpoint.substring(0, idx) : wgEndpoint;
    }

    public int getWgEndpointPort() {
        int idx = wgEndpoint.lastIndexOf(':');
        if (idx > 0 && wgEndpoint.indexOf(':') == idx) {
            return Integer.parseInt(wgEndpoint.substring(idx + 1).trim());
        }
        return 443;
    }
}
