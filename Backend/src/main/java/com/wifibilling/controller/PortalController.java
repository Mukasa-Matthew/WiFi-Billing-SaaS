package com.wifibilling.controller;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.dto.PortalPackageResponse;
import com.wifibilling.dto.PortalConfigResponse;
import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Router;
import com.wifibilling.model.Transaction;
import com.wifibilling.model.Voucher;
import java.util.Map;
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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/portal")
@RequiredArgsConstructor
public class PortalController {

    private final RouterRepository routerRepository;
    private final PackagePlanRepository packagePlanRepository;
    private final TransactionRepository transactionRepository;
    private final com.wifibilling.repository.VoucherRepository voucherRepository;
    private final com.wifibilling.repository.PortalFeedbackRepository feedbackRepository;
    private final PlatformProperties props;

    private List<PortalPackageResponse> portalPackages(Router router) {
        if (!Boolean.TRUE.equals(router.getTenant().getPortalShowPackages())) return List.of();
        return packagePlanRepository.findAllByTenantIdOrderByPriceAsc(router.getTenant().getId()).stream()
                .filter(p -> "ACTIVE".equals(p.effectiveAvailability()))
                .filter(p -> p.getRouter() == null || p.getRouter().getId().equals(router.getId()))
                .sorted(java.util.Comparator.<PackagePlan, Boolean>comparing(p -> !Boolean.TRUE.equals(p.getFeatured()))
                        .thenComparing(PackagePlan::getPrice)
                        .thenComparing(p -> p.getDisplayOrder() == null ? 0 : p.getDisplayOrder()))
                .map(p -> new PortalPackageResponse(p.getId(), p.getName(), p.getPrice(), p.getRateLimit(),
                        p.getSessionTimeout(), Boolean.TRUE.equals(p.getFeatured())))
                .toList();
    }

