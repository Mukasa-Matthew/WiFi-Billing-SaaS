package com.wifibilling.controller;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.TenantGatewayConfig;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.EncryptionService;
import com.wifibilling.service.GatewayConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Tenant payment-gateway settings: mode selection (platform vs own MarzPay account),
 * credential management (write-only secrets, masked on read), test, and disconnect.
 * All endpoints are scoped to the authenticated tenant — no tenantId is ever taken
 * from the request.
 */
@RestController
@RequestMapping("/api/settings/gateway")
@RequiredArgsConstructor
public class TenantGatewayController {

    private final GatewayConfigService gateway;
    private final TenantRepository tenants;
    private final PlatformProperties props;

    public record GatewayView(
            String mode,
            String connectionStatus,
            String connectionError,
            String apiKeyMasked,
            boolean hasSecret,
            boolean hasWebhookSecret,
            String environment,
            String country,
            OffsetDateTime lastVerifiedAt,
            String callbackUrl,
            boolean platformAvailable,
            double platformFeePercent
    ) {}

    @GetMapping
    public GatewayView get(@AuthenticationPrincipal TenantPrincipal p) {
        var cfg = gateway.findConfig(p.getTenantId()).orElse(null);
        return new GatewayView(
                cfg != null ? cfg.getMode() : TenantGatewayConfig.MODE_PLATFORM,
                cfg != null ? cfg.getConnectionStatus() : TenantGatewayConfig.CONN_UNCONFIGURED,
                cfg != null ? cfg.getConnectionError() : null,
                cfg != null && cfg.getApiKey() != null ? EncryptionService.mask(cfg.getApiKey()) : null,
                cfg != null && cfg.getApiSecretEnc() != null,
                cfg != null && cfg.getWebhookSecretEnc() != null,
                cfg != null ? cfg.getEnvironment() : "live",
                cfg != null ? cfg.getCountry() : "UG",
                cfg != null ? cfg.getLastVerifiedAt() : null,
                props.getPublicBaseUrl() + "/api/payments/marzpay/callback",
                gateway.platformConfigured(),
                gateway.platformFeePercent()
        );
    }

    public record ModeRequest(String mode, boolean confirm) {}

    /** Switch modes. Requires explicit confirm=true; history and pending work are untouched. */
    @PostMapping("/mode")
    public GatewayView setMode(@AuthenticationPrincipal TenantPrincipal p, @RequestBody ModeRequest req) {
        if (req == null || !req.confirm()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Mode changes require explicit confirmation");
        }
        gateway.setMode(load(p), req.mode());
        return get(p);
    }

    public record CredentialsRequest(String apiKey, String apiSecret,
                                     String webhookSecret, String environment, String country) {}

    @PostMapping("/credentials")
    public GatewayView saveCredentials(@AuthenticationPrincipal TenantPrincipal p,
                                       @RequestBody CredentialsRequest req) {
        gateway.saveOwnCredentials(load(p), req.apiKey(), req.apiSecret(),
                req.webhookSecret(), req.environment(), req.country());
        return get(p);
    }

    @PostMapping("/test")
    public GatewayView test(@AuthenticationPrincipal TenantPrincipal p) {
        gateway.testConnection(load(p));
        return get(p);
    }

    @PostMapping("/disconnect")
    public GatewayView disconnect(@AuthenticationPrincipal TenantPrincipal p) {
        gateway.disconnect(load(p));
        return get(p);
    }

    private Tenant load(TenantPrincipal p) {
        return tenants.findById(p.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
    }
}
