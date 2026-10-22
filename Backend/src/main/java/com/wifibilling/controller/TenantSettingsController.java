package com.wifibilling.controller;

import com.wifibilling.dto.TenantSettingsDto;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.security.TenantPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class TenantSettingsController {

    private final TenantRepository tenantRepository;

    @GetMapping
    @Transactional(readOnly = true)
    public TenantSettingsDto get(@AuthenticationPrincipal TenantPrincipal principal) {
        return toDto(load(principal.getTenantId()));
    }

    @PutMapping
    @Transactional
    public TenantSettingsDto update(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody TenantSettingsDto req) {
        Tenant tenant = load(principal.getTenantId());
        if (req.name() != null && !req.name().isBlank()) tenant.setName(req.name().trim());
        tenant.setSupportPhone(req.supportPhone());
        tenant.setSupportEmail(req.supportEmail());
        if (req.brandColor() != null) tenant.setBrandColor(req.brandColor());
        tenant.setRedirectUrl(req.redirectUrl());
        if (req.voucherPrefix() != null) tenant.setVoucherPrefix(req.voucherPrefix());
        if (req.voucherCodeLength() != null) tenant.setVoucherCodeLength(req.voucherCodeLength());
        if (req.voucherCodeFormat() != null) tenant.setVoucherCodeFormat(req.voucherCodeFormat());
        if (req.portalShowPackages() != null) tenant.setPortalShowPackages(req.portalShowPackages());
        tenant.setNotifyEmails(req.notifyEmails());
        tenant.setNotifyPhones(req.notifyPhones());
        return toDto(tenantRepository.save(tenant));
    }

    private Tenant load(Long tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
    }

    private TenantSettingsDto toDto(Tenant t) {
        return new TenantSettingsDto(
                t.getName(), t.getEmail(), t.getSupportPhone(), t.getSupportEmail(),
                t.getBrandColor(), t.getRedirectUrl(), t.getVoucherPrefix(),
                t.getVoucherCodeLength(), t.getVoucherCodeFormat(),
                Boolean.TRUE.equals(t.getPortalShowPackages()),
                t.getNotifyEmails(), t.getNotifyPhones());
    }
}