    /** A tenant- and router-scoped snapshot. Client supplied tenant ids are never accepted. */
    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PortalConfigResponse> getConfig(
            @RequestParam("token") String token,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Router router = resolveRouter(token);
        var tenant = router.getTenant();
        var packages = portalPackages(router);
        String base = props.getPublicBaseUrl();
        String logoUrl = tenant.getPortalLogoFile() != null
                ? base + "/api/portal/assets/" + token + "/logo" : null;
        String bgUrl = tenant.getPortalBackgroundFile() != null
                ? base + "/api/portal/assets/" + token + "/background" : null;
        var settings = new PortalConfigResponse.PortalSettings(
                tenant.getName(), tenant.getSupportPhone(), tenant.getSupportPhone2(),
                tenant.getSupportEmail(), tenant.getWhatsappPhone(),
                tenant.getBrandColor() == null ? "#3B82F6" : tenant.getBrandColor(),
                tenant.getRedirectUrl(), tenant.getPortalTemplate() == null ? "midnight" : tenant.getPortalTemplate(),
                Boolean.TRUE.equals(tenant.getPortalShowPackages()),
                tenant.getPortalFont() == null ? "Poppins" : tenant.getPortalFont(),
                tenant.getPortalLanguage() == null ? "English" : tenant.getPortalLanguage(),
                tenant.getPortalPrimaryColor(), tenant.getPortalSecondaryColor(),
                tenant.getPortalLogoSize(), logoUrl, bgUrl,
                Boolean.TRUE.equals(tenant.getPortalShowDeviceLimits()),
                Boolean.TRUE.equals(tenant.getPortalShowDeviceMac()),
                !Boolean.FALSE.equals(tenant.getPortalShowPackageTime()),
                !Boolean.FALSE.equals(tenant.getPortalAllowPurchases()),
                !Boolean.FALSE.equals(tenant.getPortalBuyForOther()),
                !Boolean.FALSE.equals(tenant.getPortalShowFeedback()));
        String fingerprint = settings.toString() + "|" + packages.toString();
        String version;
        try {
            version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(fingerprint.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to version portal configuration", e);
        }
        String etag = "\"" + version + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(version).cacheControl(CacheControl.noCache()).build();
        }
        return ResponseEntity.ok().eTag(version).cacheControl(CacheControl.noCache())
                .body(new PortalConfigResponse(version, settings, packages));
    }
    private String packagesJson(Router router) {
        StringBuilder b = new StringBuilder("[");
        var plans = packagePlanRepository.findAllByTenantIdOrderByPriceAsc(router.getTenant().getId());
        boolean first = true;
        for (var p : plans) {
            if (!"ACTIVE".equals(p.effectiveAvailability())) continue;
            if (p.getRouter() != null && !p.getRouter().getId().equals(router.getId())) continue;
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
        return portalPackages(resolveRouter(token));
    }

    /**
     * "Already bought? Find your voucher" — looks up vouchers for the given phone number.
     * Returns the newest active/valid voucher for immediate login, along with the full history
     * of active and past used vouchers with package details and timestamps.
     */
    @GetMapping(value = "/find-voucher", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<java.util.Map<String, Object>> findVoucher(
            @RequestParam("token") String token,
            @RequestParam("phone") String phone) {
        Router router = resolveRouter(token);
        String raw = phone == null ? "" : phone.replaceAll("[^0-9+]", "");
        if (raw.length() < 7) {
            return ResponseEntity.badRequest().body(java.util.Map.of("voucherCode", "", "vouchers", List.of()));
        }

        // Generate phone variants: local (07...), international (2567...), and E.164 (+2567...)
        java.util.Set<String> phoneVariants = new java.util.LinkedHashSet<>();
        phoneVariants.add(raw);
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("256") && digits.length() >= 12) {
            phoneVariants.add("+" + digits);
            phoneVariants.add(digits);
            phoneVariants.add("0" + digits.substring(3));
        } else if (digits.startsWith("0") && digits.length() >= 10) {
            phoneVariants.add(digits);
            phoneVariants.add("256" + digits.substring(1));
            phoneVariants.add("+256" + digits.substring(1));
        } else if (digits.length() == 9 && digits.startsWith("7")) {
            phoneVariants.add("0" + digits);
            phoneVariants.add("256" + digits);
            phoneVariants.add("+256" + digits);
        }

        List<Transaction> txList = transactionRepository.findAllByTenantIdAndPhoneInAndStatusOrderByCreatedAtDesc(
                router.getTenant().getId(), phoneVariants, "SUCCESSFUL");

        // Map voucher codes from transactions
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        for (Transaction tx : txList) {
            if (tx.getVoucherCode() != null && !tx.getVoucherCode().isBlank()) {
                codes.add(tx.getVoucherCode().trim().toUpperCase());
            }
        }

        Map<String, Voucher> voucherMap = codes.isEmpty() ? Map.of()
                : voucherRepository.findAllByCodeIn(codes).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                v -> v.getCode().toUpperCase(),
                                v -> v,
                                (a, b) -> a));

        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        List<java.util.Map<String, Object>> voucherItems = new java.util.ArrayList<>();
        String primaryActiveCode = "";

        for (Transaction tx : txList) {
            String code = tx.getVoucherCode();
            if (code == null || code.isBlank()) continue;
            String normalizedCode = code.trim().toUpperCase();
            Voucher v = voucherMap.get(normalizedCode);

            String status = v != null ? v.getStatus() : "unused";
            java.time.OffsetDateTime expiresAt = v != null ? v.getExpiresAt() : null;
            java.time.OffsetDateTime firstLogin = v != null ? v.getFirstLogin() : null;
            java.time.OffsetDateTime createdAt = v != null && v.getCreatedAt() != null ? v.getCreatedAt() : tx.getCreatedAt();

            boolean isExpired = "expired".equalsIgnoreCase(status)
                    || "revoked".equalsIgnoreCase(status)
                    || (expiresAt != null && expiresAt.isBefore(now));
            boolean isActive = !isExpired && ("active".equalsIgnoreCase(status) || "unused".equalsIgnoreCase(status));

            PackagePlan plan = tx.getPackagePlan() != null ? tx.getPackagePlan()
                    : (v != null ? v.getPackagePlan() : null);
            String packageName = plan != null ? plan.getName() : "WiFi Package";
            Integer sessionTimeout = plan != null ? plan.getSessionTimeout() : null;
            java.math.BigDecimal price = tx.getAmount() != null ? tx.getAmount()
                    : (plan != null ? plan.getPrice() : java.math.BigDecimal.ZERO);

            if (isActive && primaryActiveCode.isEmpty()) {
                primaryActiveCode = normalizedCode;
            }

            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("code", normalizedCode);
            item.put("packageName", packageName);
            item.put("price", price);
            item.put("durationSeconds", sessionTimeout);
            item.put("status", isExpired ? "expired" : ("active".equalsIgnoreCase(status) ? "active" : "unused"));
            item.put("isActive", isActive);
            item.put("firstLogin", firstLogin != null ? firstLogin.toString() : null);
            item.put("expiresAt", expiresAt != null ? expiresAt.toString() : null);
            item.put("createdAt", createdAt != null ? createdAt.toString() : null);
            voucherItems.add(item);
        }

