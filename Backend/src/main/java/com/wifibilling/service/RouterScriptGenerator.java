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

    /** Bump only for a deliberate, reviewed RouterOS infrastructure migration. */
    public static final String PROVISIONING_VERSION = "2026.10.2";

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
            "feedback.html",
            "status.html",
            "logout.html",
            "alogin.html",
            "redirect.html",
            "rlogin.html",
            "error.html",
            "packages.json"
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
            Set<String> walledGardenHosts,
            String tetheringMode
    ) {}

    /**
     * Firewall rules implementing tethering protection. Tethered traffic arrives
     * with TTL decremented by the sharing phone (63 on Android/iOS, 127 on Windows).
     * mode: "OFF" (stale rules removed), "LOG" (detect only), "BLOCK" (drop).
     */
    public static String tetheringRules(String mode) {
        StringBuilder s = new StringBuilder();
        s.append("/ip/firewall/filter/remove [find where comment~\"^wb-tether\"]\n");
        if ("LOG".equals(mode) || "BLOCK".equals(mode)) {
            String action = "BLOCK".equals(mode) ? "drop" : "log";
            // TTL is decremented before the forward chain, so direct clients
            // appear as 63/127. Tethered traffic passed an extra NAT hop: 62/126.
            s.append("/ip/firewall/filter/add chain=forward ttl=equal:62 action=").append(action)
                    .append(" log=yes log-prefix=\"wb-tether\" comment=\"wb-tether: android/ios tethered\"\n");
            s.append("/ip/firewall/filter/add chain=forward ttl=equal:126 action=").append(action)
                    .append(" log=yes log-prefix=\"wb-tether\" comment=\"wb-tether: windows tethered\"\n");
        }
        return s.toString();
    }

    public String generate(ScriptContext c) {
        String iface = q(IFACE_NAME);
        String tag = q(TAG);
        String registerUrl = c.publicBaseUrl() + "/api/router/provision/" + c.token() + "/register";
        String telemetryUrl = c.publicBaseUrl() + "/api/router/telemetry";

        StringBuilder s = new StringBuilder(8192);
        s.append("# ==========================================================\n");
        s.append("# WiFi-Billing auto-provisioning script (RouterOS v7+)\n");
        s.append("# Provisioning-Version: ").append(PROVISIONING_VERSION).append('\n');
        s.append("# Router    : ").append(comment(c.routerName())).append('\n');
        s.append("# Tunnel IP : ").append(c.wgIp()).append('/').append(c.prefixLength()).append('\n');
        s.append("# Generated : ").append(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append('\n');
        s.append("# Requires RouterOS v7+ (WireGuard support)\n");
        s.append("# ==========================================================\n");
        s.append(":local wbMarker ").append(q("wifi-billing:" + PROVISIONING_VERSION)).append('\n');
        s.append(":if ([:len [/system/script find name=wb-provisioned comment=$wbMarker]] > 0) do={ ")
                .append(":log info \"wifi-billing: provisioning already current; no changes made\"; ")
                .append(":put \"WiFi-Billing is already provisioned at the requested version.\"; :return }\n");
        s.append(":if ([:tonum [:pick [/system/resource get version] 0 1]] < 7) do={ :error \"RouterOS v7 or newer is required\" }\n");
        s.append(":if ([:len [/interface find name=").append(q(c.wanInterface()))
                .append("]] = 0) do={ :error \"WAN interface not found; no changes made\" }\n");
        s.append(":local wbBridge [/interface/bridge find name=").append(q(BRIDGE_NAME)).append("]\n");
        s.append(":if ([:len $wbBridge] > 0 && [/interface/bridge get [:pick $wbBridge 0] comment] != ")
                .append(tag).append(") do={ :error \"Existing wb-hotspot-bridge is not managed by WiFi-Billing; no changes made\" }\n");
        s.append(":local wbAddress [/ip/address find address=\"10.10.0.1/24\"]\n");
        s.append(":if ([:len $wbAddress] > 0 && [/ip/address get [:pick $wbAddress 0] comment] != ")
                .append(tag).append(") do={ :error \"10.10.0.1/24 is already used by an unmanaged configuration; no changes made\" }\n");
        s.append(":log info \"wifi-billing: provisioning started\"\n\n");
        s.append(":do { /export file=before-wifi-billing } on-error={}\n\n");

        // Clock sync FIRST: an NTP time jump while /import or /tool/fetch is
        // running aborts it mid-script. Sync and settle the clock before any
        // downloads or config changes (bounded wait — WAN may be slow to come up).
        s.append("# --- 0a. Sync clock before anything else ---\n");
        s.append("/system/clock/set time-zone-name=Etc/UTC time-zone-autodetect=no\n");
        s.append("/system/ntp/client/set enabled=yes\n");
        s.append(":if ([:len [/system/ntp/client/servers find address=pool.ntp.org]] = 0) do={ /system/ntp/client/servers add address=pool.ntp.org }\n");
        s.append(":local wbDate [/system/clock get date]\n");
        s.append(":local wbYear [:tonum [:pick $wbDate ([:len $wbDate] - 4) [:len $wbDate]]]\n");
        s.append(":for wbNtpWait from=1 to=24 do={\n");
        s.append("    :set wbDate [/system/clock get date]\n");
        s.append("    :set wbYear [:tonum [:pick $wbDate ([:len $wbDate] - 4) [:len $wbDate]]]\n");
        s.append("    :if ($wbYear < 2026) do={ :delay 5s }\n");
        s.append("}\n\n");

        // 0. Cleanup of any previous run
        s.append("# --- 0. Remove previous provisioning (safe to re-run) ---\n");
        s.append("/system/scheduler/remove [find name=").append(q(TELEMETRY_NAME)).append("]\n");
        s.append("/system/script/remove [find name=").append(q(TELEMETRY_NAME)).append("]\n");
        s.append("/system/scheduler/remove [find name=wb-register]\n");
        s.append("/system/script/remove [find name=wb-register]\n");
        s.append("/system/scheduler/remove [find name=wb-portal-sync]\n");
        s.append("/system/script/remove [find name=wb-portal-sync]\n");
        s.append("/system/scheduler/remove [find name=wb-pkg-sync]\n");
        s.append("/system/script/remove [find name=wb-pkg-sync]\n");
        s.append("/system/script/remove [find name=wb-provisioned]\n");
        s.append("/ip/hotspot/walled-garden/ip/remove [find comment=").append(tag).append("]\n");
        s.append("/ip/hotspot/walled-garden/remove [find comment=").append(tag).append("]\n");
        s.append("/ip/firewall/filter/remove [find comment=").append(tag).append("]\n");
        s.append("/ip/firewall/nat/remove [find comment=").append(tag).append("]\n");
        s.append("/ip/hotspot/remove [find name=").append(q(HOTSPOT_SERVER)).append("]\n");
        s.append("/ip/hotspot/profile/remove [find name=").append(q(HOTSPOT_PROFILE)).append("]\n");
        s.append("/ip/dhcp-server/remove [find name=").append(q(DHCP_NAME)).append("]\n");
        s.append("/ip/dhcp-server/network/remove [find comment=").append(tag).append("]\n");
        // Active leases hold references to the pool — clear them first or the pool
        // removal throws and aborts the whole import mid-teardown.
        s.append(":do { /ip/dhcp-server/lease/remove [find dynamic] } on-error={}\n");
        s.append(":do { /ip/pool/remove [find name=").append(q(POOL_NAME)).append("] } on-error={}\n");
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
        // Fail fast if the chosen WAN port is wrong: no cable link, or no IP after DHCP.
        // Skipped automatically for non-ethernet uplinks (pppoe, lte, vlan...).
        s.append(":if ([:len [/interface/ethernet find name=").append(q(c.wanInterface())).append("]] > 0) do={ ")
                .append(":if ([:len [/interface/ethernet find name=").append(q(c.wanInterface())).append(" running]] = 0) do={ ")
                .append(":error \"WAN port has no cable link - plug the ISP cable into ").append(c.wanInterface())
                .append(" or pick a different port\" } }\n");
        s.append(":if ([:len [/ip/dhcp-client find interface=").append(q(c.wanInterface())).append("]] = 0) do={ /ip/dhcp-client add interface=")
                .append(q(c.wanInterface())).append(" disabled=no comment=").append(tag).append(" }\n");
        s.append(":delay 8s\n");
        s.append(":if ([:len [/ip/address find interface=").append(q(c.wanInterface()))
                .append("]] = 0) do={ :error \"No IP address on WAN - the ISP cable is probably in a different port\" }\n");
        s.append("/interface/bridge add name=").append(q(BRIDGE_NAME)).append(" protocol-mode=rstp comment=").append(tag).append('\n');
        if (c.hotspotInterfaces() == null || c.hotspotInterfaces().isEmpty()) {
            // Auto mode: bridge every ethernet port except the WAN uplink, whatever the model.
            s.append(":foreach e in=[/interface/ethernet find] do={ :local n [/interface/ethernet get $e name]; ")
                    .append(":if ($n != ").append(q(c.wanInterface())).append(") do={ ")
                    .append("/interface/bridge/port/remove [find interface=$n]; ")
                    .append("/interface/bridge/port add bridge=").append(q(BRIDGE_NAME))
                    .append(" interface=$n comment=").append(tag).append(" } }\n");
        } else {
            for (String hotspotInterface : c.hotspotInterfaces()) {
                s.append(":if ([:len [/interface find name=").append(q(hotspotInterface)).append("]] > 0) do={ ")
                        .append("/interface/bridge/port/remove [find interface=").append(q(hotspotInterface)).append("]; ")
                        .append("/interface/bridge/port add bridge=").append(q(BRIDGE_NAME))
                        .append(" interface=").append(q(hotspotInterface)).append(" comment=").append(tag).append(" }\n");
            }
        }
        // Keep the router manageable: the hotspot bridge must be in the LAN list, otherwise the
        // default firewall, MAC-WinBox and neighbor discovery ignore it and WinBox can't find the router.
        // Some routers lack the default LAN list entirely (custom reset) — create it if missing.
        s.append(":if ([:len [/interface/list find name=LAN]] = 0) do={ /interface/list add name=LAN comment=").append(tag).append(" }\n");
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
        // Management REST API for instant backend->router push (portal sync, etc.).
        // Reachable ONLY over the WireGuard tunnel; www service is bound to the
        // hub subnet and a dedicated low-privilege user authenticates with the
        // router's RADIUS secret (already known to the backend).
        s.append("/user/group/remove [find name=wb-api]\n");
        s.append("/user/remove [find name=wb-api]\n");
        s.append(":do { /user/group/add name=wb-api policy=read,write,test,rest-api }"
                + " on-error={ /user/group/add name=wb-api policy=read,write,test }\n");
        s.append("/user/add name=wb-api password=").append(q(c.radiusSecret())).append(" group=wb-api\n");
        s.append("/ip/service/set www disabled=no address=").append(c.tunnelSubnet()).append("\n");
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
        s.append(":do { /ip/neighbor/discovery-settings/set discover-interface-list=LAN } on-error={}\n");
        s.append(":do { /tool/mac-server/set allowed-interface-list=LAN } on-error={}\n");
        s.append(":do { /tool/mac-server/mac-winbox/set allowed-interface-list=LAN } on-error={}\n");
        s.append('\n');
        // Tethering protection: TTL-based detection of devices sharing the connection.
        s.append(tetheringRules(c.tetheringMode() == null ? "OFF" : c.tetheringMode()));
        s.append('\n');

        // Management loop installed EARLY — it's the backbone (task queue, bindings,
        // host reports). If a later section is interrupted, the router is still
        // fully managed and self-heals the rest via tasks.
        s.append(managementInstaller(c.publicBaseUrl(), c.token()));
        s.append('\n');

        // 3a. Router-side DNS: the router itself must resolve the billing host
        // for /tool fetch (client DNS is already intercepted by the hotspot).
        s.append("# --- 4a. Router DNS resolution ---\n");
        s.append(":if ([:len [/ip dns get servers]] = 0) do={ /ip dns set servers=1.1.1.1,8.8.8.8 }\n\n");

        // 3b. TLS trust: the public base URL is HTTPS (Let's Encrypt) and
        // RouterOS 7 verifies certificates. Import the Mozilla CA bundle once;
        // every fetch below retries with check-certificate=no as a fallback so
        // provisioning never hard-fails on routers with an empty/old CA store.
        s.append("# --- 4b. CA trust store for HTTPS fetches ---\n");
        s.append(":do { /tool fetch url=\"https://curl.se/ca/cacert.pem\" dst-path=\"cacert.pem\" check-certificate=no } on-error={}\n");
        s.append(":do { /certificate import file-name=\"cacert.pem\" } on-error={}\n");
        s.append(":do { /file remove [find name=\"cacert.pem\"] } on-error={}\n\n");

        // 3. Captive Portal Template Download to flash/hotspot
        s.append("# --- 4. Captive Portal Assets Download ---\n");
        s.append(":do { /file add name=flash/hotspot/login.html contents=\"\" } on-error={}\n");
        for (String tmpl : PORTAL_TEMPLATES) {
            String url = c.publicBaseUrl() + "/api/portal/templates/" + tmpl + "?token=" + c.token();
            String dst = " dst-path=" + q("flash/hotspot/" + tmpl);
            s.append(tlsFetch(url, dst, ":log warning \"wifi-billing: failed fetching " + tmpl + "\"")).append('\n');
        }
        // Two cadences: packages.json (tiny, changes often) refreshes every minute;
        // full template refresh every 30 minutes (branding changes are rare).
        s.append("/system/script add name=wb-pkg-sync policy=").append(POLICY)
                .append(" comment=").append(tag).append(" source={\n")
                .append(tlsFetch(c.publicBaseUrl() + "/api/portal/templates/packages.json?token=" + c.token(),
                        " dst-path=" + q("flash/hotspot/packages.json"), ""))
                .append("\n}\n");
        s.append("/system/scheduler add name=wb-pkg-sync interval=1m on-event=")
                .append(q("/system script run wb-pkg-sync"))
                .append(" comment=").append(tag).append("\n");
        // wb-portal-sync stays a tiny bootstrap: it pulls the server-generated
        // portal-sync.rsc every run, so new portal files sync without ever
        // rewriting this on-router script.
        s.append("/system/script add name=wb-portal-sync policy=").append(POLICY)
                .append(" comment=").append(tag).append(" source={\n")
                .append(portalSyncBootstrap(c.publicBaseUrl(), c.token())).append("}\n");
        s.append("/system/scheduler add name=wb-portal-sync interval=30m on-event=")
                .append(q("/system script run wb-portal-sync"))
                .append(" comment=").append(tag).append("\n");
        s.append("\n");

        // 4. Walled garden (portal/API reachable before login)
        s.append("# --- 5. Walled garden (portal/API reachable before login) ---\n");
        for (String ip : c.walledGardenIps()) {
            s.append("/ip/hotspot/walled-garden/ip add action=accept dst-address=").append(ip)
                    .append(" comment=").append(tag).append('\n');
            // Walled-garden ip rules only exempt the filter chain on some RouterOS builds —
            // also bypass the hotspot dst-nat redirect or clients get TCP resets.
            s.append("/ip/firewall/nat add chain=pre-hotspot action=accept dst-address=").append(ip)
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
        s.append(":local hotspotReady ([:len [/ip hotspot find name=").append(q(HOTSPOT_SERVER)).append(" disabled=no]] > 0)\n");
        s.append(":local radiusReady ([:len [/radius find comment=").append(tag).append("]] > 0)\n");
        s.append(":local portalReady ([:len [/file find name=flash/hotspot/login.html]] > 0)\n");
        s.append(":local body (\"{\\\"token\\\":\\\"").append(c.token())
                .append("\\\",\\\"cpuLoad\\\":\" . $cpu . \",\\\"freeMemory\\\":\" . $freeMem")
                .append(" . \",\\\"totalMemory\\\":\" . $totalMem . \",\\\"uptime\\\":\\\"\" . $up")
                .append(" . \"\\\",\\\"activeUsers\\\":\" . $users")
                .append(" . \",\\\"hotspotReady\\\":\" . $hotspotReady . \",\\\"radiusReady\\\":\" . $radiusReady")
                .append(" . \",\\\"portalReady\\\":\" . $portalReady . \"}\")\n");
        s.append(tlsFetch(telemetryUrl,
                " http-method=post http-header-field=\"Content-Type: application/json\" http-data=$body output=none",
                ":log warning \"wifi-billing: telemetry push failed\"")).append('\n');
        s.append("}\n");
        s.append("/system/scheduler add name=").append(q(TELEMETRY_NAME))
                .append(" interval=2m on-event=").append(q("/system script run " + TELEMETRY_NAME))
                .append(" comment=").append(tag).append("\n\n");

        // 6. Key registration — retried every 30s until it succeeds, then self-removes.
        //    Survives partial-import failure and transient network issues.
        s.append("# --- 7. Register this router's WireGuard public key with the hub ---\n");
        s.append(":delay 2s\n");
        s.append("/system/script/add name=wb-register policy=read,write,test source={\n");
        s.append("    :local wbPub [/interface/wireguard get [find name=").append(iface).append("] public-key]\n");
        s.append("    :local wbOk false\n");
        s.append("    :do { /tool fetch url=").append(q(registerUrl))
                .append(" http-method=post http-header-field=\"Content-Type: application/json\"")
                .append(" http-data=(\"{\\\"publicKey\\\":\\\"\" . $wbPub . \"\\\"}\") output=none;\n");
        s.append("        :set wbOk true\n");
        s.append("    } on-error={ :do { /tool fetch url=").append(q(registerUrl))
                .append(" http-method=post http-header-field=\"Content-Type: application/json\"")
                .append(" http-data=(\"{\\\"publicKey\\\":\\\"\" . $wbPub . \"\\\"}\") output=none check-certificate=no;\n");
        s.append("        :set wbOk true\n");
        s.append("    } on-error={ :log warning \"wifi-billing: key registration failed - will retry\" } }\n");
        s.append("    :if ($wbOk) do={\n");
        s.append("        /system/scheduler/remove [find name=wb-register];\n");
        s.append("        /system/script/remove [find name=wb-register];\n");
        s.append("        :log info \"wifi-billing: WireGuard public key registered\"\n");
        s.append("    }\n");
        s.append("}\n");
        s.append("/system/scheduler/add name=wb-register interval=30s on-event=\"/system script run wb-register\" comment=").append(tag).append("\n");
        s.append(":do { /system script run wb-register } on-error={}\n\n");

        s.append("/system/script/add name=wb-provisioned policy=read comment=")
                .append(q("wifi-billing:" + PROVISIONING_VERSION)).append(" source={}\n");
        s.append(":do { /system script run ").append(TELEMETRY_NAME).append(" } on-error={}\n");
        s.append(":log info \"wifi-billing: provisioning complete\"\n");
        // Signal the backend that the full script ran end-to-end — the dashboard
        // can then show "provisioned" instead of guessing from telemetry.
        s.append(tlsFetch(c.publicBaseUrl() + "/api/router/provision/" + c.token() + "/complete",
                " http-method=post output=none", "")).append('\n');
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
                + ":for i from=1 to=3 do={\n"
                + tlsFetch(prefix + "/management.rsc?wait=25", " dst-path=wb-management.rsc", ":delay 2s")
                + ":do { /import wb-management.rsc } on-error={}\n"
                + "}\n"
                + ":do { :local hosts [/ip/dhcp-server/lease/print as-value where status=bound]; "
                + tlsFetch(prefix + "/hosts",
                        " http-method=post http-header-field=\"Content-Type: application/json\" http-data=[:serialize to=json value=$hosts] output=none",
                        ":log warning \"wifi-billing host report failed\"")
                + " } on-error={}\n}\n"
                + "/system/scheduler/add name=wb-management interval=1m on-event=\"/system script run wb-management\"\n"
                + ":do { /system/script/run wb-management } on-error={}\n";
    }

    /** RouterOS commands that fetch every portal template file to flash/hotspot. */
    public static String portalSyncScript(String publicBaseUrl, String token) {
        StringBuilder sb = new StringBuilder();
        for (String tmpl : PORTAL_TEMPLATES) {
            sb.append(tlsFetch(publicBaseUrl + "/api/portal/templates/" + tmpl + "?token=" + token,
                    " dst-path=" + q("flash/hotspot/" + tmpl), "")).append('\n');
        }
        return sb.toString();
    }

    /** Bootstrap body for the on-router wb-portal-sync script: fetch + import portal-sync.rsc. */
    public static String portalSyncBootstrap(String publicBaseUrl, String token) {
        return tlsFetch(publicBaseUrl + "/api/portal/templates/portal-sync.rsc?token=" + token,
                " dst-path=" + q("wb-portal-sync.rsc"), "")
                + ":do { /import wb-portal-sync.rsc } on-error={}\n";
    }

    /**
     * /tool fetch with a TLS-verified first attempt and a check-certificate=no
     * retry. RouterOS 7 verifies certs by default and ships without the Let's
     * Encrypt chain on many devices; the CA bundle import above handles fresh
     * installs, and this fallback keeps provisioning working when the import
     * failed (offline at that moment, ancient RouterOS, custom cert store).
     */
    private static String tlsFetch(String url, String args, String onErrorBody) {
        return ":do { /tool fetch url=" + q(url) + args + " } on-error={"
                + " :do { /tool fetch url=" + q(url) + args + " check-certificate=no } on-error={"
                + onErrorBody + " } }";
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
