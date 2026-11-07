package com.wifibilling.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterScriptGeneratorTest {

    private final RouterScriptGenerator generator = new RouterScriptGenerator();
    private static final String SETUP = "su_TESTsetupCodeMarker0000000000000";

    @Test
    void setupCallbacksUseTheOneTimeCodeNotThePublicRouterId() {
        String s = render("s3cret");
        assertTrue(s.contains("/api/router/setup/" + SETUP + "/register"), "register callback uses the setup code");
        assertTrue(s.contains("/api/router/setup/" + SETUP + "/complete"), "complete callback uses the setup code");
        assertFalse(s.contains("/api/router/provision/tok_ABC123xyz/register"), "public id must not authorize key registration");
        assertFalse(s.contains("/api/router/provision/tok_ABC123xyz/complete"));
        assertFalse(s.contains("/api/router/provision/tok_ABC123xyz\""), "no link back to the retired script route");
    }

    private String render(String secret) {
        return generator.generate(new RouterScriptGenerator.ScriptContext(
                "Cafe \"Main\"", "ether1", List.of("ether2", "ether3", "wlan1"), "Cafe WiFi",
                "tok_ABC123xyz", "10.9.0.5", 16,
                secret, "10.9.0.1",
                "yoay+E/LyCqRq0xxqsIRJdRoqe8XjRVmkMe3nDx5wEc=", "203.0.113.10", 443, "10.9.0.0/16",
                "http://203.0.113.10:8080", Set.of("203.0.113.10"), Set.of("momo.mtn.com"), "BLOCK", SETUP));
    }

    @Test
    void schoolsGetALargeHotspotNetworkAndOthersKeepTheirs() {
        String ordinary = render("s3cret");
        assertTrue(ordinary.contains("/ip/address add address=10.10.0.1/24 interface=\"wb-hotspot-bridge\""));
        assertTrue(ordinary.contains("ranges=10.10.0.10-10.10.0.254"));
        assertTrue(ordinary.contains("lease-time=1h"));
        assertTrue(ordinary.contains("/ip/dhcp-server/network add address=10.10.0.0/24 gateway=10.10.0.1"));

        String school = generator.generate(new RouterScriptGenerator.ScriptContext(
                "School", "ether1", List.of("ether2"), "School WiFi", "tok_ABC123xyz", "10.9.0.5", 16, "s3cret", "10.9.0.1",
                "yoay+E/LyCqRq0xxqsIRJdRoqe8XjRVmkMe3nDx5wEc=", "203.0.113.10", 443, "10.9.0.0/16",
                "http://203.0.113.10:8080", Set.of("203.0.113.10"), Set.of(), "OFF", SETUP, null, true));
        assertTrue(school.contains("/ip/address add address=10.10.0.1/21 interface=\"wb-hotspot-bridge\""));
        assertTrue(school.contains("ranges=10.10.0.10-10.10.7.254"));
        assertTrue(school.contains("lease-time=30m"));
        assertTrue(school.contains("/ip/dhcp-server/network add address=10.10.0.0/21 gateway=10.10.0.1 dns-server=10.10.0.1"));
        assertTrue(school.contains("hotspot-address=10.10.0.1 dns-name=login.wifi"), "the router address and login page are unchanged");

        String enlarge = RouterScriptGenerator.largeNetworkScript();
        assertTrue(enlarge.contains("address=\"10.10.0.1/24\"]"), "only our own /24 address is changed");
        assertTrue(enlarge.contains("/ip/address set $a address=10.10.0.1/21"));
        assertTrue(enlarge.contains("/ip/pool set [find name=\"wb-hotspot-pool\"] ranges=10.10.0.10-10.10.7.254"));
        assertTrue(enlarge.contains("/ip/dhcp-server/network set [find comment=\"wifi-billing\"] address=10.10.0.0/21"));
    }

    @Test
    void rendersCoreSectionsRouterOsV7() {
        String s = render("s3cret");
        System.out.println(s);
        assertTrue(s.contains("/interface/bridge add name=\"wb-hotspot-bridge\""));
        assertTrue(s.contains("/ip/dhcp-server add name=\"wb-hotspot-dhcp\""));
        assertTrue(s.contains("/ip/firewall/nat add chain=srcnat out-interface=\"ether1\" action=masquerade"));
        assertTrue(s.contains("ssid=\\\"Cafe WiFi\\\""));
        assertTrue(s.contains("/ip/hotspot add name=\"wb-hotspot\""));
        assertTrue(s.contains("/interface/wireguard add name=\"wg-billing\" listen-port=13231 mtu=1420"));
        assertTrue(s.contains("/interface/wireguard/peers add interface=\"wg-billing\" public-key=\"yoay+E/LyCqRq0xxqsIRJdRoqe8XjRVmkMe3nDx5wEc=\""));
        assertTrue(s.contains("endpoint-address=\"203.0.113.10\" endpoint-port=443 allowed-address=10.9.0.0/16 persistent-keepalive=25s"));
        assertTrue(s.contains("/ip/address add address=10.9.0.5/16 interface=\"wg-billing\""));
        assertTrue(s.contains("/radius add address=10.9.0.1 secret=\"s3cret\" service=hotspot,login src-address=10.9.0.5 timeout=3s"));
        assertTrue(s.contains("/radius/incoming/set accept=yes port=3799"));
        assertTrue(s.contains("/ip/hotspot/profile add name=\"wb-hotspot-profile\""));
        assertTrue(s.contains("/file add name=flash/hotspot/login.html"));
        assertTrue(s.contains("dst-path=\"flash/hotspot/login.html\""));
        assertTrue(s.contains("/ip/hotspot/walled-garden/ip add action=accept dst-address=203.0.113.10"));
        assertTrue(s.contains("/ip/hotspot/walled-garden add action=allow dst-host=\"momo.mtn.com\""));
        assertTrue(s.contains("/system/scheduler add name=\"wb-telemetry\""));
        assertTrue(s.contains("http://203.0.113.10:8080/api/router/setup/" + SETUP + "/register"));
        assertTrue(s.contains(":local wbPub [/interface/wireguard get [find name=\"wg-billing\"] public-key]"));
        assertTrue(s.contains("http-data=(\"{\\\"publicKey\\\":\\\"\" . $wbPub . \"\\\"}\")"));
        // tethering BLOCK mode emits drop rules + downstream TTL=1 lock
        assertTrue(s.contains("/ip/firewall/filter/add chain=forward ttl=equal:62 action=drop"));
        assertTrue(s.contains("/ip/firewall/filter/add chain=forward ttl=equal:126 action=drop"));
        assertTrue(s.contains("/ip/firewall/mangle/add chain=postrouting out-interface=\"wb-hotspot-bridge\" action=change-ttl new-ttl=set:1"));
        assertTrue(s.contains("# Provisioning-Version: " + RouterScriptGenerator.PROVISIONING_VERSION));
        assertTrue(s.contains("find name=wb-provisioned comment=$wbMarker"));
        assertTrue(s.contains("Existing wb-hotspot-bridge is not managed by WiFi-Billing"));
        assertTrue(s.contains("\\\"hotspotReady\\\":"));
        assertTrue(s.contains("/system/script/add name=wb-provisioned"));
        assertTrue(s.indexOf("Existing wb-hotspot-bridge") < s.indexOf("Remove previous provisioning"));
    }

    @Test
    void escapesRouterOsSpecialCharactersInSecret() {
        String s = render("a\"b$c\\d");
        assertTrue(s.contains("secret=\"a\\\"b\\$c\\\\d\""));
    }

    @Test
    void newlineInRouterNameCannotInjectCommands() {
        String s = generator.generate(new RouterScriptGenerator.ScriptContext(
                "evil\n/system reset-configuration", "ether1", List.of("ether2"), "Cafe WiFi",
                "tok_ABC123xyz", "10.9.0.5", 16,
                "x", "10.9.0.1", "yoay+E/LyCqRq0xxqsIRJdRoqe8XjRVmkMe3nDx5wEc=", "203.0.113.10", 443,
                "10.9.0.0/16", "http://203.0.113.10:8080", Set.of(), Set.of(), "OFF", SETUP));
        assertFalse(s.contains("\n/system reset-configuration"));
    }

    @Test
    void newlineInQuotedValueCannotInjectCommands() {
        assertFalse(RouterScriptGenerator.q("Cafe\n/system reset-configuration")
                .contains("\n/system reset-configuration"));
    }

    /**
     * /import parses a top-level multi-line do={ } block as ending at its first
     * inner statement, aborting the whole import with a syntax error. Blocks
     * inside a script body (source={ ... }) are stored as text and parsed later
     * by RouterOS, so only the top level is constrained.
     */
    @Test
    void topLevelBlocksStayOnOneLine() {
        String s = render("s3cret");
        int scriptBodyDepth = 0;
        int lineNo = 0;
        for (String line : s.split("\n", -1)) {
            lineNo++;
            String trimmed = line.trim();
            if (scriptBodyDepth == 0 && trimmed.endsWith("do={")) {
                throw new AssertionError(
                        "Top-level multi-line do={ block at line " + lineNo + ": " + trimmed
                                + " — /import cannot parse this; keep it on one line.");
            }
            if (trimmed.contains("source={")) {
                scriptBodyDepth++;
            } else if (scriptBodyDepth > 0 && trimmed.equals("}")) {
                scriptBodyDepth--;
            }
        }
    }

    @Test
    void ntpWaitLoopIsASingleStatement() {
        String s = render("s3cret");
        // No :for loop or :set inside blocks — strict parsers reject them.
        assertFalse(s.contains(":for wbNtpWait"), "NTP wait must not use a :for loop");
        assertFalse(s.contains(":set wbDate"), "NTP wait must not re-:set inside a block");
        assertTrue(s.contains(":delay 10s"), "NTP wait must use a bounded :delay");
    }

    @Test
    void schoolFilterRulesRenderStudentWhitelistAndStaffAppPolicies() {
        String allowRules = generator.schoolFilterRules(
                true,
                "elearning.school.ac.ug, @wikipedia, chatgpt.com",
                "ALLOW_LIST",
                "@whatsapp, @youtube, chatgpt.com, @tiktok",
                "https://app.qticket.dev"
        );
        // Each site has one shared address list, filled from DNS for the site and all its sub-domains
        assertTrue(allowRules.contains("/ip/dns/static/add name=\"elearning.school.ac.ug\" type=FWD forward-to=1.1.1.1 match-subdomain=yes address-list=\"wb-site-elearning.school.ac.ug\""));
        assertTrue(allowRules.contains("/ip/dns/static/add name=\"googlevideo.com\" type=FWD forward-to=1.1.1.1 match-subdomain=yes address-list=\"wb-site-googlevideo.com\""));
        assertEquals(1, allowRules.split("/ip/dns/static/add name=\"chatgpt.com\"", -1).length - 1, "a site named by both groups is learned once");
        // ...and both groups use it
        assertTrue(allowRules.contains("src-address-list=\"wb-student-free\" dst-address-list=\"wb-site-chatgpt.com\" action=accept"));
        assertTrue(allowRules.contains("src-address-list=\"wb-staff-free\" dst-address-list=\"wb-site-chatgpt.com\" action=accept"));
        // Student strict allow list
        assertTrue(allowRules.contains("src-address-list=\"wb-student-free\" dst-address-list=\"wb-site-wikipedia.org\" action=accept"));
        assertTrue(allowRules.contains("src-address-list=\"wb-student-free\" dst-address-list=\"wb-site-app.qticket.dev\" action=accept"),
                "portal host is always allowed so students can buy packages");
        assertTrue(allowRules.contains("src-address-list=\"wb-student-free\" action=drop comment=\"wb-school-filter: student drop other\""));
        // Staff allow list with expanded presets; typing chatgpt.com brings the rest of ChatGPT
        for (String site : List.of("whatsapp.com", "youtube.com", "googlevideo.com", "tiktok.com", "oaistatic.com")) {
            assertTrue(allowRules.contains("src-address-list=\"wb-staff-free\" dst-address-list=\"wb-site-" + site + "\" action=accept"), "staff may use " + site);
        }
        assertTrue(allowRules.contains("src-address-list=\"wb-staff-free\" action=drop comment=\"wb-school-filter: staff drop other\""));
        // Android's HTTPS probe and the plain probes are always reachable on allow lists, so phones don't say "limited"
        for (String probe : List.of("www.google.com", "connectivitycheck.gstatic.com", "captive.apple.com", "www.msftconnecttest.com")) {
            assertTrue(allowRules.contains("src-address-list=\"wb-staff-free\" dst-address-list=\"wb-site-" + probe + "\" action=accept"), "staff reach " + probe);
            assertTrue(allowRules.contains("src-address-list=\"wb-student-free\" dst-address-list=\"wb-site-" + probe + "\" action=accept"), "students reach " + probe);
        }
        // More specific names are learned first, so auth.openai-style sub-sites aren't swallowed by a parent entry
        assertTrue(allowRules.indexOf("/ip/dns/static/add name=\"www.msftconnecttest.com\"") < allowRules.indexOf("/ip/dns/static/add name=\"chatgpt.com\""));
        // Rules sit above the router's own forward rules, then apply at once
        String move = ":do { /ip/firewall/filter/move [find where comment~\"^wb-school-filter\"] destination=[:pick [find where chain=forward dynamic=no !(comment~\"^wb-\")] 0] } on-error={}";
        assertTrue(allowRules.contains(move), "school rules are placed above the router's own forward rules");
        assertTrue(allowRules.indexOf(move) < allowRules.indexOf("/ip/dns/cache/flush"), "rules are in place before clients reconnect");
        assertTrue(allowRules.contains("list=\"wb-staff-free\"] do={ :local ip [/ip/firewall/address-list/get $e address]; /ip/firewall/connection/remove"));
        assertTrue(RouterScriptGenerator.tetheringRules("BLOCK").contains("move [find where comment~\"^wb-tether\"]"), "tethering rules are placed on top too");
        assertFalse(RouterScriptGenerator.tetheringRules("OFF").contains("/move"), "nothing to move when tethering is off");

        // Staff block list drops only the selected services, by name and by learned address
        String blockRules = generator.schoolFilterRules(false, "", "BLOCK_LIST", "youtube.com, @tiktok", "https://app.qticket.dev");
        assertTrue(blockRules.contains("src-address-list=\"wb-staff-free\" protocol=tcp dst-port=443 tls-host=\"*tiktok.com\" action=drop"));
        assertTrue(blockRules.contains("src-address-list=\"wb-staff-free\" dst-address-list=\"wb-site-googlevideo.com\" action=drop"), "typing youtube.com also blocks its videos");
        assertTrue(blockRules.contains("dst-address-list=\"wb-site-youtubei.googleapis.com\" action=drop"), "and the YouTube app");
        assertFalse(blockRules.contains("action=drop comment=\"wb-school-filter: staff drop other\""), "a block list never drops other traffic");
        assertFalse(blockRules.contains("wb-site-www.google.com"), "a block list adds no allowed sites");
        assertTrue(blockRules.contains("src-address-list=\"wb-staff-free\" protocol=udp dst-port=53 action=redirect to-ports=53"), "staff DNS goes through the router");
        assertTrue(blockRules.contains("src-address-list=\"wb-staff-free\" protocol=tcp dst-port=853 action=reject"), "private DNS cannot bypass the router");
        assertTrue(blockRules.contains(":do { /ip/dns/static/remove [find where comment~\"^wb-school-filter\"] } on-error={}"), "old DNS entries are cleared first");
        assertFalse(generator.schoolFilterRules(false, "", "ALL", "", "https://app.qticket.dev").contains("/ip/dns/static/add"), "no filtering means no DNS entries");
    }

}
