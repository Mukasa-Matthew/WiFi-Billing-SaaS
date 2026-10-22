package com.wifibilling.controller;

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
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;


@Slf4j
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class TenantSettingsController {

    private final TenantRepository tenantRepository;
    private final RouterRepository routerRepository;
    private final RouterManagementService managementService;

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
     * Authenticated preview of the tenant's hosted captive portal — renders the
     * exact page customers see, with the selected theme and tenant branding.
     * When the tenant has a router, its token is injected so packages load too.
     */
    @GetMapping(value = "/portal-preview", produces = "text/html;charset=UTF-8")
    @Transactional(readOnly = true)
    public ResponseEntity<String> preview(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(value = "template", required = false) String template) {
        Tenant tenant = load(principal.getTenantId());
        String tpl = template != null && template.matches("[a-z0-9-]{1,40}") ? template : null;
        String html = PortalController.renderPortal(tenant, tpl);
        // Give the page a real router token when available so package listing works.
        String token = routerRepository.findAllByTenantId(tenant.getId()).stream()
                .findFirst().map(Router::getToken).orElse("preview");
        html = html.replace("</body>",
                "<script>var Q2=new URLSearchParams(location.search);if(!Q2.get('token')){history.replaceState(null,'',location.pathname+'?token=" + token + "');}</script></body>");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(html);
    }

    private Tenant load(Long tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
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
