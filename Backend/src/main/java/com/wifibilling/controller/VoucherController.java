package com.wifibilling.controller;

import com.wifibilling.dto.GenerateVouchersRequest;
import com.wifibilling.dto.VoucherResponse;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.VoucherService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vouchers")
@RequiredArgsConstructor
public class VoucherController {

    private final VoucherService voucherService;
    private final com.wifibilling.service.SubscriptionService subscriptionService;

    public record SaleRequest(@jakarta.validation.constraints.NotNull Long routerId,
        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin("0.01") java.math.BigDecimal amount,
        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp="CASH|MTN|AIRTEL|BANK|OTHER") String method,
        @jakarta.validation.constraints.Size(max=128) String reference,
        @jakarta.validation.constraints.Size(max=1000) String notes) {}
    @PostMapping("/{code}/sale")
    public void sell(@AuthenticationPrincipal TenantPrincipal p,@PathVariable String code,@Valid @RequestBody SaleRequest req){
        voucherService.sellWalkIn(p.getTenantId(),code,req.routerId(),req.amount(),req.method(),req.reference(),req.notes());
    }


    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<VoucherResponse> generate(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody GenerateVouchersRequest body) {
        if (subscriptionService != null) {
            subscriptionService.assertTenantManagementAllowed(principal.getTenantId());
        }
        return voucherService.generate(principal.getTenantId(), body);
    }

    @GetMapping
    public List<VoucherResponse> list(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(required = false) String status) {
        return voucherService.list(principal.getTenantId(), status);
    }

    public record AssignmentRequest(@jakarta.validation.constraints.NotNull Long customerId) {}
    public record BulkAssignmentRequest(@jakarta.validation.constraints.NotEmpty @jakarta.validation.constraints.Size(max=500) List<String> codes,
            @jakarta.validation.constraints.NotNull Long customerId) {}

    @PostMapping("/assign")
    public void assign(@AuthenticationPrincipal TenantPrincipal p,@Valid @RequestBody BulkAssignmentRequest r) {
        voucherService.assign(p.getTenantId(),r.codes(),r.customerId());
    }
    @PostMapping("/{code}/archive")
    public void archive(@AuthenticationPrincipal TenantPrincipal p,@PathVariable String code) { voucherService.archive(p.getTenantId(),code); }

    @GetMapping("/{code}")
    public VoucherResponse get(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String code) {
        return voucherService.get(principal.getTenantId(), code);
    }

    @PostMapping("/{code}/revoke")
    public VoucherResponse revoke(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String code) {
        return voucherService.revoke(principal.getTenantId(), code);
    }
}
