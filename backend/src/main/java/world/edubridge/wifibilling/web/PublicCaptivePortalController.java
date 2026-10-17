package world.edubridge.wifibilling.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import world.edubridge.wifibilling.domain.PackagePlan;
import world.edubridge.wifibilling.domain.Tenant;
import world.edubridge.wifibilling.repository.PackagePlanRepository;
import world.edubridge.wifibilling.repository.TenantRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Serves dynamic, database-driven Captive Portal configuration and eligible packages
 * for the centrally hosted customer Hotspot portal (/portal/hotspot).
 *
 * Accepts the MikroTik redirect query parameters documented in mikrotik-integration.md
 * (tenantSlug / tenantId / routerCode / mac / ip / linkLoginOnly / chapId / chapChallenge).
 */
@RestController
@RequestMapping("/api/v1/public/portal")
public class PublicCaptivePortalController {

    private final TenantRepository tenantRepository;
    private final PackagePlanRepository packagePlanRepository;
    private final JdbcTemplate jdbcTemplate;

    public PublicCaptivePortalController(TenantRepository tenantRepository,
                                         PackagePlanRepository packagePlanRepository,
                                         JdbcTemplate jdbcTemplate) {
        this.tenantRepository = tenantRepository;
        this.packagePlanRepository = packagePlanRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/config")
    public Map<String, Object> getCaptivePortalConfig(
            @RequestParam(required = false) String tenantSlug,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String routerCode) {

        Tenant tenant = resolveTenant(tenantSlug, tenantId, routerCode);

        List<PackagePlan> eligiblePackages = packagePlanRepository
            .findByTenantIdAndActiveTrueAndPortalVisibleTrueOrderBySortOrderAscIdAsc(tenant.getId());

        Map<String, Object> branding = new LinkedHashMap<>();
        branding.put("tenantId", tenant.getId());
        branding.put("tenantSlug", tenant.getSlug());
        branding.put("businessName", tenant.getName());
        branding.put("companyName", tenant.getCompanyName());
        branding.put("status", tenant.getStatus());
        branding.put("logoUrl", tenant.getBrandingLogoUrl());
        branding.put("primaryColor", tenant.getBrandingPrimaryColor());
        branding.put("secondaryColor", tenant.getBrandingSecondaryColor());
        branding.put("welcomeTitle", tenant.getPortalWelcomeTitle());
        branding.put("welcomeMessage", tenant.getPortalWelcomeMessage());
        branding.put("promotionalBannerText", tenant.getPortalPromotionalBanner());
        branding.put("whatsappSupportNumber", tenant.getWhatsappPhone());
        branding.put("supportPhoneNumber", tenant.getSupportPhone());
        branding.put("currencyCode", tenant.getCurrency());

        Map<String, Object> paymentOptions = new LinkedHashMap<>();
        paymentOptions.put("enableMtnMomo", tenant.isPortalEnableMtnMomo());
        paymentOptions.put("enableAirtelMoney", tenant.isPortalEnableAirtelMoney());
        paymentOptions.put("enableVoucherCode", tenant.isPortalEnableVoucherCode());

        List<Map<String, Object>> packageItems = eligiblePackages.stream().map(pkg -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", pkg.getId());
            item.put("name", pkg.getName());
            item.put("description", pkg.getDescription());
            item.put("badgeText", pkg.getBadgeText());
            item.put("price", pkg.getPrice());
            item.put("currencyCode", pkg.getCurrency());
            item.put("durationSeconds", pkg.getDurationSeconds());
            item.put("downloadKbps", pkg.getRateLimitDownKbps());
            item.put("uploadKbps", pkg.getRateLimitUpKbps());
            item.put("dataQuotaBytes", pkg.getDataQuotaBytes());
            item.put("simultaneousUse", pkg.getSimultaneousUse());
            return item;
        }).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("branding", branding);
        response.put("paymentOptions", paymentOptions);
        response.put("packages", packageItems);
        response.put("routerCode", routerCode);
        return response;
    }

    private Tenant resolveTenant(String tenantSlug, Long tenantId, String routerCode) {
        if (routerCode != null && !routerCode.isBlank()) {
            List<Long> routerTenantIds = jdbcTemplate.queryForList(
                "SELECT tenant_id FROM public.routers WHERE router_code = ? LIMIT 1",
                Long.class,
                routerCode.trim()
            );
            if (!routerTenantIds.isEmpty() && routerTenantIds.getFirst() != null) {
                return tenantRepository.findById(routerTenantIds.getFirst())
                    .orElseThrow(() -> new NoSuchElementException("Tenant for router not found"));
            }
        }
        if (tenantSlug != null && !tenantSlug.isBlank()) {
            return tenantRepository.findBySlug(tenantSlug.trim().toLowerCase())
                .orElseThrow(() -> new NoSuchElementException("Business portal '" + tenantSlug + "' not found"));
        }
        if (tenantId != null) {
            return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new NoSuchElementException("Business portal ID " + tenantId + " not found"));
        }
        return tenantRepository.findBySlug("default-hotspot")
            .or(() -> tenantRepository.findAllByOrderByIdAsc().stream().findFirst())
            .orElseThrow(() -> new NoSuchElementException("No active business portal found"));
    }
}
