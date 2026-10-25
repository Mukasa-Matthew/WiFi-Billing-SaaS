package com.wifibilling.controller;

import com.wifibilling.dto.AuthRequest;
import com.wifibilling.dto.AuthResponse;
import com.wifibilling.dto.TwoFactorVerifyRequest;
import com.wifibilling.service.AuthService;
import com.wifibilling.security.TenantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * Unified login for both Superadmin and Hotspot Admins.
     * If 2FA is active, returns requires2fa=true and a short-lived tempToken.
     */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody AuthRequest req, HttpServletRequest servletRequest) {
        return authService.login(req, servletRequest);
    }

    /**
     * Step 2 of 2FA login: verifies the 6-digit TOTP or one-time recovery code.
     */
    @PostMapping("/2fa/verify")
    public AuthResponse verifyTwoFactor(@Valid @RequestBody TwoFactorVerifyRequest req, HttpServletRequest servletRequest) {
        return authService.verifyTwoFactorLogin(req, servletRequest);
    }

    public record ForgotRequest(String email) {}
    public record ResetRequest(String email, String code, String newPassword) {}
    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    /** Always returns OK — never reveals whether the email is registered. */
    @PostMapping("/forgot-password")
    public Map<String, String> forgotPassword(@RequestBody ForgotRequest req) {
        authService.forgotPassword(req.email());
        return Map.of("message", "If that account exists, a reset code is on its way.");
    }

    @PostMapping("/reset-password")
    public Map<String, String> resetPassword(@RequestBody ResetRequest req) {
        authService.resetPassword(req.email(), req.code(), req.newPassword());
        return Map.of("message", "Password updated. You can sign in now.");
    }

    @PostMapping("/change-password")
    public Map<String, String> changePassword(@AuthenticationPrincipal TenantPrincipal principal,
                                               @RequestBody ChangePasswordRequest req) {
        if (principal == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        authService.changePassword(principal.getTenantId(), req.currentPassword(), req.newPassword(), principal.getSessionId());
        return Map.of("message", "Password changed successfully.");
    }
}
