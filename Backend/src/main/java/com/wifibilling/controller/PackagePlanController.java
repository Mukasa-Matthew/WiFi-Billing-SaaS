package com.wifibilling.controller;

import com.wifibilling.dto.PackagePlanDto;
import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.PackagePlanRepository;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.security.TenantPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/packages")
@RequiredArgsConstructor
public class PackagePlanController {

    private final PackagePlanRepository packagePlanRepository;
    private final TenantRepository tenantRepository;
    private final com.wifibilling.repository.RouterRepository routerRepository;
    private final com.wifibilling.service.RouterManagementService routerManagement;
    private final com.wifibilling.service.SubscriptionService subscriptionService;

    /** Push an instant packages.json sync to every router the tenant owns
     *  (sub-second over the WireGuard tunnel; 1-min poll is the fallback). */
    private void syncPortals(Long tenantId) {
        for (var r : routerRepository.findAllByTenantId(tenantId)) {
            try { routerManagement.enqueue(r, "SYNC_PORTAL"); } catch (ResponseStatusException ignored) {}
            routerManagement.triggerPortalSync(r);
        }
    }

    @GetMapping
    public List<PackagePlanDto.Response> list(@AuthenticationPrincipal TenantPrincipal principal) {
        return packagePlanRepository.findAllByTenantIdOrderByPriceAsc(principal.getTenantId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PackagePlanDto.Response create(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody PackagePlanDto.CreateRequest req) {
        if (subscriptionService != null) {
            subscriptionService.assertTenantManagementAllowed(principal.getTenantId());
        }
        Tenant tenant = tenantRepository.findById(principal.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));

        PackagePlan plan = PackagePlan.builder()
                .tenant(tenant)
                .router(req.routerId() == null ? null
                        : routerRepository.findByIdAndTenantId(req.routerId(), tenant.getId())
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found")))
                .name(req.name().trim())
                .price(req.price())
                .rateLimit(sanitizeRateLimit(req.rateLimit()))
                .sessionTimeout(req.sessionTimeout())
                .sharedUsers(req.sharedUsers() != null ? req.sharedUsers() : 1)
                .description(req.description())
                .availability(req.availability() == null ? "ACTIVE" : req.availability())
                .featured(Boolean.TRUE.equals(req.featured()))
                .displayOrder(req.displayOrder() == null ? 0 : req.displayOrder())
                .build();

        plan = packagePlanRepository.save(plan);
        syncPortals(plan.getTenant().getId());
        return toResponse(plan);
    }

    @PutMapping("/{id}")
    public PackagePlanDto.Response update(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody PackagePlanDto.CreateRequest req) {
        if (subscriptionService != null) {
            subscriptionService.assertTenantManagementAllowed(principal.getTenantId());
        }
        PackagePlan plan = packagePlanRepository.findByIdAndTenantId(id, principal.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));

        plan.setName(req.name().trim());
        plan.setPrice(req.price());
        plan.setRateLimit(sanitizeRateLimit(req.rateLimit()));
        plan.setSessionTimeout(req.sessionTimeout());
        plan.setSharedUsers(req.sharedUsers() != null ? req.sharedUsers() : 1);
        plan.setDescription(req.description());
        if (req.availability() != null) plan.setAvailability(req.availability());
        plan.setFeatured(Boolean.TRUE.equals(req.featured()));
        plan.setDisplayOrder(req.displayOrder() == null ? 0 : req.displayOrder());
        plan.setRouter(req.routerId() == null ? null
                : routerRepository.findByIdAndTenantId(req.routerId(), plan.getTenant().getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found")));

        PackagePlan saved = packagePlanRepository.save(plan);
        syncPortals(plan.getTenant().getId());
        return toResponse(saved);
    }

    @GetMapping("/{id}")
    public PackagePlanDto.Response get(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable Long id) {
        PackagePlan plan = packagePlanRepository.findByIdAndTenantId(id, principal.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));
        return toResponse(plan);
    }

    @PatchMapping("/{id}/availability")
    public PackagePlanDto.Response availability(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody AvailabilityRequest req) {
        if (subscriptionService != null) {
            subscriptionService.assertTenantManagementAllowed(principal.getTenantId());
        }
        PackagePlan plan = packagePlanRepository.findByIdAndTenantId(id, principal.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));
        plan.setAvailability(req.availability());
        PackagePlan saved = packagePlanRepository.save(plan);
        syncPortals(plan.getTenant().getId());
        return toResponse(saved);
    }

    public record AvailabilityRequest(
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.Pattern(regexp = "ACTIVE|HIDDEN|DISABLED") String availability) {}

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable Long id) {
        if (subscriptionService != null) {
            subscriptionService.assertTenantManagementAllowed(principal.getTenantId());
        }
        PackagePlan plan = packagePlanRepository.findByIdAndTenantId(id, principal.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));
        packagePlanRepository.delete(plan);
        syncPortals(principal.getTenantId());
    }

    /** RouterOS simple queues only accept whole-number rates (e.g. "10M/10M").
     *  Fractional values like "9.7M" cause "invalid rate limit" and break hotspot login,
     *  so each side of the limit is rounded to the nearest whole unit. */
    private static final java.util.regex.Pattern RATE_PART =
            java.util.regex.Pattern.compile("(?i)^(\\d+(?:\\.\\d+)?)([kmg])$");

    public static String sanitizeRateLimit(String rateLimit) {
        if (rateLimit == null) return null;
        StringBuilder out = new StringBuilder();
        String[] parts = rateLimit.trim().split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append('/');
            var m = RATE_PART.matcher(parts[i].trim());
            out.append(m.matches()
                    ? Math.max(1, Math.round(Double.parseDouble(m.group(1)))) + m.group(2).toUpperCase()
                    : parts[i].trim());
        }
        return out.toString();
    }

    private PackagePlanDto.Response toResponse(PackagePlan p) {
        return new PackagePlanDto.Response(
                p.getId(),
                p.getName(),
                p.getPrice(),
                p.getRateLimit(),
                p.getSessionTimeout(),
                p.getSharedUsers(),
                p.getDescription(),
                p.effectiveAvailability(),
                Boolean.TRUE.equals(p.getFeatured()),
                p.getDisplayOrder() == null ? 0 : p.getDisplayOrder(),
                p.getRouter() == null ? null : p.getRouter().getId(),
                p.getRouter() == null ? null : p.getRouter().getName(),
                p.getCreatedAt()
        );
    }
}
