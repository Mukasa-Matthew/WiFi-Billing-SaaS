package world.edubridge.wifibilling.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import world.edubridge.wifibilling.domain.PackagePlan;
import world.edubridge.wifibilling.domain.Tenant;
import world.edubridge.wifibilling.domain.TenantSubscription;
import world.edubridge.wifibilling.domain.UserAccount;
import world.edubridge.wifibilling.repository.AuditLogRepository;
import world.edubridge.wifibilling.repository.PackagePlanRepository;
import world.edubridge.wifibilling.repository.TenantRepository;
import world.edubridge.wifibilling.repository.TenantSubscriptionRepository;
import world.edubridge.wifibilling.repository.UserAccountRepository;
import world.edubridge.wifibilling.security.AuthenticatedUserPrincipal;
import world.edubridge.wifibilling.security.TenantAccessGuard;
import world.edubridge.wifibilling.service.AuditService;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/business")
public class BusinessAdminController {

    private final TenantAccessGuard tenantAccessGuard;
    private final TenantRepository tenantRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final PackagePlanRepository packagePlanRepository;
    private final UserAccountRepository userAccountRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    public BusinessAdminController(TenantAccessGuard tenantAccessGuard,
                                   TenantRepository tenantRepository,
                                   TenantSubscriptionRepository subscriptionRepository,
                                   PackagePlanRepository packagePlanRepository,
                                   UserAccountRepository userAccountRepository,
                                   AuditLogRepository auditLogRepository,
                                   AuditService auditService,
                                   JdbcTemplate jdbcTemplate) {
        this.tenantAccessGuard = tenantAccessGuard;
        this.tenantRepository = tenantRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.packagePlanRepository = packagePlanRepository;
        this.userAccountRepository = userAccountRepository;
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record PortalSettingsUpdateRequest(
        @NotBlank(message = "Business name is required")
        String businessName,
        String logoUrl,
        @NotBlank(message = "Primary color is required")
        String primaryColor,
        @NotBlank(message = "Secondary color is required")
        String secondaryColor,
        String whatsappSupportNumber,
        String supportPhoneNumber,
        @NotBlank(message = "Welcome title is required")
        String portalWelcomeTitle,
        String portalWelcomeMessage,
        String promotionalBannerText,
        boolean enableMtnMomo,
        boolean enableAirtelMoney,
        boolean enableVoucherCode
    ) {}

    public record CreateOrUpdatePackageRequest(
        @NotBlank(message = "Package name is required")
        String name,
        String description,
        String badgeText,
        @NotNull(message = "Price is required")
        @PositiveOrZero(message = "Price must be zero or positive")
        BigDecimal price,
        @Positive(message = "Duration seconds must be positive")
        int durationSeconds,
        @Positive(message = "Download kbps must be positive")
        int downloadKbps,
        @Positive(message = "Upload kbps must be positive")
        int uploadKbps,
        Long dataQuotaBytes,
        @Positive(message = "Simultaneous use must be at least 1")
        int simultaneousUse,
        boolean active,
        boolean portalVisible,
        int sortOrder
    ) {}

    @GetMapping("/dashboard")
    public Map<String, Object> getBusinessDashboard(@RequestParam(required = false) Long tenantId) {
        Long resolvedTenantId = tenantAccessGuard.requireBusinessTenantAccess(tenantId);
        Tenant tenant = tenantRepository.findById(resolvedTenantId)
            .orElseThrow(() -> new NoSuchElementException("Tenant not found"));
        TenantSubscription subscription = subscriptionRepository.findByTenantId(resolvedTenantId).orElse(null);

        List<PackagePlan> packages = packagePlanRepository.findByTenantIdOrderBySortOrderAscIdAsc(resolvedTenantId);
        long enabledPackages = packages.stream().filter(p -> p.isActive() && p.isPortalVisible()).count();

        Integer routerCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM public.routers WHERE tenant_id = ?",
            Integer.class,
            resolvedTenantId
        );
        Integer voucherCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM public.vouchers WHERE tenant_id = ?",
            Integer.class,
            resolvedTenantId
        );
        Integer customerCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM public.customers WHERE tenant_id = ?",
            Integer.class,
            resolvedTenantId
        );

        List<Map<String, Object>> staff = userAccountRepository.findByTenantIdOrderByIdAsc(resolvedTenantId).stream()
            .map(this::mapStaffAccount)
            .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenant", tenant);
        response.put("subscription", subscription);
        response.put("metrics", Map.of(
            "totalRouters", routerCount != null ? routerCount : 0,
            "totalPackages", packages.size(),
            "portalVisiblePackages", enabledPackages,
            "totalVouchers", voucherCount != null ? voucherCount : 0,
            "totalCustomers", customerCount != null ? customerCount : 0
        ));
        response.put("packages", packages);
        response.put("staff", staff);
        response.put("recentAuditLogs", auditLogRepository.findTop50ByTenantIdOrderByCreatedAtDesc(resolvedTenantId));
        return response;
    }

    @GetMapping("/portal-settings")
    public Tenant getPortalSettings(@RequestParam(required = false) Long tenantId) {
        Long resolvedTenantId = tenantAccessGuard.requireBusinessTenantAccess(tenantId);
        return tenantRepository.findById(resolvedTenantId)
            .orElseThrow(() -> new NoSuchElementException("Tenant not found"));
    }

    @PutMapping("/portal-settings")
    @Transactional
    public Tenant updatePortalSettings(@RequestParam(required = false) Long tenantId,
                                       @Valid @RequestBody PortalSettingsUpdateRequest req,
                                       HttpServletRequest httpRequest) {
        AuthenticatedUserPrincipal principal = tenantAccessGuard.requireAuthenticatedUser();
        Long resolvedTenantId = tenantAccessGuard.requireBusinessTenantAccess(tenantId, "business_admin", "manager");

        Tenant tenant = tenantRepository.findById(resolvedTenantId)
            .orElseThrow(() -> new NoSuchElementException("Tenant not found"));

        tenant.setName(req.businessName().trim());
        tenant.setBrandingLogoUrl(req.logoUrl() != null && !req.logoUrl().isBlank() ? req.logoUrl().trim() : null);
        tenant.setBrandingPrimaryColor(req.primaryColor().trim());
        tenant.setBrandingSecondaryColor(req.secondaryColor().trim());
        tenant.setWhatsappPhone(req.whatsappSupportNumber() != null ? req.whatsappSupportNumber().trim() : null);
        tenant.setSupportPhone(req.supportPhoneNumber() != null ? req.supportPhoneNumber().trim() : null);
        tenant.setPortalWelcomeTitle(req.portalWelcomeTitle().trim());
        tenant.setPortalWelcomeMessage(req.portalWelcomeMessage() != null ? req.portalWelcomeMessage().trim() : "");
        tenant.setPortalPromotionalBanner(req.promotionalBannerText() != null && !req.promotionalBannerText().isBlank()
            ? req.promotionalBannerText().trim()
            : null);
        tenant.setPortalEnableMtnMomo(req.enableMtnMomo());
        tenant.setPortalEnableAirtelMoney(req.enableAirtelMoney());
        tenant.setPortalEnableVoucherCode(req.enableVoucherCode());
        tenant.setUpdatedAt(OffsetDateTime.now());

        Tenant saved = tenantRepository.save(tenant);

        auditService.record(
            resolvedTenantId,
            principal.getUserId(),
            "PORTAL_SETTINGS_UPDATED",
            "tenant",
            String.valueOf(resolvedTenantId),
            Map.of("welcomeTitle", saved.getPortalWelcomeTitle(), "primaryColor", saved.getBrandingPrimaryColor()),
            httpRequest.getRemoteAddr()
        );

        return saved;
    }

    @GetMapping("/packages")
    public List<PackagePlan> listPackages(@RequestParam(required = false) Long tenantId) {
        Long resolvedTenantId = tenantAccessGuard.requireBusinessTenantAccess(tenantId);
        return packagePlanRepository.findByTenantIdOrderBySortOrderAscIdAsc(resolvedTenantId);
    }

    @PostMapping("/packages")
    @Transactional
    public ResponseEntity<PackagePlan> createPackage(@RequestParam(required = false) Long tenantId,
                                                     @Valid @RequestBody CreateOrUpdatePackageRequest req,
                                                     HttpServletRequest httpRequest) {
        AuthenticatedUserPrincipal principal = tenantAccessGuard.requireAuthenticatedUser();
        Long resolvedTenantId = tenantAccessGuard.requireBusinessTenantAccess(tenantId, "business_admin", "manager");

        Tenant tenant = tenantRepository.findById(resolvedTenantId)
            .orElseThrow(() -> new NoSuchElementException("Tenant not found"));

        PackagePlan pkg = new PackagePlan();
        pkg.setTenantId(resolvedTenantId);
        pkg.setName(req.name().trim());
        pkg.setDescription(req.description());
        pkg.setBadgeText(req.badgeText());
        pkg.setPrice(req.price());
        pkg.setCurrency(tenant.getCurrency());
        pkg.setDurationSeconds(req.durationSeconds());
        pkg.setSessionTimeout(req.durationSeconds());
        pkg.setRateLimitDownKbps(req.downloadKbps());
        pkg.setRateLimitUpKbps(req.uploadKbps());
        pkg.setDataQuotaBytes(req.dataQuotaBytes());
        pkg.setSimultaneousUse(req.simultaneousUse());
        pkg.setActive(req.active());
        pkg.setPortalVisible(req.portalVisible());
        pkg.setSortOrder(req.sortOrder());

        PackagePlan saved = packagePlanRepository.save(pkg);

        auditService.record(
            resolvedTenantId,
            principal.getUserId(),
            "PACKAGE_CREATED",
            "package",
            String.valueOf(saved.getId()),
            Map.of("name", saved.getName(), "price", saved.getPrice()),
            httpRequest.getRemoteAddr()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PatchMapping("/packages/{packageId}/toggle")
    @Transactional
    public PackagePlan togglePackageVisibility(@PathVariable Long packageId,
                                               @RequestParam(required = false) Long tenantId,
                                               @RequestBody Map<String, Boolean> toggleReq,
                                               HttpServletRequest httpRequest) {
        AuthenticatedUserPrincipal principal = tenantAccessGuard.requireAuthenticatedUser();
        Long resolvedTenantId = tenantAccessGuard.requireBusinessTenantAccess(tenantId, "business_admin", "manager");

        PackagePlan pkg = packagePlanRepository.findByIdAndTenantId(packageId, resolvedTenantId)
            .orElseThrow(() -> new NoSuchElementException("Package not found in current business tenant"));

        if (toggleReq.containsKey("active")) {
            pkg.setActive(Boolean.TRUE.equals(toggleReq.get("active")));
        }
        if (toggleReq.containsKey("portalVisible")) {
            pkg.setPortalVisible(Boolean.TRUE.equals(toggleReq.get("portalVisible")));
        }
        PackagePlan saved = packagePlanRepository.save(pkg);

        auditService.record(
            resolvedTenantId,
            principal.getUserId(),
            "PACKAGE_TOGGLED",
            "package",
            String.valueOf(saved.getId()),
            Map.of("active", saved.isActive(), "portalVisible", saved.isPortalVisible()),
            httpRequest.getRemoteAddr()
        );

        return saved;
    }

    private Map<String, Object> mapStaffAccount(UserAccount user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("email", user.getEmail());
        map.put("fullName", user.getFullName());
        map.put("phone", user.getPhone());
        map.put("role", user.getRole());
        map.put("status", user.getStatus());
        map.put("lastLoginAt", user.getLastLoginAt());
        return map;
    }
}
