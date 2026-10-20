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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<VoucherResponse> generate(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody GenerateVouchersRequest body) {
        return voucherService.generate(principal.getTenantId(), body);
    }

    @GetMapping
    public List<VoucherResponse> list(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(required = false) String status) {
        return voucherService.list(principal.getTenantId(), status);
    }

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
