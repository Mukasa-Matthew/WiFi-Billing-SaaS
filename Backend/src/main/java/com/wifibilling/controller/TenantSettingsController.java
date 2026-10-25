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
    private final PlatformProperties props;
    private final com.wifibilling.service.OtpService otpService;

    public record EmailChangeRequest(String newEmail, String otp) {}
    public record OtpResponse(boolean sent, String devCode) {}

    /**
     * Step 1 of changing the account email: a code is sent to the CURRENT
     * address. The account email is the security channel for withdrawal and
     * password-reset codes, so the change must be authorised from it.
     */
    @PostMapping("/email/otp")
    public OtpResponse requestEmailChangeOtp(@AuthenticationPrincipal TenantPrincipal principal,
                                             @RequestBody(required = false) EmailChangeRequest req) {
        Tenant tenant = load(principal.getTenantId());
        validateNewEmail(tenant, req != null ? req.newEmail() : null);
        String devCode = otpService.issue(tenant.getEmail(), com.wifibilling.model.EmailOtp.PURPOSE_EMAIL_CHANGE,
                tenant.getId(), tenant.getName(),
                "You asked to change the email on your AirPairy account. Enter this code to confirm it.");
        return new OtpResponse(true, devCode);
    }

    /** Step 2: verify the code sent to the current email, then switch address. */
    @PostMapping("/email")
    @Transactional
    public TenantSettingsDto changeEmail(@AuthenticationPrincipal TenantPrincipal principal,
                                         @RequestBody EmailChangeRequest req) {
        Tenant tenant = load(principal.getTenantId());
        String newEmail = validateNewEmail(tenant, req != null ? req.newEmail() : null);
        otpService.verify(tenant.getEmail(), com.wifibilling.model.EmailOtp.PURPOSE_EMAIL_CHANGE, req.otp());
        tenant.setEmail(newEmail);
        return toDto(tenantRepository.save(tenant));
    }

    private String validateNewEmail(Tenant tenant, String newEmail) {
        if (newEmail == null || newEmail.isBlank()
                || !newEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || newEmail.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid new email address");
        }
        String normalized = newEmail.trim().toLowerCase();
        if (normalized.equalsIgnoreCase(tenant.getEmail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That is already your account email");
        }
        if (tenantRepository.findByEmailIgnoreCase(normalized).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That email is already in use");
        }
        return normalized;
    }

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
        tenant.setSupportPhone2(req.supportPhone2());
        tenant.setWhatsappPhone(req.whatsappPhone());
        if (req.portalFont() != null) tenant.setPortalFont(req.portalFont());
        if (req.portalLanguage() != null) tenant.setPortalLanguage(req.portalLanguage());
        tenant.setPortalPrimaryColor(req.portalPrimaryColor());
        tenant.setPortalSecondaryColor(req.portalSecondaryColor());
        tenant.setPortalLogoSize(req.portalLogoSize());
        if (req.portalShowDeviceLimits() != null) tenant.setPortalShowDeviceLimits(req.portalShowDeviceLimits());
        if (req.portalShowDeviceMac() != null) tenant.setPortalShowDeviceMac(req.portalShowDeviceMac());
        if (req.portalShowPackageTime() != null) tenant.setPortalShowPackageTime(req.portalShowPackageTime());
        if (req.portalAllowPurchases() != null) tenant.setPortalAllowPurchases(req.portalAllowPurchases());
        if (req.portalBuyForOther() != null) tenant.setPortalBuyForOther(req.portalBuyForOther());
        if (req.portalShowFeedback() != null) tenant.setPortalShowFeedback(req.portalShowFeedback());
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
        // srcdoc iframes have no query string and location.origin is "null", so
        // bake both values in instead of a replaceState hack that throws.
        String token = routerRepository.findAllByTenantId(tenant.getId()).stream()
                .findFirst().map(Router::getToken).orElse("preview");
        html = html
                .replace("var TOKEN = Q.get('token') || '';", "var TOKEN = '" + token + "';")
                .replace("var BASE = location.origin;", "var BASE = '" + props.getPublicBaseUrl() + "';");
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
                t.getTetheringProtection() != null ? t.getTetheringProtection() : "OFF",
                t.getSupportPhone2(), t.getWhatsappPhone(),
                t.getPortalFont() != null ? t.getPortalFont() : "Poppins",
                t.getPortalLanguage() != null ? t.getPortalLanguage() : "English",
                t.getPortalPrimaryColor(), t.getPortalSecondaryColor(),
                t.getPortalLogoSize(),
                Boolean.TRUE.equals(t.getPortalShowDeviceLimits()),
                Boolean.TRUE.equals(t.getPortalShowDeviceMac()),
                !Boolean.FALSE.equals(t.getPortalShowPackageTime()),
                !Boolean.FALSE.equals(t.getPortalAllowPurchases()),
                !Boolean.FALSE.equals(t.getPortalBuyForOther()),
                !Boolean.FALSE.equals(t.getPortalShowFeedback()),
                t.getPortalLogoFile() != null,
                t.getPortalBackgroundFile() != null);
    }

    /** Upload a tenant branding image (logo or portal background). */
    @PostMapping(value = "/assets/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public TenantSettingsDto uploadAsset(@AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String kind,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        if (!kind.equals("logo") && !kind.equals("background")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind must be logo or background");
        }
        String type = file.getContentType() != null ? file.getContentType() : "";
        String ext = switch (type) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/svg+xml" -> kind.equals("logo") ? ".svg" : null;
            case "image/webp" -> ".webp";
            default -> null;
        };
        if (ext == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PNG, JPG" + (kind.equals("logo") ? ", SVG" : "") + " or WebP images are allowed");
        long max = kind.equals("background") ? 500_000 : 1_000_000;
        if (file.getSize() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File too large (max " + max / 1000 + " KB)");
        Tenant tenant = load(principal.getTenantId());
        java.nio.file.Path dir = java.nio.file.Path.of("uploads", "tenants", String.valueOf(tenant.getId()));
        java.nio.file.Files.createDirectories(dir);
        String filename = kind + ext;
        java.nio.file.Files.copy(file.getInputStream(), dir.resolve(filename),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if (kind.equals("logo")) tenant.setPortalLogoFile(filename); else tenant.setPortalBackgroundFile(filename);
        return toDto(tenantRepository.save(tenant));
    }

    @DeleteMapping("/assets/{kind}")
    @Transactional
    public TenantSettingsDto deleteAsset(@AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String kind) {
        Tenant tenant = load(principal.getTenantId());
        String file = kind.equals("logo") ? tenant.getPortalLogoFile() : kind.equals("background") ? tenant.getPortalBackgroundFile() : null;
        if (file == null) return toDto(tenant);
        try { java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("uploads", "tenants", String.valueOf(tenant.getId()), file)); }
        catch (java.io.IOException ignored) {}
        if (kind.equals("logo")) tenant.setPortalLogoFile(null); else tenant.setPortalBackgroundFile(null);
        return toDto(tenantRepository.save(tenant));
    }
}
