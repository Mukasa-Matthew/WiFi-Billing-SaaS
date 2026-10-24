package com.wifibilling.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterScriptGeneratorTest {

    private final RouterScriptGenerator generator = new RouterScriptGenerator();

    private String render(String secret) {
        return generator.generate(new RouterScriptGenerator.ScriptContext(
                "Cafe \"Main\"", "ether1", List.of("ether2", "ether3", "wlan1"), "Cafe WiFi",
                "tok_ABC123xyz", "10.9.0.5", 16,
                secret, "10.9.0.1",
                "yoay+E/LyCqRq0xxqsIRJdRoqe8XjRVmkMe3nDx5wEc=", "203.0.113.10", 443, "10.9.0.0/16",
                "http://203.0.113.10:8080", Set.of("203.0.113.10"), Set.of("momo.mtn.com"), "BLOCK"));
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
        assertTrue(s.contains("http://203.0.113.10:8080/api/router/provision/tok_ABC123xyz/register"));
        assertTrue(s.contains(":local wbPub [/interface/wireguard get [find name=\"wg-billing\"] public-key]"));
        assertTrue(s.contains("http-data=(\"{\\\"publicKey\\\":\\\"\" . $wbPub . \"\\\"}\")"));
        // tethering BLOCK mode emits drop rules
        assertTrue(s.contains("/ip/firewall/filter/add chain=forward ttl=equal:62 action=drop"));
        assertTrue(s.contains("/ip/firewall/filter/add chain=forward ttl=equal:126 action=drop"));
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
                "10.9.0.0/16", "http://203.0.113.10:8080", Set.of(), Set.of(), "OFF"));
        assertFalse(s.contains("\n/system reset-configuration"));
    }

    @Test
    void newlineInQuotedValueCannotInjectCommands() {
        assertFalse(RouterScriptGenerator.q("Cafe\n/system reset-configuration")
                .contains("\n/system reset-configuration"));
    }
}
