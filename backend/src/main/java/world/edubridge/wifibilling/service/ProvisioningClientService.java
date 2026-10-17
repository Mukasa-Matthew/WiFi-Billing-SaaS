package world.edubridge.wifibilling.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Configuration and contract metadata for the existing cloudserv provisioning service
 * (127.0.0.1:8085 on cloudserv, or 127.0.0.1:18085 via SSH tunnel during local development).
 * Actual HTTP call-outs for Link a MikroTik -> Generate Command will be wired in Milestone 3
 * on top of this contract configuration.
 */
@Service
public class ProvisioningClientService {

    private final String baseUrl;
    private final String adminApiKey;
    private final String publicEndpointHost;

    public ProvisioningClientService(
            @Value("${app.infrastructure.provisioning-base-url:http://127.0.0.1:18085}") String baseUrl,
            @Value("${app.infrastructure.provisioning-admin-api-key:replace-with-cloudserv-provisioning-admin-api-key}") String adminApiKey,
            @Value("${app.infrastructure.public-endpoint-host:portal.edubridge.world}") String publicEndpointHost) {
        this.baseUrl = baseUrl;
        this.adminApiKey = adminApiKey;
        this.publicEndpointHost = publicEndpointHost;
    }

    public Map<String, Object> getContractStatus() {
        boolean configuredKey = adminApiKey != null
            && !adminApiKey.isBlank()
            && !"replace-with-cloudserv-provisioning-admin-api-key".equals(adminApiKey);
        return Map.of(
            "provisioningBaseUrl", baseUrl,
            "publicEndpointHost", publicEndpointHost,
            "adminApiKeyConfigured", configuredKey,
            "createTokenEndpoint", "/api/v1/admin/routers/create-token"
        );
    }
}
