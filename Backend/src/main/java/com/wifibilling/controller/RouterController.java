package com.wifibilling.controller;

import com.wifibilling.dto.RouterDto;
import com.wifibilling.model.Router;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.security.TenantPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/routers")
@RequiredArgsConstructor
public class RouterController {

    private final RouterRepository routerRepository;
    private final TenantRepository tenantRepository;

    @GetMapping
    public List<RouterDto.Response> list(@AuthenticationPrincipal TenantPrincipal principal) {
        return routerRepository.findAllByTenantId(principal.getTenantId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RouterDto.Response create(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody RouterDto.CreateRequest req) {
        Tenant tenant = tenantRepository.findById(principal.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));

        String token = "tok_" + UUID.randomUUID().toString().replace("-", "");
        Router router = Router.builder()
                .tenant(tenant)
                .name(req.name().trim())
                .token(token)
                .wgIp("")
                .radiusSecret("")
                .status("pending")
                .build();

        router = routerRepository.save(router);
        return toResponse(router);
    }

    @GetMapping("/{id}")
    public RouterDto.Response get(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable Long id) {
        Router router = routerRepository.findById(id)
                .filter(r -> r.getTenant().getId().equals(principal.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found"));
        return toResponse(router);
    }

    private RouterDto.Response toResponse(Router r) {
        return new RouterDto.Response(
                r.getId(),
                r.getName(),
                r.getToken(),
                r.getWgIp(),
                r.getWgPublicKey(),
                r.getStatus(),
                r.getCpuLoad(),
                r.getMemoryUsage(),
                r.getUptime(),
                r.getActiveUsers(),
                r.getLastSeen(),
                r.getCreatedAt()
        );
    }
}
