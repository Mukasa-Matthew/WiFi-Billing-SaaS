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
                .build();

        plan = packagePlanRepository.save(plan);
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

    private PackagePlanDto.Response toResponse(PackagePlan p) {
        return new PackagePlanDto.Response(
                p.getId(),
                p.getName(),
                p.getPrice(),
                p.getRateLimit(),
                p.getSessionTimeout(),
                p.getSharedUsers(),
                p.getCreatedAt()
        );
    }
}
