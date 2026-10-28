package com.wifibilling.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.wifibilling.util.Ipv4Subnet;

@Getter @Component
public class WinboxProperties {
    @Value("${winbox.enabled:false}") private boolean enabled;
    @Value("${winbox.public-host:}") private String publicHost;
    @Value("${winbox.hub-ip:}") private String hubIp;
    @Value("${winbox.gateway-token:}") private String gatewayToken;
    @Value("${app.encryption-key:}") private String encryptionKey;

    public boolean configured() {
        if (!enabled || publicHost == null || !publicHost.matches("(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")
                || hubIp == null || gatewayToken == null || gatewayToken.length() < 32 || encryptionKey == null) return false;
        try {
            return java.util.Base64.getDecoder().decode(encryptionKey).length == 32
                    && Ipv4Subnet.isIpv4(hubIp);
        } catch (RuntimeException e) { return false; }
    }
}
