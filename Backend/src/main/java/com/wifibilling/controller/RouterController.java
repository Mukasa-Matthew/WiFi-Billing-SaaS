package com.wifibilling.controller;

import com.wifibilling.dto.RouterDto;
import com.wifibilling.model.Router;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.repository.NasRepository;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.WireGuardPeerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/routers")
@RequiredArgsConstructor
public class RouterController {

    private final RouterRepository routerRepository;
    private final com.wifibilling.repository.IpBindingRepository bindingRepository;
    private final com.wifibilling.repository.RouterTaskRepository taskRepository;
    private final com.wifibilling.service.WireGuardIpAllocator ipAllocator;
    private final TenantRepository tenantRepository;
    private final TransactionRepository transactionRepository;
    private final NasRepository nasRepository;
    private final WireGuardPeerService wireGuardPeerService;

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
        String wanInterface = valueOrDefault(req.wanInterface(), "ether1");
        // Empty list = auto mode: the provisioning script bridges every ethernet
        // port except the WAN uplink, so any router model works without config.
        List<String> hotspotInterfaces = req.hotspotInterfaces() == null || req.hotspotInterfaces().isEmpty()
                ? List.of()
                : new LinkedHashSet<>(req.hotspotInterfaces()).stream().toList();
        if (hotspotInterfaces.contains(wanInterface)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "WAN interface cannot also be a hotspot interface");
        }
        Router router = Router.builder()
                .tenant(tenant)
                .name(req.name().trim())
                .token(token)
                .wgIp(ipAllocator.allocate())
                .radiusSecret("")
                .wanInterface(wanInterface)
                .hotspotInterfaces(String.join(",", hotspotInterfaces))
                .wifiSsid(valueOrDefault(req.wifiSsid(), "WiFi Hotspot"))
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

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable Long id) {
        Router router = routerRepository.findByIdAndTenantId(id, principal.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found"));
        if (transactionRepository.existsByRouterId(router.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Router has payment history and cannot be deleted");
        }

        if (bindingRepository.existsByRouterId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Router has binding history and cannot be deleted");
        }
        taskRepository.deleteAllByRouterId(id);
        String publicKey = router.getWgPublicKey();
        if (router.getWgIp() != null && !router.getWgIp().isBlank()) {
            nasRepository.deleteByNasname(router.getWgIp());
        }
        routerRepository.delete(router);
        routerRepository.flush();

        if (publicKey != null && !publicKey.isBlank()) {
            wireGuardPeerService.removePeer(publicKey);
        }
    }

    private RouterDto.Response toResponse(Router r) {
        return new RouterDto.Response(
                r.getId(),
                r.getName(),
                r.getToken(),
                r.getWgIp(),
                r.getWgPublicKey(),
                valueOrDefault(r.getWanInterface(), "ether1"),
                hotspotInterfaces(r.getHotspotInterfaces()),
                valueOrDefault(r.getWifiSsid(), "WiFi Hotspot"),
                r.getStatus(),
                r.getCpuLoad(),
                r.getMemoryUsage(),
                r.getUptime(),
                r.getActiveUsers(),
                r.getLastSeen(),
                r.getCreatedAt()
        );
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static List<String> hotspotInterfaces(String value) {
        return value == null || value.isBlank()
                ? List.of()
                : List.of(value.split(","));
    }
}
