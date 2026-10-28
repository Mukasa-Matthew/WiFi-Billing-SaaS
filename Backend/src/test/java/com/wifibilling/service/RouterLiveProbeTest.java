package com.wifibilling.service;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.model.Router;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Parsing of the router's REST answers, and that nothing sensitive is ever echoed back. */
class RouterLiveProbeTest {

    PlatformProperties props = mock(PlatformProperties.class);
    RouterLiveProbe probe = new RouterLiveProbe(props, JsonMapper.builder().build());
    static final String SEP = "\u0001";

    @Test
    void parsesResourceHotspotUsersAndHandshake() {
        String out = "{\"uptime\":\"3d4h\",\"cpu-load\":\"7\",\"version\":\"7.16\"}" + SEP
                + "[{\"user\":\"ABC123\",\"address\":\"10.5.50.9\",\"mac-address\":\"AA:BB:CC:DD:EE:FF\",\"uptime\":\"1h2m3s\",\"bytes-in\":\"1024\",\"bytes-out\":\"2048\",\"login-by\":\"http-pap\",\"radius\":\"true\"}]" + SEP
                + "[{\"last-handshake\":\"45s\"}]";

        var r = probe.parse(out);

        assertThat(r.reachable()).isTrue();
        assertThat(r.hotspotActive()).isEqualTo(1);
        assertThat(r.wgHandshakeSecondsAgo()).isEqualTo(45L);
        var h = r.hotspotHosts().get(0);
        assertThat(h).containsEntry("user", "ABC123").containsEntry("address", "10.5.50.9")
                .containsEntry("macAddress", "AA:BB:CC:DD:EE:FF").containsEntry("uptimeSeconds", 3723L)
                .containsEntry("bytesIn", 1024L).containsEntry("bytesOut", 2048L).containsEntry("loginBy", "http-pap");
        // Only display fields are kept from the router's record.
        assertThat(h.keySet()).containsExactly("user", "address", "macAddress", "uptimeSeconds", "bytesIn", "bytesOut", "loginBy");
    }

    @Test
    void unreachableOutputNeverEchoesTheCommandOrCredentials() {
        String out = "curl: (7) Failed to connect; command was curl -u 'wb-api:RADIUS_SECRET_MARKER' http://10.9.0.4" + SEP + "" + SEP + "";
        var r = probe.parse(out);
        assertThat(r.reachable()).isFalse();
        assertThat(r.error()).isEqualTo("router REST unreachable or unauthorised");
        assertThat(r.toString()).doesNotContain("RADIUS_SECRET_MARKER").doesNotContain("wb-api");
    }

    @Test
    void missingFieldsAndAnEmptyHotspotAreHandled() {
        var r = probe.parse("{\"uptime\":\"1m\"}" + SEP + "[{}]" + SEP + "[]");
        assertThat(r.reachable()).isTrue();
        assertThat(r.hotspotHosts().get(0)).containsEntry("user", null).containsEntry("uptimeSeconds", null);
        assertThat(r.wgHandshakeSecondsAgo()).isNull();
    }

    @Test
    void routerWithoutAManagementChannelIsUnavailableWithoutRunningAnything() {
        when(props.getPeerManagementSshTarget()).thenReturn("");
        Router router = Router.builder().id(5L).wgIp("10.9.0.5").radiusSecret("RADIUS_SECRET_MARKER").build();
        var r = probe.probe(router);
        assertThat(r.reachable()).isFalse();
        assertThat(r.error()).isEqualTo("management channel not configured");
    }

    @Test
    void missingHotspotResponseIsUnavailableNotAnEmptyList() {
        var result = probe.parse("{\"uptime\":\"1m\"}");
        assertThat(result.reachable()).isTrue();
        assertThat(result.hotspotActive()).isNull();
        assertThat(result.hotspotHosts()).isNull();
    }

    @Test
    void malformedTunnelAddressNeverReachesTheShell() {
        when(props.getPeerManagementSshTarget()).thenReturn("hub@10.0.0.1");
        Router router = Router.builder().id(6L).wgIp("10.9.0.6;reboot").radiusSecret("x").build();
        var r = probe.probe(router);
        assertThat(r.reachable()).isFalse();
        assertThat(r.error()).isEqualTo("router tunnel address is invalid");
    }
}
