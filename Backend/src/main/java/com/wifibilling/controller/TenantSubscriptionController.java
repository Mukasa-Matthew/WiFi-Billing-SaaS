package com.wifibilling.controller;

import com.wifibilling.dto.SubscriptionDto;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Tenant-facing subscription management controller.
 * Supports viewing current plan/trial/grace status, viewing billing history,
 * in-app notifications, and self-service Mobile Money subscription checkout.
 */
@RestController
@RequestMapping("/api/subscription")
@RequiredArgsConstructor
public class TenantSubscriptionController {

    private final SubscriptionService service;

    @GetMapping
    public SubscriptionDto.TenantView view(@AuthenticationPrincipal TenantPrincipal p) {
        return service.buildTenantView(p.getTenantId());
    }

    /**
     * Tenant initiates a Mobile Money subscription payment.
     * Guaranteed to use the SaaS platform merchant gateway.
     */
    @PostMapping("/checkout")
    public SubscriptionDto.CheckoutResponse checkout(
            @AuthenticationPrincipal TenantPrincipal p,
            @Valid @RequestBody SubscriptionDto.CheckoutRequest req) {
        return service.initiateSubscriptionCheckout(p.getTenantId(), req);
    }

    /** Outcome of one checkout — confirmed with the provider while it is still pending. */
    @GetMapping("/checkout/{reference}")
    public SubscriptionDto.CheckoutResponse checkoutStatus(
            @AuthenticationPrincipal TenantPrincipal p, @PathVariable String reference) {
        return service.checkoutStatus(p.getTenantId(), reference);
    }

    @PostMapping("/notifications/{id}/dismiss")
    public void dismissNotification(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id) {
        service.dismissNotification(p.getTenantId(), id);
    }
}
