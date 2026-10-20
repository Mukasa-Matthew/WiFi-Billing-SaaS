package com.wifibilling.service;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * Generates an idempotent RouterOS v7 configuration script (.rsc) tailored
 * to a specific router instance.
 *
 * <p>All commands use RouterOS v7 syntax:
 * <ul>
 *   <li>{@code /interface/wireguard} and {@code /interface/wireguard/peers}</li>
 *   <li>{@code /ip/address}</li>
 *   <li>{@code /radius} and {@code /radius/incoming/set}</li>
 *   <li>{@code /ip/hotspot/profile/set}</li>
 *   <li>{@code /tool fetch} for dynamic captive portal assets</li>
 * </ul>
 */
@Component
public class RouterScriptGenerator {

    private static final String IFACE_NAME = "wg-billing";
    private static final String TELEMETRY_NAME = "wb-telemetry";
    private static final String POLICY = "read,write,policy,test";
    private static final String TAG = "wifi-billing";

    private static final List<String> PORTAL_TEMPLATES = List.of(
            "login.html",
            "status.html",
            "logout.html",
            "alogin.html",
            "redirect.html",
            "rlogin.html",
            "error.html"
    );

    public record ScriptContext(
            String routerName,
            String token,
            String wgIp,
            int prefixLength,
            String radiusSecret,
            String radiusServerIp,
            String serverPublicKey,
            String endpointHost,
            int endpointPort,
            String tunnelSubnet,
            String publicBaseUrl,
            Set<String> walledGardenIps,
            Set<String> walledGardenHosts
    ) {}

