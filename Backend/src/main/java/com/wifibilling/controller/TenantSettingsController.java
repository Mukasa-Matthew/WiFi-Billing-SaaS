package com.wifibilling.controller;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.dto.TenantSettingsDto;
import com.wifibilling.model.Router;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.RouterManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

@Slf4j
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class TenantSettingsController {

    private final TenantRepository tenantRepository;
    private final RouterRepository routerRepository;
    private final RouterManagementService managementService;
    private final PlatformProperties props;

    @GetMapping
    @Transactional(readOnly = true)
    public TenantSettingsDto get(@AuthenticationPrincipal TenantPrincipal principal) {
        return toDto(load(principal.getTenantId()));
    }

    @PutMapping
    @Transactional
    public TenantSettingsDto update(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody TenantSettingsDto req) {
        Tenant tenant = load(principal.getTenantId());
        if (req.name() != null && !req.name().isBlank()) tenant.setName(req.name().trim());
        tenant.setSupportPhone(req.supportPhone());
        tenant.setSupportEmail(req.supportEmail());
        if (req.brandColor() != null) tenant.setBrandColor(req.brandColor());
        tenant.setRedirectUrl(req.redirectUrl());
        if (req.voucherPrefix() != null) tenant.setVoucherPrefix(req.voucherPrefix());
        if (req.voucherCodeLength() != null) tenant.setVoucherCodeLength(req.voucherCodeLength());
        if (req.voucherCodeFormat() != null) tenant.setVoucherCodeFormat(req.voucherCodeFormat());
        if (req.portalShowPackages() != null) tenant.setPortalShowPackages(req.portalShowPackages());
        tenant.setNotifyEmails(req.notifyEmails());
        tenant.setNotifyPhones(req.notifyPhones());
        if (req.portalTemplate() != null) tenant.setPortalTemplate(req.portalTemplate());
        if (req.tetheringProtection() != null) tenant.setTetheringProtection(req.tetheringProtection());
        Tenant saved = tenantRepository.save(tenant);

        // Push changes that live on the router to already-provisioned devices.
        for (Router router : routerRepository.findAllByTenantId(saved.getId())) {
            try {
                if (req.tetheringProtection() != null) {
                    managementService.enqueue(router, "SYNC_TETHERING");
                } else if (req.portalTemplate() != null) {
                    managementService.enqueue(router, "SYNC_PORTAL");
                }
            } catch (ResponseStatusException e) {
                log.debug("Skipping queued task for router {}: {}", router.getId(), e.getMessage());
            }
        }
        return toDto(saved);
    }

    /**
     * Authenticated preview of the tenant's captive portal: renders the selected
     * template with tenant branding plus dummy MikroTik variables.
     */
    @GetMapping(value = "/portal-preview", produces = "text/html;charset=UTF-8")
    @Transactional(readOnly = true)
    public ResponseEntity<String> preview(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(value = "template", required = false) String template) {
        Tenant tenant = load(principal.getTenantId());
        String tpl = template != null && template.matches("[a-z0-9-]{1,40}") ? template
                : tenant.getPortalTemplate();
        Resource resource = new ClassPathResource("static/hotspot/" + tpl + "/login.html");
        if (!resource.exists()) {
            resource = new ClassPathResource("static/hotspot/login.html");
        }
        try {
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            content = content
                    .replace("ROUTER_TOKEN_PLACEHOLDER", "preview")
                    .replace("SERVER_BASE_URL_PLACEHOLDER", props.getPublicBaseUrl())
                    .replace("TENANT_NAME_PLACEHOLDER", escape(tenant.getName()))
                    .replace("TENANT_BRAND_COLOR_PLACEHOLDER",
                            tenant.getBrandColor() != null && tenant.getBrandColor().matches("#[0-9A-Fa-f]{6}")
                                    ? tenant.getBrandColor() : "#3B82F6")
                    .replace("TENANT_SUPPORT_PHONE_PLACEHOLDER",
                            tenant.getSupportPhone() != null ? escape(tenant.getSupportPhone()) : "")
                    .replace("TENANT_SUPPORT_EMAIL_PLACEHOLDER",
                            tenant.getSupportEmail() != null ? escape(tenant.getSupportEmail()) : "")
                    .replace("POST_LOGIN_REDIRECT_PLACEHOLDER",
                            tenant.getRedirectUrl() != null && !tenant.getRedirectUrl().isBlank()
                                    ? escape(tenant.getRedirectUrl()) : "#")
                    .replace("$(link-login-only)", "#")
                    .replace("$(link-orig)", "#")
                    .replace("$(username)", "")
                    .replace("$(ip)", "10.10.0.10")
                    .replace("$(mac)", "AA:BB:CC:DD:EE:FF")
                    .replace("$(error)", "");
            // Neutralize MikroTik conditional blocks in preview
            content = content.replace("$(if ", "$(if_")
                    .replace("$(endif)", "$(endif_)")
                    .replace("<!-- $(if error) -->", "")
                    .replace("<!-- $(endif) -->", "");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(content);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Preview failed");
        }
    }

    private Tenant load(Long tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private TenantSettingsDto toDto(Tenant t) {
        return new TenantSettingsDto(
                t.getName(), t.getEmail(), t.getSupportPhone(), t.getSupportEmail(),
                t.getBrandColor() != null ? t.getBrandColor() : "#3B82F6",
                t.getRedirectUrl(), t.getVoucherPrefix(),
                t.getVoucherCodeLength() != null ? t.getVoucherCodeLength() : 8,
                t.getVoucherCodeFormat() != null ? t.getVoucherCodeFormat() : "ALPHANUMERIC",
                Boolean.TRUE.equals(t.getPortalShowPackages()),
                t.getNotifyEmails(), t.getNotifyPhones(),
                t.getPortalTemplate() != null ? t.getPortalTemplate() : "midnight",
                t.getTetheringProtection() != null ? t.getTetheringProtection() : "OFF");
    }
}
