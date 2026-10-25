package com.wifibilling.controller;

import com.wifibilling.dto.AdminSettingsDto;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.AdminSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
public class AdminSettingsController {

    private final AdminSettingsService settingsService;

    // Profile
    @GetMapping("/profile")
    public AdminSettingsDto.ProfileResponse getProfile(@AuthenticationPrincipal TenantPrincipal principal) {
        return settingsService.getProfile(principal);
    }

    @PutMapping("/profile")
    public AdminSettingsDto.ProfileResponse updateProfile(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody AdminSettingsDto.UpdateProfileRequest req,
            HttpServletRequest request) {
        return settingsService.updateProfile(principal, req, request);
    }

    // Email Change Flow
    @PostMapping("/email/request")
    public Map<String, String> requestEmailChange(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody AdminSettingsDto.RequestEmailChangeRequest req,
            HttpServletRequest request) {
        return settingsService.requestEmailChange(principal, req, request);
    }

    @PostMapping("/email/verify")
    public AdminSettingsDto.ProfileResponse verifyEmailChange(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody AdminSettingsDto.VerifyEmailChangeRequest req,
            HttpServletRequest request) {
        return settingsService.verifyEmailChange(principal, req, request);
    }

    // Phone Change Flow
    @PostMapping("/phone/request")
    public Map<String, String> requestPhoneChange(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody AdminSettingsDto.RequestPhoneChangeRequest req,
            HttpServletRequest request) {
        return settingsService.requestPhoneChange(principal, req, request);
    }

    @PostMapping("/phone/verify")
    public AdminSettingsDto.ProfileResponse verifyPhoneChange(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody AdminSettingsDto.VerifyPhoneChangeRequest req,
            HttpServletRequest request) {
        return settingsService.verifyPhoneChange(principal, req, request);
    }

    // Two-Factor Authentication (TOTP)
    @PostMapping("/2fa/setup")
    public AdminSettingsDto.TwoFactorSetupResponse setupTwoFactor(
            @AuthenticationPrincipal TenantPrincipal principal) {
        return settingsService.setupTwoFactor(principal);
    }

    @PostMapping("/2fa/activate")
    public AdminSettingsDto.TwoFactorActivateResponse activateTwoFactor(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody AdminSettingsDto.TwoFactorActivateRequest req,
            HttpServletRequest request) {
        return settingsService.activateTwoFactor(principal, req, request);
    }

    @PostMapping("/2fa/disable")
    public Map<String, String> disableTwoFactor(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody AdminSettingsDto.TwoFactorDisableRequest req,
            HttpServletRequest request) {
        return settingsService.disableTwoFactor(principal, req, request);
    }

    @PostMapping("/2fa/recovery-codes")
    public List<String> regenerateRecoveryCodes(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody AdminSettingsDto.RegenerateRecoveryCodesRequest req,
            HttpServletRequest request) {
        return settingsService.regenerateRecoveryCodes(principal, req, request);
    }

    // Sessions
    @GetMapping("/sessions")
    public List<AdminSettingsDto.SessionResponse> getActiveSessions(
            @AuthenticationPrincipal TenantPrincipal principal) {
        return settingsService.getActiveSessions(principal);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, String> revokeSession(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String sessionId,
            HttpServletRequest request) {
        return settingsService.revokeSession(principal, sessionId, request);
    }

    @PostMapping("/sessions/revoke-others")
    public Map<String, String> revokeAllOtherSessions(
            @AuthenticationPrincipal TenantPrincipal principal,
            HttpServletRequest request) {
        return settingsService.revokeAllOtherSessions(principal, request);
    }

    // Platform Settings
    @GetMapping("/platform")
    public Map<String, String> getPlatformSettings() {
        return settingsService.getPlatformSettings();
    }

    @PutMapping("/platform")
    public Map<String, String> updatePlatformSettings(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestBody Map<String, String> updates,
            HttpServletRequest request) {
        return settingsService.updatePlatformSettings(principal, updates, request);
    }

    // Audit Logs
    @GetMapping("/audit-logs")
    public List<AdminSettingsDto.AuditLogResponse> getAuditLogs(
            @AuthenticationPrincipal TenantPrincipal principal) {
        return settingsService.getAuditLogs(principal);
    }
}
