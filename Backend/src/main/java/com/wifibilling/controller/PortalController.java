package com.wifibilling.controller;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.dto.PortalPackageResponse;
import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Router;
import com.wifibilling.repository.PackagePlanRepository;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.util.RouterTokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/portal")
@RequiredArgsConstructor
public class PortalController {

    private final RouterRepository routerRepository;
    private final PackagePlanRepository packagePlanRepository;
    private final TransactionRepository transactionRepository;
    private final PlatformProperties props;
    private String packagesJson(Router router) {
        StringBuilder b = new StringBuilder("[");
        var plans = packagePlanRepository.findAllByTenantIdOrderByPriceAsc(router.getTenant().getId());
        boolean first = true;
        for (var p : plans) {
            if (!"ACTIVE".equals(p.effectiveAvailability())) continue;
            if (!Boolean.TRUE.equals(router.getTenant().getPortalShowPackages())) break;
            if (!first) b.append(',');
            first = false;
            b.append("{\"id\":").append(p.getId())
             .append(",\"name\":\"").append(escapeJson(p.getName())).append("\"")
             .append(",\"price\":").append(p.getPrice())
             .append(",\"rateLimit\":\"").append(escapeJson(p.getRateLimit())).append("\"")
             .append(",\"sessionTimeout\":").append(p.getSessionTimeout())
             .append(",\"featured\":").append(Boolean.TRUE.equals(p.getFeatured()))
             .append("}");
        }
        return b.append(']').toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Public endpoint invoked by captive portal client-side JS.
     * Instantly reflects package creations, edits, and deletions performed by the router's tenant.
     */
    @GetMapping(value = "/packages", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PortalPackageResponse> getPackages(@RequestParam("token") String token) {
        Router router = resolveRouter(token);
        if (!Boolean.TRUE.equals(router.getTenant().getPortalShowPackages())) {
            return List.of();
        }
        List<PackagePlan> plans = packagePlanRepository.findAllByTenantIdOrderByPriceAsc(router.getTenant().getId());

        return plans.stream()
                .filter(p -> "ACTIVE".equals(p.effectiveAvailability()))
                .sorted(java.util.Comparator.<PackagePlan, Boolean>comparing(p -> !Boolean.TRUE.equals(p.getFeatured()))
                        .thenComparing(PackagePlan::getPrice)
                        .thenComparing(p -> p.getDisplayOrder() == null ? 0 : p.getDisplayOrder()))
                .map(p -> new PortalPackageResponse(
                        p.getId(),
                        p.getName(),
                        p.getPrice(),
                        p.getRateLimit(),
                        p.getSessionTimeout(), Boolean.TRUE.equals(p.getFeatured())))
                .toList();
    }

    /**
     * "Already bought? Find your voucher" — looks up the most recent successful
     * purchase by phone number for this tenant and returns its voucher code.
     */
    @GetMapping(value = "/find-voucher", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<java.util.Map<String, Object>> findVoucher(
            @RequestParam("token") String token,
            @RequestParam("phone") String phone) {
        Router router = resolveRouter(token);
        String normalized = phone == null ? "" : phone.replaceAll("[^0-9+]", "");
        if (normalized.length() < 7) {
            return ResponseEntity.badRequest().body(java.util.Map.of("voucherCode", ""));
        }
        var tx = transactionRepository.findFirstByTenantIdAndPhoneAndStatusOrderByCreatedAtDesc(
                router.getTenant().getId(), normalized, "SUCCESSFUL");
        if (tx.isEmpty() || tx.get().getVoucherCode() == null) {
            return ResponseEntity.ok(java.util.Map.of("voucherCode", ""));
        }
        return ResponseEntity.ok(java.util.Map.of("voucherCode", tx.get().getVoucherCode()));
    }

    /**
     * The hosted captive portal page. Router login/rlogin shells redirect here
     * carrying MikroTik context (mac, ip, link-login-only) as query params.
     * Rendered with the tenant's branding and selected theme palette.
     */
    @GetMapping(value = "/page", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> portalPage(@RequestParam("token") String token) {
        Router router = resolveRouter(token);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(renderPortal(router.getTenant(), null));
    }

    static String renderPortal(com.wifibilling.model.Tenant tenant, String themeOverride) {
        String tpl = themeOverride != null ? themeOverride : tenant.getPortalTemplate();
        String theme = tpl != null && tpl.matches("[a-z0-9-]{1,40}") ? tpl : "midnight";
        String redirect = tenant.getRedirectUrl() != null && !tenant.getRedirectUrl().isBlank()
                ? escapeHtml(tenant.getRedirectUrl()) : "";
        Resource resource = new ClassPathResource("static/hotspot/portal.html");
        try {
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            return content
                    .replace("PORTAL_THEME_PLACEHOLDER", "theme-" + theme)
                    .replace("TENANT_NAME_PLACEHOLDER", escapeHtml(tenant.getName()))
                    .replace("TENANT_BRAND_COLOR_PLACEHOLDER",
                            tenant.getBrandColor() != null && tenant.getBrandColor().matches("#[0-9A-Fa-f]{6}")
                                    ? tenant.getBrandColor() : "#3B82F6")
                    .replace("TENANT_SUPPORT_PHONE_PLACEHOLDER",
                            tenant.getSupportPhone() != null ? escapeHtml(tenant.getSupportPhone()) : "")
                    .replace("TENANT_SUPPORT_EMAIL_PLACEHOLDER",
                            tenant.getSupportEmail() != null ? escapeHtml(tenant.getSupportEmail()) : "")
                    .replace("POST_LOGIN_REDIRECT_PLACEHOLDER", redirect);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Portal unavailable");
        }
    }

    /**
     * Serves hotspot templates from static/hotspot/{filename}.
     * When serving login.html, substitutes ROUTER_TOKEN_PLACEHOLDER and SERVER_BASE_URL_PLACEHOLDER
     * with the router's actual token and configured public base URL.
     */
    @GetMapping(value = "/templates/{filename}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> getTemplate(
            @PathVariable String filename,
            @RequestParam("token") String token) {

        // Validate token
        Router router = resolveRouter(token);

        // Sanitize filename to prevent directory traversal
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
        }

        // The router caches the tenant's packages as a static file so unauthenticated
        // clients get plans instantly from flash — no WAN or walled-garden dependency.
        if ("packages.json".equals(filename)) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(packagesJson(router));
        }

        // The router serves the full portal page itself as login.html so clients see
        // the branded plans instantly; only the JSON calls cross the WAN. The page is
        // the same portal.html used by /api/portal/page, with its runtime config swapped
        // from query-string parsing to RouterOS $(variables) + baked-in endpoints.
        boolean isLogin = "login.html".equals(filename);
        Resource resource = new ClassPathResource("static/hotspot/" + (isLogin ? "portal.html" : filename));
        if (!resource.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Template file not found: " + filename);
        }

        try {
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);

            var tenant = router.getTenant();
            String tpl = tenant.getPortalTemplate();
            String theme = tpl != null && tpl.matches("[a-z0-9-]{1,40}") ? tpl : "midnight";

            if (isLogin) {
                content = content
                        .replace("var TOKEN = Q.get('token') || '';",
                                "var TOKEN = 'ROUTER_TOKEN_PLACEHOLDER';")
                        .replace("var LINK_LOGIN = Q.get('link-login-only') || '';",
                                "var LINK_LOGIN = '$(link-login-only)';")
                        .replace("var LINK_ORIG = Q.get('link-orig') || '';",
                                "var LINK_ORIG = '$(link-orig)';")
                        .replace("var MT_ERROR = Q.get('error') || '';",
                                "var MT_ERROR = '$(error)';")
                        .replace("var BASE = location.origin;",
                                "var BASE = 'SERVER_BASE_URL_PLACEHOLDER';")
                        // Packages come from the router-cached JSON file (instant),
                        // not a live WAN request that can stall.
                        .replace("fetch(BASE + '/api/portal/packages?token=' + encodeURIComponent(TOKEN))",
                                "fetch('packages.json')");
            }

            content = content
                    .replace("PORTAL_THEME_PLACEHOLDER", "theme-" + theme)
                    .replace("ROUTER_TOKEN_PLACEHOLDER", router.getToken())
                    .replace("SERVER_BASE_URL_PLACEHOLDER", props.getPublicBaseUrl())
                    .replace("TENANT_NAME_PLACEHOLDER", escapeHtml(tenant.getName()))
                    .replace("TENANT_BRAND_COLOR_PLACEHOLDER",
                            tenant.getBrandColor() != null && tenant.getBrandColor().matches("#[0-9A-Fa-f]{6}")
                                    ? tenant.getBrandColor() : "#3B82F6")
                    .replace("TENANT_SUPPORT_PHONE_PLACEHOLDER",
                            tenant.getSupportPhone() != null ? escapeHtml(tenant.getSupportPhone()) : "")
                    .replace("TENANT_SUPPORT_EMAIL_PLACEHOLDER",
                            tenant.getSupportEmail() != null ? escapeHtml(tenant.getSupportEmail()) : "")
                    .replace("POST_LOGIN_REDIRECT_PLACEHOLDER",
                            tenant.getRedirectUrl() != null && !tenant.getRedirectUrl().isBlank()
                                    ? escapeHtml(tenant.getRedirectUrl()) : "$(link-orig)");

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.TEXT_HTML)
                    .body(content);
        } catch (IOException e) {
            log.error("Failed to read template {}: {}", filename, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error reading template");
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private Router resolveRouter(String token) {
        if (!RouterTokens.isValid(token)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found");
        }
        return routerRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found"));
    }
}