    public String generate(ScriptContext c) {
        String iface = q(IFACE_NAME);
        String tag = q(TAG);
        String registerUrl = c.publicBaseUrl() + "/api/router/provision/" + c.token() + "/register";
        String telemetryUrl = c.publicBaseUrl() + "/api/router/telemetry";

        StringBuilder s = new StringBuilder(4096);
        s.append("# ==========================================================\n");
        s.append("# WiFi-Billing auto-provisioning script (RouterOS v7+)\n");
        s.append("# Router    : ").append(comment(c.routerName())).append('\n');
        s.append("# Tunnel IP : ").append(c.wgIp()).append('/').append(c.prefixLength()).append('\n');
        s.append("# Generated : ").append(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append('\n');
        s.append("# Requires RouterOS v7+ (WireGuard support)\n");
        s.append("# ==========================================================\n");
        s.append(":log info \"wifi-billing: provisioning started\"\n\n");

        // 0. Cleanup of any previous run
        s.append("# --- 0. Remove previous provisioning (safe to re-run) ---\n");
        s.append("/system/scheduler/remove [find name=").append(q(TELEMETRY_NAME)).append("]\n");
        s.append("/system/script/remove [find name=").append(q(TELEMETRY_NAME)).append("]\n");
        s.append("/ip/hotspot/walled-garden/ip/remove [find comment=").append(tag).append("]\n");
        s.append("/ip/hotspot/walled-garden/remove [find comment=").append(tag).append("]\n");
        s.append("/ip/firewall/filter/remove [find comment=").append(tag).append("]\n");
        s.append("/radius/remove [find comment=").append(tag).append("]\n");
        s.append("/ip/address/remove [find interface=").append(iface).append("]\n");
        s.append("/interface/wireguard/peers/remove [find interface=").append(iface).append("]\n");
        s.append("/interface/wireguard/remove [find name=").append(iface).append("]\n\n");

        // 1. WireGuard
        s.append("# --- 1. WireGuard tunnel to billing hub ---\n");
        s.append("/interface/wireguard add name=").append(iface)
                .append(" listen-port=13231 mtu=1420 comment=").append(tag).append('\n');
        s.append("/interface/wireguard/peers add interface=").append(iface)
                .append(" public-key=").append(q(c.serverPublicKey()))
                .append(" endpoint-address=").append(q(c.endpointHost()))
                .append(" endpoint-port=").append(c.endpointPort())
                .append(" allowed-address=").append(c.tunnelSubnet())
                .append(" persistent-keepalive=25s comment=").append(tag).append('\n');
        s.append("/ip/address add address=").append(c.wgIp()).append('/').append(c.prefixLength())
                .append(" interface=").append(iface).append(" comment=").append(tag).append("\n\n");

        // 2. RADIUS + CoA
        s.append("# --- 2. RADIUS (FreeRADIUS via tunnel) + CoA/Disconnect ---\n");
        s.append("/radius add address=").append(c.radiusServerIp())
                .append(" secret=").append(q(c.radiusSecret()))
                .append(" service=hotspot,login src-address=").append(c.wgIp())
                .append(" timeout=3s comment=").append(tag).append('\n');
        s.append("/radius/incoming/set accept=yes port=3799\n");
        String coaRule = "/ip/firewall/filter add chain=input in-interface=" + iface
                + " src-address=" + c.radiusServerIp()
                + " protocol=udp dst-port=3799 action=accept comment=" + tag;
        s.append(":if ([:len [/ip/firewall/filter find]] > 0) do={ ").append(coaRule)
                .append(" place-before=0 } else={ ").append(coaRule).append(" }\n");
        s.append("/ip/hotspot/profile/set [find] use-radius=yes radius-accounting=yes radius-interim-update=2m html-directory=flash/hotspot\n\n");

        // 3. Captive Portal Template Download to flash/hotspot
        s.append("# --- 3. Captive Portal Assets Download ---\n");
        s.append(":do { /file add name=flash/hotspot/login.html contents=\"\" } on-error={}\n");
        for (String tmpl : PORTAL_TEMPLATES) {
            String tmplUrl = c.publicBaseUrl() + "/api/portal/templates/" + tmpl + "?token=" + c.token();
            s.append(":do { /tool fetch url=").append(q(tmplUrl))
                    .append(" dst-path=").append(q("flash/hotspot/" + tmpl))
                    .append(" mode=http } on-error={ :log warning \"wifi-billing: failed fetching ").append(tmpl).append("\" }\n");
        }
        s.append("\n");

        // 4. Walled garden (portal/API reachable before login)
        s.append("# --- 4. Walled garden (portal/API reachable before login) ---\n");
        for (String ip : c.walledGardenIps()) {
            s.append("/ip/hotspot/walled-garden/ip add action=accept dst-address=").append(ip)
                    .append(" comment=").append(tag).append('\n');
        }
        for (String host : c.walledGardenHosts()) {
            s.append("/ip/hotspot/walled-garden add action=allow dst-host=").append(q(host))
                    .append(" comment=").append(tag).append('\n');
        }
        s.append('\n');

        // 5. Telemetry
        s.append("# --- 5. Telemetry push every 2 minutes ---\n");
        s.append("/system/script add name=").append(q(TELEMETRY_NAME)).append(" policy=").append(POLICY)
                .append(" comment=").append(tag).append(" source={\n");
        s.append(":local cpu [/system resource get cpu-load]\n");
        s.append(":local freeMem [/system resource get free-memory]\n");
        s.append(":local totalMem [/system resource get total-memory]\n");
        s.append(":local up [/system resource get uptime]\n");
        s.append(":local users [/ip hotspot active print count-only]\n");
        s.append(":local body (\"{\\\"token\\\":\\\"").append(c.token())
                .append("\\\",\\\"cpuLoad\\\":\" . $cpu . \",\\\"freeMemory\\\":\" . $freeMem")
                .append(" . \",\\\"totalMemory\\\":\" . $totalMem . \",\\\"uptime\\\":\\\"\" . $up")
                .append(" . \"\\\",\\\"activeUsers\\\":\" . $users . \"}\")\n");
        s.append(":do { /tool fetch url=").append(q(telemetryUrl))
                .append(" http-method=post http-header-field=\"Content-Type: application/json\"")
                .append(" http-data=$body output=none } on-error={ :log warning \"wifi-billing: telemetry push failed\" }\n");
        s.append("}\n");
        s.append("/system/scheduler add name=").append(q(TELEMETRY_NAME))
                .append(" interval=2m on-event=").append(q("/system script run " + TELEMETRY_NAME))
                .append(" policy=").append(POLICY).append(" comment=").append(tag).append("\n\n");

        // 6. Key registration
        s.append("# --- 6. Register this router's WireGuard public key with the hub ---\n");
        s.append(":delay 2s\n");
        s.append(":do {\n");
        s.append("    :local wbPub [/interface/wireguard get [find name=").append(iface).append("] public-key]\n");
        s.append("    /tool fetch url=").append(q(registerUrl))
                .append(" http-method=post http-header-field=\"Content-Type: application/json\"")
                .append(" http-data=(\"{\\\"publicKey\\\":\\\"\" . $wbPub . \"\\\"}\") output=none\n");
        s.append("    :log info \"wifi-billing: WireGuard public key registered\"\n");
        s.append("} on-error={ :log error \"wifi-billing: key registration failed - re-run provisioning\" }\n\n");

        s.append(":do { /system script run ").append(TELEMETRY_NAME).append(" } on-error={}\n");
        s.append(":log info \"wifi-billing: provisioning complete\"\n");
        return s.toString();
    }

    /** Quotes a value as a RouterOS string literal. */
    static String q(String value) {
        String v = value == null ? "" : value;
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\"";
    }

    /** Makes arbitrary text safe for a single-line script comment. */
    private static String comment(String value) {
        return value == null ? "" : value.replaceAll("[^\\x20-\\x7E]", "?");
    }
}
