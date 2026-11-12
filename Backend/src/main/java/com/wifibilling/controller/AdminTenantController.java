package com.wifibilling.controller;

import com.wifibilling.dto.AdminTenantDto;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.*;
import com.wifibilling.service.EmailService;
import com.wifibilling.service.EmailTemplates;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminTenantController {

    private final TenantRepository tenantRepository;
    private final RouterRepository routerRepository;
    private final VoucherRepository voucherRepository;
    private final TransactionRepository transactionRepository;
    private final RadAcctRepository radAcctRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final EmailTemplates emailTemplates;
    private final com.wifibilling.service.SubscriptionService subscriptionService;
    private final com.wifibilling.service.WinboxAccessService winboxAccess;
    private final AuditLogRepository auditLogRepository;
    private final com.wifibilling.service.GatewayConfigService gatewayConfig;
    private final com.wifibilling.security.JwtService jwtService;

    @Value("${app.public-base-url:http://localhost:3000}")
    private String publicBaseUrl;

    private com.wifibilling.school.SchoolManagementService schools;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSchools(com.wifibilling.school.SchoolManagementService schools) { this.schools = schools; }

    /**
     * Lists all provisioned tenants with aggregate metrics.
     */
    @GetMapping("/tenants")
    public List<AdminTenantDto.SummaryResponse> listTenants() {
        return tenantRepository.findAll().stream()
                .filter(t -> !"ROLE_SUPERADMIN".equals(t.getRole()))
                .map(t -> {
                    int routers = routerRepository.findAllByTenantId(t.getId()).size();
                    long vouchers = voucherRepository.countByTenantIdAndStatus(t.getId(), "unused")
                            + voucherRepository.countByTenantIdAndStatus(t.getId(), "active");
                    return new AdminTenantDto.SummaryResponse(
                            t.getId(),
                            t.getName(),
                            t.getEmail(),
                            t.getPhone(),
                            t.getRole(),
                            t.getStatus(),
                            routers,
                            vouchers,
                            t.getCreatedAt()
                    );
                })
                .toList();
    }

    /**
     * Provisions a new hotspot business admin.
     */
    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    @org.springframework.transaction.annotation.Transactional
    public AdminTenantDto.CreateResponse createTenant(@Valid @RequestBody AdminTenantDto.CreateRequest req) {
        String email = req.email().trim().toLowerCase();
        if (tenantRepository.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant email is already registered");
        }
        if (req.school() && (schools == null || req.itAdminEmail() == null || req.itAdminEmail().isBlank()
                || req.itAdminName() == null || req.itAdminName().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A school needs its IT person's name and email");
        }

        String temporaryPassword = generateTemporaryPassword();
        Tenant tenant = Tenant.builder()
                .name(req.name().trim())
                .email(email)
                .phone(req.phone().trim())
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .mustChangePassword(true)
                .role("ROLE_ADMIN")
                .status("ACTIVE")
                .emailVerified(true)
                .build();

        tenant = tenantRepository.save(tenant);

        // Automatically grant the one-time 7-day free trial upon new tenant creation
        if (subscriptionService != null) {
            subscriptionService.grantTrialIfEligible(tenant);
        }
        // A school is created in the identity system before anything is emailed; a failure rolls the tenant back.
        var schoolCredentials = req.school() ? schools.link(tenant, req.itAdminEmail(), req.itAdminName()) : null;

        AdminTenantDto.SummaryResponse summary = new AdminTenantDto.SummaryResponse(
                tenant.getId(),
                tenant.getName(),
                tenant.getEmail(),
                tenant.getPhone(),
                tenant.getRole(),
                tenant.getStatus(),
                0,
                0L,
                tenant.getCreatedAt()
        );

        boolean emailSent = false;
        if (emailService.configured()) {
            try {
                String loginUrl = publicBaseUrl.replaceAll("/+$", "") + "/login";
                emailService.send(tenant.getEmail(), "Welcome to AirPairy — your account is ready",
                        emailTemplates.tenantWelcomeEmail(tenant.getName(), tenant.getEmail(),
                                temporaryPassword, loginUrl));
                emailSent = true;
            } catch (EmailService.EmailDeliveryException ignored) {
                // The account remains usable; the Superadmin receives the password as a fallback.
            }
        }
        return new AdminTenantDto.CreateResponse(summary, emailSent ? null : temporaryPassword, emailSent,
                schoolCredentials == null ? null : schoolCredentials.emailSent(),
                schoolCredentials == null ? null : schoolCredentials.temporaryPassword());
    }

    /**
     * Resends or regenerates temporary credentials for an operator.
     */
    @PostMapping("/tenants/{id}/resend-credentials")
    public AdminTenantDto.ResetCredentialsResponse resendCredentials(@PathVariable Long id) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));

        if ("ROLE_SUPERADMIN".equals(tenant.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot reset Superadmin credentials through this endpoint");
        }

        String temporaryPassword = generateTemporaryPassword();
        tenant.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        tenant.setMustChangePassword(true);
        tenantRepository.save(tenant);

        boolean emailSent = false;
        if (emailService.configured()) {
            try {
                String loginUrl = publicBaseUrl.replaceAll("/+$", "") + "/login";
                emailService.send(tenant.getEmail(), "AirPairy — Your login credentials",
                        emailTemplates.tenantWelcomeEmail(tenant.getName(), tenant.getEmail(),
                                temporaryPassword, loginUrl));
                emailSent = true;
            } catch (EmailService.EmailDeliveryException ignored) {
            }
        }

        return new AdminTenantDto.ResetCredentialsResponse(
                tenant.getId(),
                tenant.getEmail(),
                temporaryPassword,
                emailSent
        );
    }

    private String generateTemporaryPassword() {
        SecureRandom random = new SecureRandom();
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String lower = "abcdefghijkmnopqrstuvwxyz";
        String digits = "23456789";
        String symbols = "!@#$%";
        String all = upper + lower + digits + symbols;
        List<Character> chars = new ArrayList<>();
        chars.add(upper.charAt(random.nextInt(upper.length())));
        chars.add(lower.charAt(random.nextInt(lower.length())));
        chars.add(digits.charAt(random.nextInt(digits.length())));
        chars.add(symbols.charAt(random.nextInt(symbols.length())));
        while (chars.size() < 16) chars.add(all.charAt(random.nextInt(all.length())));
        Collections.shuffle(chars, random);
        StringBuilder result = new StringBuilder(chars.size());
        chars.forEach(result::append);
        return result.toString();
    }

    /**
     * Activates, suspends, or disables a tenant account.
     */
    @PutMapping("/tenants/{id}/status")
    @org.springframework.transaction.annotation.Transactional
    public AdminTenantDto.SummaryResponse updateTenantStatus(
            @PathVariable Long id,
            @Valid @RequestBody AdminTenantDto.UpdateStatusRequest req) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));

        if ("ROLE_SUPERADMIN".equals(tenant.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot alter Superadmin account status");
        }

        String newStatus = req.status().trim().toUpperCase();
        if (!List.of("ACTIVE", "SUSPENDED", "DISABLED").contains(newStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status: " + newStatus);
        }

        if (!"ACTIVE".equals(newStatus)) winboxAccess.revokeTenant(id);
        tenant.setStatus(newStatus);
        tenant = tenantRepository.save(tenant);

        int routers = routerRepository.findAllByTenantId(tenant.getId()).size();
        long vouchers = voucherRepository.countByTenantIdAndStatus(tenant.getId(), "unused");

        return new AdminTenantDto.SummaryResponse(
                tenant.getId(),
                tenant.getName(),
                tenant.getEmail(),
                tenant.getPhone(),
                tenant.getRole(),
                tenant.getStatus(),
                routers,
                vouchers,
                tenant.getCreatedAt()
        );
    }

    /**
     * Platform-wide telemetry and billing metrics.
     */
    @GetMapping("/metrics")
    public AdminTenantDto.PlatformMetricsResponse getPlatformMetrics() {
        long totalTenants = tenantRepository.count() - 1; // minus superadmin
        long totalRouters = routerRepository.count();
        long onlineRouters = routerRepository.findAll().stream()
                .filter(r -> "online".equalsIgnoreCase(r.getStatus()))
                .count();

        long totalVouchers = voucherRepository.count();
        var allTx = transactionRepository.findAll();
        long totalTx = allTx.size();
        BigDecimal totalRevenue = allTx.stream()
                .filter(tx -> "SUCCESSFUL".equalsIgnoreCase(tx.getStatus()))
                .map(tx -> tx.getAmount() != null ? tx.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Active guest sessions currently authenticated in radacct
        long activeSessions = radAcctRepository.findAll().stream()
                .filter(a -> a.getAcctStopTime() == null)
                .count();

        return new AdminTenantDto.PlatformMetricsResponse(
                Math.max(0, totalTenants),
                totalRouters,
                onlineRouters,
                totalVouchers,
                totalTx,
                totalRevenue,
                activeSessions
        );
    }

    /** Platform-wide router inventory. Deliberately excludes provisioning tokens,
     * RADIUS secrets and WireGuard public keys. */
    @GetMapping("/routers")
    public List<AdminTenantDto.RouterFleetResponse> listRouters() {
        return routerRepository.findAll().stream()
                .map(r -> new AdminTenantDto.RouterFleetResponse(
                        r.getId(),
                        r.getTenant().getId(),
                        r.getTenant().getName(),
                        r.getName(),
                        r.getStatus(),
                        r.getWgIp(),
                        r.getCpuLoad(),
                        r.getMemoryUsage(),
                        r.getUptime(),
                        r.getActiveUsers(),
                        r.getHotspotReady(),
                        r.getRadiusReady(),
                        r.getPortalReady(),
                        r.getLastSeen(),
                        r.getCreatedAt()
                ))
                .toList();
    }

    // ---------- platform fee ----------

    /** Platform fee for one tenant: whether it's on, its rate, and what it has earned. */
    public record PlatformFeeView(boolean enabled, BigDecimal percent, String gatewayMode, boolean appliesNow,
                                  long earnedThisMonth, long earnedTotal) {}

    public record PlatformFeeUpdate(Boolean enabled, BigDecimal percent) {}

    @GetMapping("/tenants/{id}/platform-fee")
    public PlatformFeeView platformFee(@PathVariable Long id) {
        return platformFeeView(findBusinessTenant(id));
    }

    /**
     * Issues a 2h impersonation token so the superadmin can open the tenant's own
     * dashboard and operate it directly (support, troubleshooting, acting on the
     * client's behalf). The sensitive paths stay closed anyway: password/2FA/email
     * changes need the tenant's password, and withdrawals need an OTP that goes to
     * the tenant's email — not to the impersonator. Every use is audit-logged.
     */
    @PostMapping("/tenants/{id}/impersonate")
    public java.util.Map<String, Object> impersonate(@PathVariable Long id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.wifibilling.security.TenantPrincipal principal) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        if ("ROLE_SUPERADMIN".equals(tenant.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot impersonate the platform account");
        }
        String actor = principal != null ? principal.getEmail() : null;
        String token = jwtService.generateImpersonationToken(tenant.getId(), tenant.getEmail(), tenant.getRole(), actor);
        auditLogRepository.save(com.wifibilling.model.AuditLog.builder()
                .tenantId(tenant.getId())
                .actorEmail(actor)
                .action("TENANT_IMPERSONATED")
                .details("Superadmin opened " + tenant.getName() + "'s dashboard")
                .build());
        return java.util.Map.of(
                "token", token,
                "tenantId", tenant.getId(),
                "name", tenant.getName() != null ? tenant.getName() : "",
                "email", tenant.getEmail(),
                "role", tenant.getRole(),
                "mustChangePassword", false);
    }

    /**
     * Switches the platform fee on or off and sets its rate. Applies only to payments
     * started from now on; every change is written to the audit log.
     */
    @PutMapping("/tenants/{id}/platform-fee")
    @org.springframework.transaction.annotation.Transactional
    public PlatformFeeView updatePlatformFee(@PathVariable Long id, @RequestBody PlatformFeeUpdate req,
                                             @org.springframework.security.core.annotation.AuthenticationPrincipal
                                             com.wifibilling.security.TenantPrincipal principal) {
        if (req == null || req.enabled() == null || req.percent() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled and percent are required");
        }
        BigDecimal percent = req.percent().stripTrailingZeros();
        if (percent.scale() > 2 || percent.compareTo(new BigDecimal("0.01")) < 0 || percent.compareTo(new BigDecimal("20")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rate must be between 0.01% and 20%, with at most two decimals");
        }
        Tenant tenant = findBusinessTenant(id);
        String before = describeFee(tenant);
        tenant.setPlatformFeeEnabled(req.enabled());
        tenant.setPlatformFeeBps(percent.movePointRight(2).intValueExact());
        tenantRepository.save(tenant);
        String after = describeFee(tenant);
        if (!before.equals(after)) {
            auditLogRepository.save(com.wifibilling.model.AuditLog.builder()
                    .tenantId(tenant.getId())
                    .actorEmail(principal != null ? principal.getEmail() : null)
                    .action("PLATFORM_FEE_CHANGED")
                    .details(tenant.getName() + ": " + before + " -> " + after)
                    .build());
        }
        return platformFeeView(tenant);
    }

    private Tenant findBusinessTenant(Long id) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        if ("ROLE_SUPERADMIN".equals(tenant.getRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The platform account has no platform fee");
        }
        return tenant;
    }

    private static String describeFee(Tenant t) {
        String rate = BigDecimal.valueOf(t.getPlatformFeeBps(), 2).stripTrailingZeros().toPlainString() + "%";
        return Boolean.TRUE.equals(t.getPlatformFeeEnabled()) ? "on at " + rate : "off (rate " + rate + ")";
    }

    private PlatformFeeView platformFeeView(Tenant tenant) {
        String mode = gatewayConfig.modeFor(tenant);
        java.time.OffsetDateTime monthStart = java.time.YearMonth.now().atDay(1).atStartOfDay()
                .atOffset(java.time.OffsetDateTime.now().getOffset());
        return new PlatformFeeView(
                Boolean.TRUE.equals(tenant.getPlatformFeeEnabled()),
                BigDecimal.valueOf(tenant.getPlatformFeeBps(), 2).stripTrailingZeros(),
                mode,
                tenant.activePlatformFeeBps() > 0 && com.wifibilling.model.TenantGatewayConfig.MODE_PLATFORM.equals(mode),
                transactionRepository.sumPlatformFeeForTenantSince(tenant.getId(), monthStart),
                transactionRepository.sumPlatformFeeForTenantSince(tenant.getId(), java.time.OffsetDateTime.parse("2000-01-01T00:00:00Z")));
    }
}
