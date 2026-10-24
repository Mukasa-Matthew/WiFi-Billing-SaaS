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

    /** Queue a portal sync on every router the tenant owns; picked up by
     *  wb-management within ~1 minute so package edits reach portals fast. */
    private void syncPortals(Long tenantId) {
        for (var r : routerRepository.findAllByTenantId(tenantId)) {
            try { routerManagement.enqueue(r, "SYNC_PORTAL"); } catch (Exception ignored) {}
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
        Tenant tenant = tenantRepository.findById(principal.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));

        PackagePlan plan = PackagePlan.builder()
                .tenant(tenant)
                .name(req.name().trim())
                .price(req.price())
                .rateLimit(req.rateLimit().trim())
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
        PackagePlan plan = packagePlanRepository.findByIdAndTenantId(id, principal.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));
        packagePlanRepository.delete(plan);
        syncPortals(principal.getTenantId());
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
                p.getCreatedAt()
        );
    }
}
