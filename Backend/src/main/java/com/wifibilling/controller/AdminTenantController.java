package com.wifibilling.controller;

import com.wifibilling.dto.AdminTenantDto;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.*;
import com.wifibilling.service.EmailService;
import com.wifibilling.service.EmailTemplates;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
@PreAuthorize("hasAuthority('ROLE_SUPERADMIN')")
public class AdminTenantController {

    private final TenantRepository tenantRepository;
    private final RouterRepository routerRepository;
    private final VoucherRepository voucherRepository;
    private final TransactionRepository transactionRepository;
    private final RadAcctRepository radAcctRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final EmailTemplates emailTemplates;

    @org.springframework.beans.factory.annotation.Value("${app.public-base-url:http://localhost:3000}")
    private String publicBaseUrl;

    /**
     * Lists all hotspot owners along with their router and voucher metrics.
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
    public AdminTenantDto.CreateResponse createTenant(@Valid @RequestBody AdminTenantDto.CreateRequest req) {
        String email = req.email().trim().toLowerCase();
        if (tenantRepository.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant email is already registered");
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
                .build();

        tenant = tenantRepository.save(tenant);

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
        return new AdminTenantDto.CreateResponse(summary, emailSent ? null : temporaryPassword, emailSent);
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
}