        // If no active voucher was found among all transactions, fallback to most recent voucher code
        if (primaryActiveCode.isEmpty() && !voucherItems.isEmpty()) {
            primaryActiveCode = (String) voucherItems.get(0).get("code");
        }

        java.util.Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("voucherCode", primaryActiveCode);
        response.put("vouchers", voucherItems);
        return ResponseEntity.ok(response);
    }

    /**
     * The hosted captive portal page. Router login/rlogin shells redirect here
     * carrying MikroTik context (mac, ip, link-login-only) as query params.
     * Rendered with the tenant's branding and selected theme palette.
     */
    @GetMapping(value = "/page", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> portalPage(@RequestParam("token") String token) {
        Router router = resolveRouter(token);
        // Server-rendered previews run in iframes where location.origin is "null"
        // and there are no query params — bake in the token and public base URL.
        String html = renderPortal(router.getTenant(), null)
                .replace("var TOKEN = Q.get('token') || '';",
                        "var TOKEN = '" + router.getToken() + "';")
                .replace("var BASE = location.origin;",
                        "var BASE = '" + props.getPublicBaseUrl() + "';");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(html);
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

        // Retained for backward compatibility and emergency diagnostics. Current
        // portals never authorize purchases from this potentially stale snapshot.
        if ("packages.json".equals(filename)) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(packagesJson(router));
        }

        // The router serves the lightweight shell. Runtime tenant configuration is
        // obtained from /config, so ordinary dashboard edits never require re-upload.
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
                        .replace("var MT_MAC = Q.get('mac') || '';",
                                "var MT_MAC = '$(mac)';")
                        .replace("var MT_IP = Q.get('ip') || '';",
                                "var MT_IP = '$(ip)';")
                        .replace("var BASE = location.origin;",
                                "var BASE = 'SERVER_BASE_URL_PLACEHOLDER';");
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

    /** Public endpoint — a hotspot client leaves feedback for the tenant. */
    @PostMapping("/feedback")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<java.util.Map<String, Object>> submitFeedback(
            @RequestParam("token") String token,
            @RequestBody java.util.Map<String, Object> body) {
        Router router = resolveRouter(token);
        String message = String.valueOf(body == null ? "" : body.getOrDefault("message", "")).trim();
        if (message.isEmpty() || message.length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Feedback message is required (max 1000 chars)");
        }
        Integer rating = null;
        Object r = body.get("rating");
        if (r instanceof Number n && n.intValue() >= 1 && n.intValue() <= 5) rating = n.intValue();
        feedbackRepository.save(com.wifibilling.model.PortalFeedback.builder()
                .tenant(router.getTenant())
                .router(router)
                .name(cut(body.get("name"), 120))
                .phone(cut(body.get("phone"), 32))
                .mac(cut(body.get("mac"), 20))
                .rating(rating)
                .message(message)
                .build());
        return ResponseEntity.ok(java.util.Map.of("ok", true));
    }

    private static String cut(Object v, int max) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s.substring(0, Math.min(s.length(), max));
    }

    private Router resolveRouter(String token) {
        if (!RouterTokens.isValid(token)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found");
        }
        return routerRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found"));
    }
}
