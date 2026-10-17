package world.edubridge.wifibilling.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.edubridge.wifibilling.domain.Tenant;
import world.edubridge.wifibilling.domain.TenantSubscription;
import world.edubridge.wifibilling.domain.UserAccount;
import world.edubridge.wifibilling.repository.AuditLogRepository;
import world.edubridge.wifibilling.repository.TenantRepository;
import world.edubridge.wifibilling.repository.TenantSubscriptionRepository;
import world.edubridge.wifibilling.repository.UserAccountRepository;
import world.edubridge.wifibilling.security.AuthenticatedUserPrincipal;
import world.edubridge.wifibilling.security.TenantAccessGuard;
import world.edubridge.wifibilling.service.AuditService;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/super-admin")
public class SuperAdminController {

    private final TenantAccessGuard tenantAccessGuard;
    private final TenantRepository tenantRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final UserAccountRepository userAccountRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    public SuperAdminController(TenantAccessGuard tenantAccessGuard,
                                TenantRepository tenantRepository,
                                TenantSubscriptionRepository subscriptionRepository,
                                UserAccountRepository userAccountRepository,
                                AuditLogRepository auditLogRepository,
                                PasswordEncoder passwordEncoder,
                                AuditService auditService,
                                JdbcTemplate jdbcTemplate) {
        this.tenantAccessGuard = tenantAccessGuard;
        this.tenantRepository = tenantRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.userAccountRepository = userAccountRepository;
        this.auditLogRepository = auditLogRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record CreateTenantRequest(
        @NotBlank(message = "Business name is required")
        String businessName,
        @NotBlank(message = "Slug is required")
        String slug,
        @NotBlank(message = "Contact email is required")
        @Email(message = "Valid contact email is required")
        String contactEmail,
        String contactPhone,
        String whatsappSupportNumber,
        String subscriptionTier,
        @NotBlank(message = "Initial Business Admin email is required")
        @Email(message = "Valid Business Admin email is required")
        String adminEmail,
        @NotBlank(message = "Initial Business Admin full name is required")
        String adminFullName,
        @NotBlank(message = "Initial Business Admin password is required")
        String adminPassword
    ) {}

    public record UpdateTenantStatusRequest(
        @NotBlank(message = "Status is required (active, suspended, trial)")
        String status,
        String subscriptionTier
    ) {}

    @GetMapping("/overview")
    public Map<String, Object> getPlatformOverview() {
        tenantAccessGuard.requireSuperAdmin();

        long totalTenants = tenantRepository.count();
        long activeTenants = tenantRepository.findAll().stream()
            .filter(t -> "active".equalsIgnoreCase(t.getStatus()) || "trial".equalsIgnoreCase(t.getStatus()))
            .count();
        Integer totalRouters = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.routers", Integer.class);
        Integer totalPackages = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM public.packages", Integer.class);

        List<Map<String, Object>> tenants = tenantRepository.findAllByOrderByIdAsc().stream()
            .map(this::mapTenantWithSubscription)
            .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalTenants", totalTenants);
        response.put("activeTenants", activeTenants);
        response.put("suspendedTenants", totalTenants - activeTenants);
        response.put("totalRouters", totalRouters != null ? totalRouters : 0);
        response.put("totalPackages", totalPackages != null ? totalPackages : 0);
        response.put("tenants", tenants);
        response.put("recentAuditLogs", auditLogRepository.findTop50ByOrderByCreatedAtDesc());
        return response;
    }

    @GetMapping("/tenants")
    public List<Map<String, Object>> listTenants() {
        tenantAccessGuard.requireSuperAdmin();
        return tenantRepository.findAllByOrderByIdAsc().stream()
            .map(this::mapTenantWithSubscription)
            .toList();
    }

    @PostMapping("/tenants")
    @Transactional
    public ResponseEntity<Map<String, Object>> createTenant(@Valid @RequestBody CreateTenantRequest req,
                                                            HttpServletRequest httpRequest) {
        AuthenticatedUserPrincipal superAdmin = tenantAccessGuard.requireSuperAdmin();

        String normalizedSlug = req.slug().trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9-]", "-");
        if (tenantRepository.findBySlug(normalizedSlug).isPresent()) {
            throw new IllegalArgumentException("Tenant slug '" + normalizedSlug + "' is already in use");
        }
        if (userAccountRepository.findByEmailIgnoreCase(req.adminEmail().trim()).isPresent()) {
            throw new IllegalArgumentException("Admin email '" + req.adminEmail().trim() + "' is already registered");
        }

        Tenant tenant = new Tenant();
        tenant.setName(req.businessName().trim());
        tenant.setCompanyName(req.businessName().trim());
        tenant.setContactName(req.adminFullName().trim());
        tenant.setSlug(normalizedSlug);
        tenant.setStatus("active");
        tenant.setContactEmail(req.contactEmail().trim());
        tenant.setContactPhone(req.contactPhone() != null ? req.contactPhone().trim() : "+256700000000");
        tenant.setWhatsappPhone(req.whatsappSupportNumber() != null
            ? req.whatsappSupportNumber().trim()
            : tenant.getContactPhone());
        tenant.setSupportPhone(tenant.getContactPhone());
        tenant.setPortalWelcomeTitle("Welcome to " + tenant.getName());
        tenant.setPortalWelcomeMessage("Select an instant high-speed WiFi package or redeem your voucher code below.");
        Tenant savedTenant = tenantRepository.save(tenant);

        String tier = req.subscriptionTier() != null && !req.subscriptionTier().isBlank()
            ? req.subscriptionTier().trim().toLowerCase(Locale.ROOT)
            : "pro";

        TenantSubscription sub = new TenantSubscription();
        sub.setTenantId(savedTenant.getId());
        sub.setPlanTier(tier);
        sub.setStatus("active");
        sub.setRouterLimit("enterprise".equals(tier) ? 100 : 15);
        sub.setVoucherLimit("enterprise".equals(tier) ? 250000 : 25000);
        sub.setCurrentPeriodEnd(OffsetDateTime.now().plusDays(30));
        subscriptionRepository.save(sub);

        UserAccount adminUser = new UserAccount();
        adminUser.setTenantId(savedTenant.getId());
        adminUser.setEmail(req.adminEmail().trim().toLowerCase(Locale.ROOT));
        adminUser.setFullName(req.adminFullName().trim());
        adminUser.setPhone(tenant.getContactPhone());
        adminUser.setRole("business_admin");
        adminUser.setPasswordHash(passwordEncoder.encode(req.adminPassword()));
        adminUser.setStatus("active");
        userAccountRepository.save(adminUser);

        auditService.record(
            savedTenant.getId(),
            superAdmin.getUserId(),
            "TENANT_CREATED",
            "tenant",
            String.valueOf(savedTenant.getId()),
            Map.of("slug", savedTenant.getSlug(), "adminEmail", adminUser.getEmail(), "tier", tier),
            httpRequest.getRemoteAddr()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(mapTenantWithSubscription(savedTenant));
    }

    @PatchMapping("/tenants/{tenantId}/status")
    @Transactional
    public Map<String, Object> updateTenantStatus(@PathVariable Long tenantId,
                                                  @Valid @RequestBody UpdateTenantStatusRequest req,
                                                  HttpServletRequest httpRequest) {
        AuthenticatedUserPrincipal superAdmin = tenantAccessGuard.requireSuperAdmin();
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new NoSuchElementException("Tenant " + tenantId + " not found"));

        String normalizedStatus = req.status().trim().toLowerCase(Locale.ROOT);
        if (!List.of("active", "suspended", "trial").contains(normalizedStatus)) {
            throw new IllegalArgumentException("Invalid tenant status: " + normalizedStatus);
        }

        tenant.setStatus(normalizedStatus);
        tenant.setUpdatedAt(OffsetDateTime.now());
        tenantRepository.save(tenant);

        subscriptionRepository.findByTenantId(tenantId).ifPresent(sub -> {
            if (req.subscriptionTier() != null && !req.subscriptionTier().isBlank()) {
                sub.setPlanTier(req.subscriptionTier().trim().toLowerCase(Locale.ROOT));
            }
            sub.setStatus("suspended".equals(normalizedStatus) ? "suspended" : "active");
            subscriptionRepository.save(sub);
        });

        auditService.record(
            tenantId,
            superAdmin.getUserId(),
            "TENANT_STATUS_UPDATED",
            "tenant",
            String.valueOf(tenantId),
            Map.of("status", normalizedStatus),
            httpRequest.getRemoteAddr()
        );

        return mapTenantWithSubscription(tenant);
    }

    private Map<String, Object> mapTenantWithSubscription(Tenant tenant) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", tenant.getId());
        item.put("businessName", tenant.getName());
        item.put("slug", tenant.getSlug());
        item.put("status", tenant.getStatus());
        item.put("contactEmail", tenant.getContactEmail());
        item.put("contactPhone", tenant.getContactPhone());
        item.put("whatsappSupportNumber", tenant.getWhatsappPhone());
        item.put("currencyCode", tenant.getCurrency());
        item.put("timezone", tenant.getTimezone());
        item.put("createdAt", tenant.getCreatedAt());

        TenantSubscription sub = subscriptionRepository.findByTenantId(tenant.getId()).orElse(null);
        if (sub != null) {
            item.put("subscriptionPlan", sub.getPlanTier());
            item.put("subscriptionStatus", sub.getStatus());
            item.put("maxRouters", sub.getRouterLimit());
            item.put("maxVouchersMonthly", sub.getVoucherLimit());
        }
        item.put("userCount", userAccountRepository.findByTenantIdOrderByIdAsc(tenant.getId()).size());
        return item;
    }
}
