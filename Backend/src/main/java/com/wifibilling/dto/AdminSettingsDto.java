package com.wifibilling.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public class AdminSettingsDto {

    public record ProfileResponse(
            Long id,
            String name,
            String email,
            String phone,
            String role,
            String status,
            Boolean totpEnabled,
            OffsetDateTime createdAt,
            OffsetDateTime lastLoginAt,
            String lastLoginIp
    ) {}

    public record UpdateProfileRequest(
            @NotBlank(message = "Name is required")
            @Size(max = 120)
            String name
    ) {}

    public record RequestEmailChangeRequest(
            @NotBlank(message = "Current password is required")
            String currentPassword,

            @NotBlank(message = "New email address is required")
            @Email(message = "Valid email is required")
            String newEmail
    ) {}

    public record VerifyEmailChangeRequest(
            @NotBlank(message = "Current email OTP code is required")
            String currentEmailCode,

            @NotBlank(message = "New email OTP code is required")
            String newEmailCode
    ) {}

    public record RequestPhoneChangeRequest(
            @NotBlank(message = "Current password is required")
            String currentPassword,

            @NotBlank(message = "New phone number is required")
            @Size(max = 30)
            String newPhone
    ) {}

    public record VerifyPhoneChangeRequest(
            @NotBlank(message = "Verification code is required")
            String code
    ) {}

    public record TwoFactorSetupResponse(
            String secret,
            String qrCodeUri,
            String manualEntryKey
    ) {}

    public record TwoFactorActivateRequest(
            @NotBlank(message = "Current password is required")
            String currentPassword,

            @NotBlank(message = "Setup secret is required")
            String secret,

            @NotBlank(message = "6-digit verification code is required")
            String code
    ) {}

    public record TwoFactorActivateResponse(
            List<String> recoveryCodes,
            String message
    ) {}

    public record TwoFactorDisableRequest(
            @NotBlank(message = "Current password is required")
            String currentPassword,

            @NotBlank(message = "Verification code or recovery code is required")
            String code
    ) {}

    public record RegenerateRecoveryCodesRequest(
            @NotBlank(message = "Current password is required")
            String currentPassword,

            @NotBlank(message = "Current 6-digit TOTP code is required")
            String code
    ) {}

    public record SessionResponse(
            String sessionId,
            String ipAddress,
            String userAgent,
            boolean current,
            OffsetDateTime createdAt,
            OffsetDateTime lastActiveAt
    ) {}

    public record PlatformSettingsResponse(
            Map<String, String> settings
    ) {}

    public record UpdatePlatformSettingsRequest(
            Map<String, String> settings
    ) {}

    public record AuditLogResponse(
            Long id,
            String action,
            String actorEmail,
            String ipAddress,
            String details,
            OffsetDateTime createdAt
    ) {}
}
