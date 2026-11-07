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
    public static final String PROVISIONING_VERSION = "2026.10.5";

    private static final String IFACE_NAME = "wg-billing";
    private static final String TELEMETRY_NAME = "wb-telemetry";
    private static final String BRIDGE_NAME = "wb-hotspot-bridge";
    private static final String POOL_NAME = "wb-hotspot-pool";
    private static final String DHCP_NAME = "wb-hotspot-dhcp";
    private static final String HOTSPOT_PROFILE = "wb-hotspot-profile";
    private static final String HOTSPOT_SERVER = "wb-hotspot";
    private static final String POLICY = "read,write,policy,test";
    private static final String TAG = "wifi-billing";

    // The hotspot network. Schools get /21 (10.10.0.0-10.10.7.255, ~2,000 devices) with shorter
    // leases so departed phones free their address; everyone else keeps /24. The router stays 10.10.0.1.
    private static final String SMALL_GATEWAY = "10.10.0.1/24", SMALL_NETWORK = "10.10.0.0/24",
            SMALL_RANGE = "10.10.0.10-10.10.0.254", SMALL_LEASE = "1h";
    private static final String LARGE_GATEWAY = "10.10.0.1/21", LARGE_NETWORK = "10.10.0.0/21",
            LARGE_RANGE = "10.10.0.10-10.10.7.254", LARGE_LEASE = "30m";
    /** Customer addresses a binding may use on a school (large) or ordinary network. */
    public static final String LARGE_NETWORK_CIDR = LARGE_NETWORK, SMALL_NETWORK_CIDR = SMALL_NETWORK;

    /**
     * Enlarges an already provisioned hotspot network from /24 to the school size, in place:
     * the router keeps 10.10.0.1 and existing leases stay valid. Safe to run again.
     */
    public static String largeNetworkScript() {
        String tag = q(TAG);
        return ":local a [/ip/address find comment=" + tag + " address=\"" + SMALL_GATEWAY + "\"]\n"
                + ":if ([:len $a] > 0) do={ /ip/address set $a address=" + LARGE_GATEWAY + " }\n"
                + "/ip/pool set [find name=" + q(POOL_NAME) + "] ranges=" + LARGE_RANGE + "\n"
                + "/ip/dhcp-server/network set [find comment=" + tag + "] address=" + LARGE_NETWORK + "\n"
                + "/ip/dhcp-server set [find name=" + q(DHCP_NAME) + "] lease-time=" + LARGE_LEASE + "\n"
                + ":log info \"wifi-billing: hotspot network enlarged to " + LARGE_NETWORK + "\"\n";
    }

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
            String tetheringMode,
            /** One-time setup code; authorizes only this setup's register/complete callbacks. */
            String setupCode,
            /** Optional WinBox agent installer snippet. */
            String winboxInstaller,
            /** Schools: a /21 hotspot network (~2,000 devices) instead of /24 (~245). */
            boolean largeNetwork
    ) {
        public ScriptContext(
                String routerName, String wanInterface, List<String> hotspotInterfaces, String wifiSsid,
                String token, String wgIp, int prefixLength, String radiusSecret, String radiusServerIp,
                String serverPublicKey, String endpointHost, int endpointPort, String tunnelSubnet,
                String publicBaseUrl, Set<String> walledGardenIps, Set<String> walledGardenHosts,
                String tetheringMode, String setupCode, String winboxInstaller) {
            this(routerName, wanInterface, hotspotInterfaces, wifiSsid, token, wgIp, prefixLength,
                 radiusSecret, radiusServerIp, serverPublicKey, endpointHost, endpointPort, tunnelSubnet,
                 publicBaseUrl, walledGardenIps, walledGardenHosts, tetheringMode, setupCode, winboxInstaller, false);
        }

        public ScriptContext(
                String routerName, String wanInterface, List<String> hotspotInterfaces, String wifiSsid,
                String token, String wgIp, int prefixLength, String radiusSecret, String radiusServerIp,
                String serverPublicKey, String endpointHost, int endpointPort, String tunnelSubnet,
                String publicBaseUrl, Set<String> walledGardenIps, Set<String> walledGardenHosts,
                String tetheringMode, String setupCode) {
            this(routerName, wanInterface, hotspotInterfaces, wifiSsid, token, wgIp, prefixLength,
                 radiusSecret, radiusServerIp, serverPublicKey, endpointHost, endpointPort, tunnelSubnet,
                 publicBaseUrl, walledGardenIps, walledGardenHosts, tetheringMode, setupCode, null, false);
        }
    }

    /**
     * Firewall rules implementing tethering protection. Tethered traffic arrives
     * with TTL decremented by the sharing phone (63 on Android/iOS, 127 on Windows).
     * In BLOCK mode, also sets TTL=1 on packets leaving the hotspot bridge so
     * connected devices cannot forward/hotspot packets to downstream devices.
     * mode: "OFF" (stale rules removed), "LOG" (detect only), "BLOCK" (drop + TTL=1 lock).
     */
    /**
     * Moves our rules above the router's own forward rules. Most routers accept or fast-track established
     * connections near the top of the chain; rules appended below them never see the TLS ClientHello, so
     * SNI blocks silently fail and only IP matches work (enough for TikTok, not for YouTube's rotating
     * Google servers). Dynamic hotspot rules stay where RouterOS keeps them. One self-contained line, so it
     * works however the router runs the task; a router with no other forward rules simply skips it.
     */
    static String moveAboveRouterRules(String commentPrefix) {
        return ":do { /ip/firewall/filter/move [find where comment~\"" + commentPrefix + "\"] destination=[:pick [find where chain=forward dynamic=no !(comment~\"^wb-\")] 0] } on-error={}\n";
    }

    /**
     * Makes new site rules take effect at once instead of when phones' cached addresses expire: the router's
     * DNS cache is cleared so every lookup is learned afresh, and the open connections of filtered students
     * and staff are dropped so apps reconnect through the new rules. Users stay signed in; apps just reconnect.
     */
    static String applyNow() {
        StringBuilder s = new StringBuilder(":do { /ip/dns/cache/flush } on-error={}\n");
        for (String list : List.of(STAFF_FREE_ADDRESS_LIST, STUDENT_FREE_ADDRESS_LIST)) {
            s.append(":do { :foreach e in=[/ip/firewall/address-list/find where list=").append(q(list))
                    .append("] do={ :local ip [/ip/firewall/address-list/get $e address]; /ip/firewall/connection/remove [find where src-address~(\"^\" . $ip . \":\")] } } on-error={}\n");
        }
        return s.toString();
    }

    public static String tetheringRules(String mode) {
        StringBuilder s = new StringBuilder();
        s.append("/ip/firewall/filter/remove [find where comment~\"^wb-tether\"]\n");
        s.append("/ip/firewall/mangle/remove [find where comment~\"^wb-tether\"]\n");
        if ("LOG".equals(mode) || "BLOCK".equals(mode)) {
            String action = "BLOCK".equals(mode) ? "drop" : "log";
            // TTL is decremented before the forward chain, so direct clients
            // appear as 63/127. Tethered traffic passed an extra NAT hop: 62/126.
            s.append("/ip/firewall/filter/add chain=forward ttl=equal:62 action=").append(action)
                    .append(" log=yes log-prefix=\"wb-tether\" comment=\"wb-tether: android/ios tethered\"\n");
            s.append("/ip/firewall/filter/add chain=forward ttl=equal:126 action=").append(action)
                    .append(" log=yes log-prefix=\"wb-tether\" comment=\"wb-tether: windows tethered\"\n");
            if ("BLOCK".equals(mode)) {
                s.append("/ip/firewall/mangle/add chain=postrouting out-interface=").append(q(BRIDGE_NAME))
                        .append(" action=change-ttl new-ttl=set:1 passthrough=yes comment=\"wb-tether: lock downstream ttl=1\"\n");
            }
            s.append(moveAboveRouterRules("^wb-tether"));
        }
        return s.toString();
    }

    public static final String STUDENT_FREE_ADDRESS_LIST = "wb-student-free";
    public static final String STAFF_FREE_ADDRESS_LIST = "wb-staff-free";

    private static final java.util.Map<String, List<String>> APP_PRESET_DOMAINS = java.util.Map.ofEntries(
            java.util.Map.entry("@whatsapp", List.of("whatsapp.com", "whatsapp.net", "wa.me")),
            java.util.Map.entry("@youtube", List.of("youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "youtube-nocookie.com", "youtubei.googleapis.com", "youtube.googleapis.com")),
            java.util.Map.entry("@chatgpt", List.of("chatgpt.com", "openai.com", "oaistatic.com", "oaiusercontent.com")),
            java.util.Map.entry("@gmail", List.of("gmail.com", "googlemail.com", "mail.google.com", "accounts.google.com", "gstatic.com", "googleusercontent.com")),
            java.util.Map.entry("@tiktok", List.of("tiktok.com", "tiktokcdn.com", "tiktokv.com", "musical.ly", "byteoversea.com")),
            java.util.Map.entry("@facebook", List.of("facebook.com", "fb.com", "fbcdn.net", "messenger.com")),
            java.util.Map.entry("@instagram", List.of("instagram.com", "cdninstagram.com")),
            java.util.Map.entry("@twitter", List.of("x.com", "twitter.com", "twimg.com", "t.co")),
            java.util.Map.entry("@netflix", List.of("netflix.com", "nflxvideo.net", "nflximg.net", "nflxso.net")),
            java.util.Map.entry("@wikipedia", List.of("wikipedia.org", "wikimedia.org", "wikidata.org")),
            java.util.Map.entry("@scholar", List.of("scholar.google.com", "books.google.com")),
            java.util.Map.entry("@khanacademy", List.of("khanacademy.org", "kastatic.org", "kasandbox.org"))
    );

    /**
     * Hosts phones and laptops probe to decide whether the WiFi "has internet". Android also runs an HTTPS probe
     * to www.google.com and reports "connected, limited" when only the plain one passes. Allow lists always
     * include these, or every device on an allow list warns about its connection even though allowed sites work.
     */
    static final List<String> CONNECTIVITY_CHECK_DOMAINS = List.of(
            "connectivitycheck.gstatic.com", "connectivitycheck.android.com", "clients3.google.com", "www.google.com",
            "captive.apple.com", "www.msftconnecttest.com", "www.msftncsi.com");

    /**
     * Parses a comma/newline-separated list of app preset keys (@whatsapp, @youtube, etc.) and domain names into
     * a deduplicated, sanitized list. A domain that belongs to a known service brings in the rest of that service
     * (typing youtube.com also covers googlevideo.com, where the videos actually come from).
     */
    public static Set<String> expandSiteRules(String raw) {
        java.util.Set<String> domains = new java.util.LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return domains;
        for (String token : raw.split("[,;\\s\\r\\n]+")) {
            String item = token.trim().toLowerCase(java.util.Locale.ROOT);
            if (item.isEmpty()) continue;
            if (item.startsWith("@")) {
                List<String> preset = APP_PRESET_DOMAINS.get(item);
                if (preset != null) domains.addAll(preset);
                continue;
            }
            item = item.replaceFirst("^https?://", "");
            int slash = item.indexOf('/');
            if (slash >= 0) item = item.substring(0, slash);
            int colon = item.indexOf(':');
            if (colon >= 0) item = item.substring(0, colon);
            while (item.startsWith("*.") || item.startsWith(".")) {
                item = item.substring(1);
            }
            if (item.length() <= 120 && item.matches("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")) {
                domains.add(item);
            }
            if (domains.size() >= 80) break;
        }
        for (List<String> preset : APP_PRESET_DOMAINS.values()) {
            if (domains.stream().anyMatch(d -> preset.stream().anyMatch(p -> d.equals(p) || d.endsWith("." + p)))) domains.addAll(preset);
        }
        return domains;
    }

    /** The address list a site's learned and resolved addresses go into; shared by every group that names the site. */
    static String siteList(String domain) { return "wb-site-" + domain; }

    /**
     * Generates RouterOS v7 rules for school groups:
     * 1) Student free daily data: a strict allow list on wb-student-free.
     * 2) Staff free WiFi: ALLOW_LIST or BLOCK_LIST on wb-staff-free.
     *
     * Every selected site gets its own address list, filled from a forwarding DNS entry (the site and all its sub-domains), so services that stream from rotating hosts
     * (YouTube's googlevideo.com) are matched by address. A site named by several groups is learned once and each
     * group allows or blocks it from the same list. TLS names (SNI) are matched as well. Filtered groups must use the
     * router for DNS and cannot use DNS-over-TLS, so lookups can't bypass the learning.
     */
    public static String schoolFilterRules(boolean studentFreeEnabled, String studentAllowedSites,
                                           String staffSiteMode, String staffSiteRules, String publicBaseUrl) {
        StringBuilder s = new StringBuilder();
        s.append("/ip/firewall/filter/remove [find where comment~\"^wb-school-filter\"]\n");
        s.append("/ip/firewall/address-list/remove [find where comment~\"^wb-school-filter\"]\n");
        s.append(":do { /ip/firewall/nat/remove [find where comment~\"^wb-school-filter\"] } on-error={}\n");
        s.append(":do { /ip/dns/static/remove [find where comment~\"^wb-school-filter\"] } on-error={}\n");

        Set<String> always = new java.util.LinkedHashSet<>(CONNECTIVITY_CHECK_DOMAINS);
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) always.addAll(expandSiteRules(publicBaseUrl));

        Set<String> student = new java.util.LinkedHashSet<>();
        if (studentFreeEnabled) { student.addAll(expandSiteRules(studentAllowedSites)); student.addAll(always); }
        String mode = staffSiteMode == null ? "ALL" : staffSiteMode.trim().toUpperCase(java.util.Locale.ROOT);
        Set<String> staff = new java.util.LinkedHashSet<>();
        if ("ALLOW_LIST".equals(mode)) { staff.addAll(expandSiteRules(staffSiteRules)); staff.addAll(always); }
        else if ("BLOCK_LIST".equals(mode)) staff.addAll(expandSiteRules(staffSiteRules));

        // Most specific names first: a lookup is learned by the first DNS entry that matches it.
        List<String> sites = new java.util.ArrayList<>(new java.util.LinkedHashSet<String>() {{ addAll(student); addAll(staff); }});
        sites.sort(java.util.Comparator.comparingInt((String d) -> d.split("\\.").length).reversed());
        // Filtered groups must look sites up through the router, so each answer is learned before they connect.
        for (String d : sites) {
            s.append(":do { /ip/dns/static/add name=").append(q(d)).append(" type=FWD forward-to=1.1.1.1 match-subdomain=yes address-list=")
                    .append(q(siteList(d))).append(" comment=\"wb-school-filter: dns ").append(d).append("\" } on-error={}\n");
        }

        if (studentFreeEnabled) groupRules(s, STUDENT_FREE_ADDRESS_LIST, "student", student, true);
        if ("ALLOW_LIST".equals(mode)) groupRules(s, STAFF_FREE_ADDRESS_LIST, "staff", staff, true);
        else if ("BLOCK_LIST".equals(mode) && !staff.isEmpty()) groupRules(s, STAFF_FREE_ADDRESS_LIST, "staff", staff, false);

        s.append(moveAboveRouterRules("^wb-school-filter"));
        s.append(applyNow());
        return s.toString();
    }

    /** One group's rules: allow only its sites and drop the rest, or drop its sites and allow the rest. */
    private static void groupRules(StringBuilder s, String group, String who, Set<String> sites, boolean allowOnly) {
        String src = "/ip/firewall/filter/add chain=forward src-address-list=" + q(group);
        for (String protocol : List.of("udp", "tcp")) {
            s.append(":do { /ip/firewall/nat/add chain=dstnat src-address-list=").append(q(group)).append(" protocol=").append(protocol)
                    .append(" dst-port=53 action=redirect to-ports=53 comment=\"wb-school-filter: ").append(who).append(" force dns ").append(protocol).append("\" } on-error={}\n");
        }
        s.append(src).append(" protocol=tcp dst-port=853 action=reject reject-with=tcp-reset comment=\"wb-school-filter: ").append(who).append(" no private dns\"\n");
        if (allowOnly) {
            s.append(src).append(" protocol=udp dst-port=53 action=accept comment=\"wb-school-filter: ").append(who).append(" dns udp\"\n");
            s.append(src).append(" protocol=tcp dst-port=53 action=accept comment=\"wb-school-filter: ").append(who).append(" dns tcp\"\n");
        }
        s.append(src).append(" protocol=udp dst-port=443 action=reject reject-with=icmp-port-unreachable comment=\"wb-school-filter: ").append(who).append(" disable quic\"\n");
        String action = allowOnly ? "accept" : "drop";
        for (String d : sites) {
            // Block lists also match TLS names, catching apps before their first lookup is learned. Allow lists rely
            // on the learned addresses alone, which keeps the router task small.
            if (!allowOnly) s.append(src).append(" protocol=tcp dst-port=443 tls-host=").append(q("*" + d)).append(" action=drop")
                    .append(" comment=\"wb-school-filter: ").append(who).append(" block sni ").append(d).append("\"\n");
            s.append(src).append(" dst-address-list=").append(q(siteList(d))).append(" action=").append(action)
                    .append(" comment=\"wb-school-filter: ").append(who).append(allowOnly ? " allow " : " block ").append(d).append("\"\n");
        }
        if (allowOnly) s.append(src).append(" action=drop comment=\"wb-school-filter: ").append(who).append(" drop other\"\n");
    }

    public String generate(ScriptContext c) {
        String iface = q(IFACE_NAME);
        String tag = q(TAG);
        // Setup callbacks use the one-time setup code, never the public router id.
        String registerUrl = c.publicBaseUrl() + "/api/router/setup/" + c.setupCode() + "/register";
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
        String gateway = c.largeNetwork() ? LARGE_GATEWAY : SMALL_GATEWAY;
        s.append(":local wbAddress [/ip/address find address=\"").append(gateway).append("\"]\n");
        s.append(":if ([:len $wbAddress] > 0 && [/ip/address get [:pick $wbAddress 0] comment] != ")
                .append(tag).append(") do={ :error \"").append(gateway).append(" is already used by an unmanaged configuration; no changes made\" }\n");
        s.append(":log info \"wifi-billing: provisioning started\"\n\n");
        s.append(":do { /export file=before-wifi-billing } on-error={}\n\n");

        // Clock sync FIRST: an NTP time jump while /import or /tool/fetch is
        // running aborts it mid-script. Sync and settle the clock before any
        // downloads or config changes (bounded wait — WAN may be slow to come up).
        s.append("# --- 0a. Sync clock before anything else ---\n");
        s.append("/system/clock/set time-zone-name=Etc/UTC time-zone-autodetect=no\n");
        s.append("/system/ntp/client/set enabled=yes\n");
        s.append(":if ([:len [/system/ntp/client/servers find address=pool.ntp.org]] = 0) do={ /system/ntp/client/servers add address=pool.ntp.org }\n");
        // Bounded settle then one re-check: strict parsers reject :for/:set
        // inside do={ } blocks here, and every fetch below already has a
        // check-certificate=no fallback, so a stale clock degrades gracefully.
        s.append(":delay 10s\n");
        s.append(":local wbDate [/system/clock get date]\n");
        s.append(":local wbYear [:tonum [:pick $wbDate ([:len $wbDate] - 4) [:len $wbDate]]]\n");
        s.append(":if ($wbYear < 2026) do={ :delay 15s }\n\n");

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
        s.append("/ip/address add address=").append(gateway).append(" interface=").append(q(BRIDGE_NAME)).append(" comment=").append(tag).append('\n');
        s.append("/ip/pool add name=").append(q(POOL_NAME)).append(" ranges=").append(c.largeNetwork() ? LARGE_RANGE : SMALL_RANGE).append('\n');
        s.append("/ip/dhcp-server add name=").append(q(DHCP_NAME)).append(" interface=").append(q(BRIDGE_NAME))
                .append(" address-pool=").append(q(POOL_NAME)).append(" lease-time=").append(c.largeNetwork() ? LARGE_LEASE : SMALL_LEASE).append(" disabled=no\n");
        s.append("/ip/dhcp-server/network add address=").append(c.largeNetwork() ? LARGE_NETWORK : SMALL_NETWORK)
                .append(" gateway=10.10.0.1 dns-server=10.10.0.1 comment=").append(tag).append('\n');
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
        s.append(":do { /user/group/add name=wb-api policy=read,write,test,api,rest-api }"
                + " on-error={ /user/group/add name=wb-api policy=read,write,test,api }\n");
        s.append("/user/add name=wb-api password=").append(q(c.radiusSecret())).append(" group=wb-api\n");
        s.append(":do { /ip/service/set www disabled=no allowed-address=").append(c.tunnelSubnet())
                .append(" } on-error={ /ip/service/set www disabled=no address=").append(c.tunnelSubnet())
                .append(" }\n");
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
        s.append(tlsFetch(c.publicBaseUrl() + "/api/router/setup/" + c.setupCode() + "/complete",
                " http-method=post output=none", "")).append('\n');
        if (c.winboxInstaller() != null && !c.winboxInstaller().isBlank()) {
            s.append("\n# --- 8. AirPairy Remote WinBox agent ---\n");
            s.append(c.winboxInstaller().strip()).append('\n');
        }
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
                + ":do { /file remove [find name=\"wb-management.rsc\"] } on-error={}\n"
                + tlsFetch(prefix + "/management.rsc?wait=8", " dst-path=wb-management.rsc", ":delay 2s")
                + "; :if ([:len [/file find name=\"wb-management.rsc\"]] > 0) do={ :do { /import wb-management.rsc } on-error={} }\n"
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
                + "; :do { /import wb-portal-sync.rsc } on-error={}\n";
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
