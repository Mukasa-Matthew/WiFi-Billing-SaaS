package com.wifibilling.service;

import com.wifibilling.model.Tenant;
import com.wifibilling.model.TenantGatewayConfig;
import com.wifibilling.repository.TenantGatewayConfigRepository;
import com.wifibilling.service.payment.MarzPayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Resolves which MarzPay credential set processes a tenant's payments:
 * PLATFORM mode → platform's own key pair (env config), OWN mode → the tenant's
 * encrypted credentials. Also owns credential lifecycle (save/test/rotate/disconnect).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayConfigService {

    private final TenantGatewayConfigRepository configRepository;
    private final EncryptionService encryption;
    private final MarzPayClient marzPay;

    @Value("${marzpay.platform.api-key:}")
    private String platformApiKey;
    @Value("${marzpay.platform.api-secret:}")
    private String platformApiSecret;
    @Value("${marzpay.platform.webhook-secret:}")
    private String platformWebhookSecret;
    @Value("${marzpay.platform.country:UG}")
    private String platformCountry;
    @Value("${marzpay.platform.fee-percent:3.0}")
    private double platformFeePercent;

    public Optional<TenantGatewayConfig> findConfig(Long tenantId) {
        return configRepository.findByTenantId(tenantId);
    }

    /** Active mode for a tenant (default PLATFORM when unconfigured). */
    public String modeFor(Tenant tenant) {
        return configRepository.findByTenantId(tenant.getId())
                .map(TenantGatewayConfig::getMode)
                .orElse(TenantGatewayConfig.MODE_PLATFORM);
    }

    /** Credentials for a payment — frozen per transaction by callers at initiation. */
    public MarzPayClient.Credentials credentialsFor(Tenant tenant, String mode) {
        if (TenantGatewayConfig.MODE_OWN.equals(mode)) {
            TenantGatewayConfig cfg = configRepository.findByTenantId(tenant.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "Own gateway selected but not configured"));
            if (cfg.getApiKey() == null || cfg.getApiSecretEnc() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Own gateway credentials are incomplete");
            }
            return new MarzPayClient.Credentials(cfg.getApiKey(), encryption.decrypt(cfg.getApiSecretEnc()));
        }
        if (platformApiKey == null || platformApiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Platform payment gateway is not configured");
        }
        return new MarzPayClient.Credentials(platformApiKey, platformApiSecret);
    }

    /** Webhook signing secret for a tenant's mode (null when not configured — signature optional). */
    public String webhookSecretFor(Tenant tenant, String mode) {
        if (TenantGatewayConfig.MODE_OWN.equals(mode)) {
            return configRepository.findByTenantId(tenant.getId())
                    .map(TenantGatewayConfig::getWebhookSecretEnc)
                    .map(encryption::decrypt).orElse(null);
        }
        return platformWebhookSecret == null || platformWebhookSecret.isBlank() ? null : platformWebhookSecret;
    }

    public String countryFor(Tenant tenant, String mode) {
        if (TenantGatewayConfig.MODE_OWN.equals(mode)) {
            return configRepository.findByTenantId(tenant.getId())
                    .map(TenantGatewayConfig::getCountry).orElse("UG");
        }
        return platformCountry;
    }

    public double platformFeePercent() { return platformFeePercent; }
    public boolean platformConfigured() { return platformApiKey != null && !platformApiKey.isBlank(); }

    @Transactional
    public TenantGatewayConfig setMode(Tenant tenant, String mode) {
        if (!TenantGatewayConfig.MODE_PLATFORM.equals(mode) && !TenantGatewayConfig.MODE_OWN.equals(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid gateway mode");
        }
        if (TenantGatewayConfig.MODE_OWN.equals(mode)) {
            TenantGatewayConfig cfg = configRepository.findByTenantId(tenant.getId()).orElse(null);
            if (cfg == null || cfg.getApiKey() == null || cfg.getApiSecretEnc() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Save and verify your MarzPay credentials before switching to your own gateway");
            }
        }
        TenantGatewayConfig cfg = configRepository.findByTenantId(tenant.getId())
                .orElseGet(() -> TenantGatewayConfig.builder().tenant(tenant).build());
        cfg.setMode(mode);
        return configRepository.save(cfg);
    }

    /** Save (or rotate) own-gateway credentials. Values are masked on read; secrets are write-only. */
    @Transactional
    public TenantGatewayConfig saveOwnCredentials(Tenant tenant, String apiKey, String apiSecret,
                                                  String webhookSecret, String environment, String country) {
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API key and secret are required");
        }
        TenantGatewayConfig cfg = configRepository.findByTenantId(tenant.getId())
                .orElseGet(() -> TenantGatewayConfig.builder().tenant(tenant).build());
        cfg.setApiKey(apiKey.trim());
        cfg.setApiSecretEnc(encryption.encrypt(apiSecret.trim()));
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            cfg.setWebhookSecretEnc(encryption.encrypt(webhookSecret.trim()));
        }
        if (environment != null && !environment.isBlank()) cfg.setEnvironment(environment.trim());
        if (country != null && !country.isBlank()) cfg.setCountry(country.trim().toUpperCase());
        cfg.setConnectionStatus(TenantGatewayConfig.CONN_UNCONFIGURED);
        return configRepository.save(cfg);
    }

    /** Live credential check against MarzPay (balance endpoint — read-only, no money movement). */
    @Transactional
    public TenantGatewayConfig testConnection(Tenant tenant) {
        TenantGatewayConfig cfg = configRepository.findByTenantId(tenant.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gateway not configured"));
        if (cfg.getApiKey() == null || cfg.getApiSecretEnc() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Save credentials before testing");
        }
        boolean ok = marzPay.testConnection(
                new MarzPayClient.Credentials(cfg.getApiKey(), encryption.decrypt(cfg.getApiSecretEnc())),
                cfg.getCountry());
        cfg.setConnectionStatus(ok ? TenantGatewayConfig.CONN_CONNECTED : TenantGatewayConfig.CONN_FAILED);
        cfg.setConnectionError(ok ? null : "MarzPay rejected the credentials or they cannot read balances");
        cfg.setLastVerifiedAt(OffsetDateTime.now());
        return configRepository.save(cfg);
    }

    /** Removes own credentials and returns the tenant to platform mode. */
    @Transactional
    public TenantGatewayConfig disconnect(Tenant tenant) {
        TenantGatewayConfig cfg = configRepository.findByTenantId(tenant.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gateway not configured"));
        cfg.setApiKey(null);
        cfg.setApiSecretEnc(null);
        cfg.setWebhookSecretEnc(null);
        cfg.setMode(TenantGatewayConfig.MODE_PLATFORM);
        cfg.setConnectionStatus(TenantGatewayConfig.CONN_UNCONFIGURED);
        cfg.setConnectionError(null);
        return configRepository.save(cfg);
    }
}
