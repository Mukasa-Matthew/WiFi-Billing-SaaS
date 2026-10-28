package com.wifibilling.controller;

import com.wifibilling.dto.RouterDto;
import com.wifibilling.model.Router;
import com.wifibilling.model.RouterSetupCode;
import com.wifibilling.service.RouterSetupService;
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
    private final RouterSetupService setupService;
    private final com.wifibilling.repository.RouterSetupCodeRepository setupCodeRepository;
    private final com.wifibilling.service.SubscriptionService subscriptionService;
    private final com.wifibilling.service.WinboxAccessService winboxAccess;

    @GetMapping
    public List<RouterDto.Response> list(@AuthenticationPrincipal TenantPrincipal principal) {
        return routerRepository.findAllByTenantIdAndStatusNot(principal.getTenantId(), "DELETED").stream()
                .map(this::toResponse)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RouterDto.Response create(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody RouterDto.CreateRequest req) {
        if (subscriptionService != null) {
            subscriptionService.assertTenantManagementAllowed(principal.getTenantId());
        }
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

    /**
     * Issues a one-time setup code for one of the caller's own routers. FULL
     * releases the provisioning script (with credentials) once; MANAGEMENT
     * releases only the management-agent installer. Issuing revokes any
     * earlier unfinished code for the router.
     */
    @PostMapping("/{id}/setup-code")
    public RouterDto.SetupCode setupCode(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable Long id,
            @RequestBody(required = false) RouterDto.SetupCodeRequest req) {
        Router router = ownRouter(principal, id);
        RouterSetupCode.Purpose purpose = req != null && "MANAGEMENT".equalsIgnoreCase(req.purpose())
                ? RouterSetupCode.Purpose.MANAGEMENT : RouterSetupCode.Purpose.FULL;
        RouterSetupService.Issued issued = setupService.issue(router, purpose);
        String path = "/api/router/setup/" + issued.code()
                + (purpose == RouterSetupCode.Purpose.MANAGEMENT ? "/install-management.rsc" : "");
        return new RouterDto.SetupCode(router.getId(), purpose.name(), path, issued.expiresAt());
    }

    /** Public portal identifier for the owner's portal preview (authorizes nothing sensitive). */
    @GetMapping("/{id}/portal-id")
    public RouterDto.PortalId portalId(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable Long id) {
        Router router = ownRouter(principal, id);
        return new RouterDto.PortalId(router.getId(), router.getToken());
    }

    private Router ownRouter(TenantPrincipal principal, Long id) {
        return routerRepository.findByIdAndTenantId(id, principal.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found"));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable Long id) {
        Router router = routerRepository.findByIdAndTenantId(id, principal.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found"));

        winboxAccess.change(principal, id, "disable", null);
        taskRepository.deleteAllByRouterId(id);
        setupCodeRepository.deleteAllByRouterId(id);
        String publicKey = router.getWgPublicKey();
        if (router.getWgIp() != null && !router.getWgIp().isBlank()) {
            nasRepository.deleteByNasname(router.getWgIp());
        }

        if (publicKey != null && !publicKey.isBlank()) {
            wireGuardPeerService.removePeer(publicKey);
        }

        // If payments, bindings, or packages exist, soft-delete so financial history remains valid
        if (transactionRepository.existsByRouterId(router.getId()) || bindingRepository.existsByRouterId(id)) {
            router.setStatus("DELETED");
            router.setWgPublicKey(null);
            router.setCpuLoad(0);
            router.setMemoryUsage(0);
            router.setActiveUsers(0);
            routerRepository.save(router);
        } else {
            routerRepository.delete(router);
            routerRepository.flush();
        }
    }

    private RouterDto.Response toResponse(Router r) {
        return new RouterDto.Response(
                r.getId(),
                r.getName(),
                r.getWgIp(),
                r.getWgPublicKey() != null && !r.getWgPublicKey().isBlank(),
                valueOrDefault(r.getWanInterface(), "ether1"),
                hotspotInterfaces(r.getHotspotInterfaces()),
                valueOrDefault(r.getWifiSsid(), "WiFi Hotspot"),
                r.getStatus(),
                r.getCpuLoad(),
                r.getMemoryUsage(),
                r.getUptime(),
                r.getActiveUsers(),
                r.getHotspotReady(),
                r.getRadiusReady(),
                r.getPortalReady(),
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
