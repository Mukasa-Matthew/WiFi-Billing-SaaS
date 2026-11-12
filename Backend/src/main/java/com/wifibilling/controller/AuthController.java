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

    public record RegisterRequest(
            @jakarta.validation.constraints.NotBlank String name,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Email String email,
            @jakarta.validation.constraints.NotBlank String phone,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min = 8) String password) {}
    public record RegisterVerifyRequest(
            @jakarta.validation.constraints.NotBlank String email,
            @jakarta.validation.constraints.NotBlank String code) {}
    public record RegisterResendRequest(@jakarta.validation.constraints.NotBlank String email) {}
    public record ForgotRequest(String email) {}
    public record ResetRequest(String email, String code, String newPassword) {}

    /**
     * Self-service signup for hotspot owners. Creates an unverified account and
     * emails a 6-digit code — the owner cannot sign in until it is verified.
     */
    @PostMapping("/register")
    public Map<String, String> register(@Valid @RequestBody RegisterRequest req) {
        authService.register(req.name(), req.email(), req.phone(), req.password());
        return Map.of("message", "We sent a 6-digit verification code to " + req.email().trim() + ".");
    }

    /** Verifies the registration OTP; returns a normal login response. */
    @PostMapping("/register/verify")
    public AuthResponse verifyRegistration(@Valid @RequestBody RegisterVerifyRequest req, HttpServletRequest servletRequest) {
        return authService.verifyRegistration(req.email(), req.code(), servletRequest);
    }

    /** Always returns OK — never reveals whether the email is registered. */
    @PostMapping("/register/resend")
    public Map<String, String> resendRegistration(@Valid @RequestBody RegisterResendRequest req) {
        authService.resendRegistrationCode(req.email());
        return Map.of("message", "If that email has an unverified account, a new code is on its way.");
    }
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
