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
    private static final String BRIDGE_NAME = "wb-hotspot-bridge";
    private static final String POOL_NAME = "wb-hotspot-pool";
    private static final String DHCP_NAME = "wb-hotspot-dhcp";
    private static final String HOTSPOT_PROFILE = "wb-hotspot-profile";
    private static final String HOTSPOT_SERVER = "wb-hotspot";
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
            String wanInterface,
            List<String> hotspotInterfaces,
            String wifiSsid,
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

        StringBuilder s = new StringBuilder(8192);
        s.append("# ==========================================================\n");
        s.append("# WiFi-Billing auto-provisioning script (RouterOS v7+)\n");
        s.append("# Router    : ").append(comment(c.routerName())).append('\n');
        s.append("# Tunnel IP : ").append(c.wgIp()).append('/').append(c.prefixLength()).append('\n');
        s.append("# Generated : ").append(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append('\n');
        s.append("# Requires RouterOS v7+ (WireGuard support)\n");
        s.append("# ==========================================================\n");
        s.append(":log info \"wifi-billing: provisioning started\"\n\n");
        s.append(":do { /export file=before-wifi-billing } on-error={}\n\n");

        // 0. Cleanup of any previous run
        s.append("# --- 0. Remove previous provisioning (safe to re-run) ---\n");
        s.append("/system/scheduler/remove [find name=").append(q(TELEMETRY_NAME)).append("]\n");
        s.append("/system/script/remove [find name=").append(q(TELEMETRY_NAME)).append("]\n");
        s.append("/ip/hotspot/walled-garden/ip/remove [find comment=").append(tag).append("]\n");
        s.append("/ip/hotspot/walled-garden/remove [find comment=").append(tag).append("]\n");
        s.append("/ip/firewall/filter/remove [find comment=").append(tag).append("]\n");
        s.append("/ip/firewall/nat/remove [find comment=").append(tag).append("]\n");
        s.append("/ip/hotspot/remove [find name=").append(q(HOTSPOT_SERVER)).append("]\n");
        s.append("/ip/hotspot/profile/remove [find name=").append(q(HOTSPOT_PROFILE)).append("]\n");
        s.append("/ip/dhcp-server/remove [find name=").append(q(DHCP_NAME)).append("]\n");
        s.append("/ip/dhcp-server/network/remove [find comment=").append(tag).append("]\n");
        s.append("/ip/pool/remove [find name=").append(q(POOL_NAME)).append("]\n");
        s.append("/ip/address/remove [find comment=").append(tag).append("]\n");
        s.append("/interface/list/member/remove [find comment=").append(tag).append("]\n");
        s.append("/interface/bridge/port/remove [find comment=").append(tag).append("]\n");
        s.append("/interface/bridge/remove [find name=").append(q(BRIDGE_NAME)).append("]\n");
        s.append("/radius/remove [find comment=").append(tag).append("]\n");
        s.append("/ip/address/remove [find interface=").append(iface).append("]\n");
        s.append("/interface/wireguard/peers/remove [find interface=").append(iface).append("]\n");
        s.append("/interface/wireguard/remove [find name=").append(iface).append("]\n\n");

        // 1. Internet uplink and hotspot LAN
        s.append("# --- 1. Internet uplink and hotspot LAN ---\n");
        s.append(":if ([:len [/interface find name=").append(q(c.wanInterface())).append("]] = 0) do={ :error \"WAN interface not found\" }\n");
        s.append(":if ([:len [/ip/dhcp-client find interface=").append(q(c.wanInterface())).append("]] = 0) do={ /ip/dhcp-client add interface=")
                .append(q(c.wanInterface())).append(" disabled=no comment=").append(tag).append(" }\n");
        s.append("/interface/bridge add name=").append(q(BRIDGE_NAME)).append(" protocol-mode=rstp comment=").append(tag).append('\n');
        for (String hotspotInterface : c.hotspotInterfaces()) {
            s.append(":if ([:len [/interface find name=").append(q(hotspotInterface)).append("]] > 0) do={ ")
                    .append("/interface/bridge/port/remove [find interface=").append(q(hotspotInterface)).append("]; ")
                    .append("/interface/bridge/port add bridge=").append(q(BRIDGE_NAME))
                    .append(" interface=").append(q(hotspotInterface)).append(" comment=").append(tag).append(" }\n");
        }
        // Keep the router manageable: the hotspot bridge must be in the LAN list, otherwise the
        // default firewall, MAC-WinBox and neighbor discovery ignore it and WinBox can't find the router.
        s.append(":do { /interface/list/member add list=LAN interface=").append(q(BRIDGE_NAME))
                .append(" comment=").append(tag).append(" } on-error={}\n");
        // Every radio on the router becomes an open hotspot AP with the chosen SSID. Run via :parse so
        // routers lacking the legacy "wireless" or the newer "wifi" package don't abort the import.
        String port = "/interface/bridge/port/remove [find interface=$n]; /interface/bridge/port add bridge="
                + q(BRIDGE_NAME) + " interface=$n comment=" + tag;
        String skipWan = ":if ($n != " + q(c.wanInterface()) + ") do={ ";
        String legacyWifi = ":if ([:len [/interface/wireless/security-profiles find name=\"wb-open\"]] = 0) do={ "
                + "/interface/wireless/security-profiles add name=\"wb-open\" mode=none } else={ "
                + "/interface/wireless/security-profiles set [find name=\"wb-open\"] mode=none }; "
                + ":foreach w in=[/interface/wireless find] do={ :local n [/interface/wireless get $w name]; " + skipWan
                + port + "; /interface/wireless set $w mode=ap-bridge ssid=" + q(c.wifiSsid())
                + " security-profile=\"wb-open\" disabled=no } }";
        String newWifi = ":foreach w in=[/interface/wifi find] do={ :local n [/interface/wifi get $w name]; " + skipWan
                + port + "; /interface/wifi set $w configuration.mode=ap configuration.ssid=" + q(c.wifiSsid())
                + " disabled=no; :do { /interface/wifi set $w configuration.security.authentication-types=\"\" configuration.security.passphrase=\"\" }"
                + " on-error={ :do { /interface/wifi set $w security.authentication-types=\"\" security.passphrase=\"\" } on-error={} } } }";
        s.append(":do { :local f [:parse ").append(q(legacyWifi)).append("]; $f } on-error={}\n");
        s.append(":do { :local f [:parse ").append(q(newWifi)).append("]; $f } on-error={}\n");
        s.append("/ip/address add address=10.10.0.1/24 interface=").append(q(BRIDGE_NAME)).append(" comment=").append(tag).append('\n');
        s.append("/ip/pool add name=").append(q(POOL_NAME)).append(" ranges=10.10.0.10-10.10.0.254\n");
        s.append("/ip/dhcp-server add name=").append(q(DHCP_NAME)).append(" interface=").append(q(BRIDGE_NAME))
                .append(" address-pool=").append(q(POOL_NAME)).append(" lease-time=1h disabled=no\n");
        s.append("/ip/dhcp-server/network add address=10.10.0.0/24 gateway=10.10.0.1 dns-server=10.10.0.1 comment=").append(tag).append('\n');
        s.append("/ip/dns/set allow-remote-requests=yes\n");
        s.append("/ip/firewall/nat add chain=srcnat out-interface=").append(q(c.wanInterface()))
                .append(" action=masquerade comment=").append(tag).append("\n\n");

        // 2. WireGuard
        s.append("# --- 2. WireGuard tunnel to billing hub ---\n");
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

        // 3. Hotspot, RADIUS + CoA
        s.append("# --- 3. Hotspot, RADIUS (via tunnel) + CoA/Disconnect ---\n");
        s.append("/ip/hotspot/profile add name=").append(q(HOTSPOT_PROFILE))
                .append(" hotspot-address=10.10.0.1 dns-name=login.wifi use-radius=yes radius-accounting=yes")
                .append(" radius-interim-update=2m login-by=http-chap,http-pap,cookie html-directory=flash/hotspot\n");
        s.append("/ip/hotspot add name=").append(q(HOTSPOT_SERVER)).append(" interface=").append(q(BRIDGE_NAME))
                .append(" address-pool=").append(q(POOL_NAME)).append(" profile=").append(q(HOTSPOT_PROFILE)).append(" disabled=no\n");
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
        // Management access from the hotspot side: winbox (8291) and neighbor
        // discovery (5678) must be inserted BEFORE the hotspot dynamic jump rules,
        // or unauthenticated clients can never reach the router.
        String winboxRule = "/ip/firewall/filter add chain=pre-hs-input protocol=tcp dst-port=8291 action=accept comment=" + tag;
        String mndpRule = "/ip/firewall/filter add chain=pre-hs-input protocol=udp dst-port=5678 action=accept comment=" + tag;
        s.append(":if ([:len [/ip/firewall/filter find]] > 0) do={ ").append(winboxRule)
                .append(" place-before=0 } else={ ").append(winboxRule).append(" }\n");
        s.append(":if ([:len [/ip/firewall/filter find]] > 0) do={ ").append(mndpRule)
                .append(" place-before=0 } else={ ").append(mndpRule).append(" }\n");
        s.append("/ip/neighbor/discovery-settings/set discover-interface-list=LAN\n");
        s.append(":do { /tool/mac-server/set allowed-interface-list=LAN } on-error={}\n");
        s.append(":do { /tool/mac-server/mac-winbox/set allowed-interface-list=LAN } on-error={}\n");
        s.append('\n');

        // 3. Captive Portal Template Download to flash/hotspot
        s.append("# --- 4. Captive Portal Assets Download ---\n");
        s.append(":do { /file add name=flash/hotspot/login.html contents=\"\" } on-error={}\n");
        for (String tmpl : PORTAL_TEMPLATES) {
            String tmplUrl = c.publicBaseUrl() + "/api/portal/templates/" + tmpl + "?token=" + c.token();
            s.append(":do { /tool fetch url=").append(q(tmplUrl))
                    .append(" dst-path=").append(q("flash/hotspot/" + tmpl))
                    .append(" } on-error={ :log warning \"wifi-billing: failed fetching ").append(tmpl).append("\" }\n");
        }
        s.append("\n");

        // 4. Walled garden (portal/API reachable before login)
        s.append("# --- 5. Walled garden (portal/API reachable before login) ---\n");
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
        s.append("# --- 6. Telemetry push every 2 minutes ---\n");
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
        s.append("# --- 7. Register this router's WireGuard public key with the hub ---\n");
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
        s.append(managementInstaller(c.publicBaseUrl(), c.token()));
        return s.toString();
    }

    public static String managementInstaller(String baseUrl, String token) {
        String prefix = baseUrl + "/api/router/provision/" + token;
        return "/system/scheduler/remove [find name=wb-management]\n"
                + "/system/script/remove [find name=wb-management]\n"
                + "/system/clock/set time-zone-name=Etc/UTC time-zone-autodetect=no\n"
                + "/system/ntp/client/set enabled=yes\n"
                + ":if ([:len [/system/ntp/client/servers find address=pool.ntp.org]] = 0) do={ /system/ntp/client/servers add address=pool.ntp.org }\n"
                + "/system/script/add name=wb-management policy=read,write,test source={\n"
                + ":do { /tool/fetch url=" + q(prefix + "/management.rsc") + " dst-path=wb-management.rsc; /import wb-management.rsc } on-error={ :log warning \"wifi-billing management failed\" }\n"
                + ":do { :local hosts [/ip/dhcp-server/lease/print as-value where status=bound]; /tool/fetch url=" + q(prefix + "/hosts")
                + " http-method=post http-header-field=\"Content-Type: application/json\" http-data=[:serialize to=json value=$hosts] output=none } on-error={ :log warning \"wifi-billing host report failed\" }\n}\n"
                + "/system/scheduler/add name=wb-management interval=1m on-event=wb-management policy=read,write,test\n"
                + ":do { /system/script/run wb-management } on-error={}\n";
    }

    /** Quotes a value as a RouterOS string literal. */
    static String q(String value) {
        String v = value == null ? "" : value;
        return "\"" + v.replace("\r", " ").replace("\n", " ")
                .replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\"";
    }

    /** Makes arbitrary text safe for a single-line script comment. */
    private static String comment(String value) {
        return value == null ? "" : value.replaceAll("[^\\x20-\\x7E]", "?");
    }
}
