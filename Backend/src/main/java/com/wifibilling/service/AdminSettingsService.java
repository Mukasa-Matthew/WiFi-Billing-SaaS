package com.wifibilling.service;

import com.wifibilling.dto.AdminSettingsDto;
import com.wifibilling.model.AuditLog;
import com.wifibilling.model.EmailOtp;
import com.wifibilling.model.PlatformSetting;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.UserSession;
import com.wifibilling.repository.AuditLogRepository;
import com.wifibilling.repository.PlatformSettingRepository;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.repository.UserSessionRepository;
import com.wifibilling.security.TenantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSettingsService {

    private final TenantRepository tenantRepository;
    private final UserSessionRepository userSessionRepository;
    private final AuditLogRepository auditLogRepository;
    private final PlatformSettingRepository platformSettingRepository;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;
    private final EncryptionService encryptionService;
    private final OtpService otpService;

    @Transactional(readOnly = true)
    public AdminSettingsDto.ProfileResponse getProfile(TenantPrincipal principal) {
        Tenant tenant = getSuperAdmin(principal);
        return new AdminSettingsDto.ProfileResponse(
                tenant.getId(),
                tenant.getName(),
                tenant.getEmail(),
                tenant.getPhone(),
                tenant.getRole(),
                tenant.getStatus(),
                Boolean.TRUE.equals(tenant.getTotpEnabled()),
                tenant.getCreatedAt(),
                tenant.getLastLoginAt(),
                tenant.getLastLoginIp()
        );
    }

    @Transactional
    public AdminSettingsDto.ProfileResponse updateProfile(TenantPrincipal principal, AdminSettingsDto.UpdateProfileRequest req, HttpServletRequest request) {
        Tenant tenant = getSuperAdmin(principal);
        String oldName = tenant.getName();
        tenant.setName(req.name().trim());
        tenantRepository.save(tenant);

        recordAudit(tenant, "PROFILE_UPDATED", "Name updated from '" + oldName + "' to '" + tenant.getName() + "'", request);

        return getProfile(principal);
    }

    // =========================================================================
    // EMAIL CHANGE WITH DUAL OTP (Current Email + New Email)
    // =========================================================================

    @Transactional
    public Map<String, String> requestEmailChange(TenantPrincipal principal, AdminSettingsDto.RequestEmailChangeRequest req, HttpServletRequest request) {
        Tenant tenant = getSuperAdmin(principal);
        validatePassword(tenant, req.currentPassword());

        String targetEmail = req.newEmail().trim().toLowerCase();
        if (targetEmail.equalsIgnoreCase(tenant.getEmail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New email cannot be the same as current email");
        }
        if (tenantRepository.existsByEmailIgnoreCase(targetEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This email is already associated with another account");
        }

        tenant.setPendingNewEmail(targetEmail);
        tenantRepository.save(tenant);

        // 1. Send verification OTP to current email
        otpService.issue(tenant.getEmail(), EmailOtp.PURPOSE_EMAIL_CHANGE + "_CURRENT",
                tenant.getId(), tenant.getName(), "Verify your request to change account email to " + targetEmail);

        // 2. Send verification OTP to new email
        otpService.issue(targetEmail, EmailOtp.PURPOSE_EMAIL_CHANGE + "_NEW",
                tenant.getId(), tenant.getName(), "Verify ownership of your new AirPairy email address");

        recordAudit(tenant, "EMAIL_CHANGE_REQUESTED", "Initiated email change towards: " + targetEmail, request);

        return Map.of("message", "Verification codes have been dispatched to both your current email and new email address.");
    }

    @Transactional
    public AdminSettingsDto.ProfileResponse verifyEmailChange(TenantPrincipal principal, AdminSettingsDto.VerifyEmailChangeRequest req, HttpServletRequest request) {
        Tenant tenant = getSuperAdmin(principal);
        String targetEmail = tenant.getPendingNewEmail();
        if (targetEmail == null || targetEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No email change request pending");
        }

        // Verify current email code
        otpService.verify(tenant.getEmail(), EmailOtp.PURPOSE_EMAIL_CHANGE + "_CURRENT", req.currentEmailCode());

        // Verify new email code
        otpService.verify(targetEmail, EmailOtp.PURPOSE_EMAIL_CHANGE + "_NEW", req.newEmailCode());

        String oldEmail = tenant.getEmail();
        tenant.setEmail(targetEmail);
        tenant.setPendingNewEmail(null);
        tenantRepository.save(tenant);

        recordAudit(tenant, "EMAIL_CHANGED", "Email changed from " + oldEmail + " to " + targetEmail, request);

        return getProfile(principal);
    }

    // =========================================================================
    // PHONE CHANGE WITH OTP
    // =========================================================================

    @Transactional
    public Map<String, String> requestPhoneChange(TenantPrincipal principal, AdminSettingsDto.RequestPhoneChangeRequest req, HttpServletRequest request) {
        Tenant tenant = getSuperAdmin(principal);
        validatePassword(tenant, req.currentPassword());

        String newPhone = req.newPhone().trim();
        tenant.setPendingNewPhone(newPhone);
        tenantRepository.save(tenant);

        // Send OTP confirmation to registered email (since SMS integration is pending)
        otpService.issue(tenant.getEmail(), "PHONE_CHANGE",
                tenant.getId(), tenant.getName(), "Authorize changing your security phone number to " + newPhone);

        recordAudit(tenant, "PHONE_CHANGE_REQUESTED", "Initiated phone update to: " + newPhone, request);

        return Map.of("message", "Security verification code sent to your registered email to authorize phone update.");
    }

    @Transactional
    public AdminSettingsDto.ProfileResponse verifyPhoneChange(TenantPrincipal principal, AdminSettingsDto.VerifyPhoneChangeRequest req, HttpServletRequest request) {
        Tenant tenant = getSuperAdmin(principal);
        String newPhone = tenant.getPendingNewPhone();
        if (newPhone == null || newPhone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No phone change request pending");
        }

        otpService.verify(tenant.getEmail(), "PHONE_CHANGE", req.code());

        String oldPhone = tenant.getPhone();
        tenant.setPhone(newPhone);
        tenant.setPendingNewPhone(null);
        tenantRepository.save(tenant);

        recordAudit(tenant, "PHONE_CHANGED", "Phone changed from " + oldPhone + " to " + newPhone, request);

        return getProfile(principal);
    }

    // =========================================================================
    // TWO-FACTOR AUTHENTICATION (TOTP)
    // =========================================================================

    @Transactional(readOnly = true)
    public AdminSettingsDto.TwoFactorSetupResponse setupTwoFactor(TenantPrincipal principal) {
        Tenant tenant = getSuperAdmin(principal);
        String secret = totpService.generateSecret();
        String uri = totpService.getOtpAuthUri("AirPairy", tenant.getEmail(), secret);
        return new AdminSettingsDto.TwoFactorSetupResponse(secret, uri, secret);
    }

    @Transactional
    public AdminSettingsDto.TwoFactorActivateResponse activateTwoFactor(TenantPrincipal principal, AdminSettingsDto.TwoFactorActivateRequest req, HttpServletRequest request) {
        Tenant tenant = getSuperAdmin(principal);
        validatePassword(tenant, req.currentPassword());

        if (!totpService.verifyCode(req.secret(), req.code())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid 6-digit authenticator code. Check your device clock.");
        }

        // Encrypt secret with server master key
        String encryptedSecret = encryptionService.encrypt(req.secret());
        tenant.setTotpSecret(encryptedSecret);
        tenant.setTotpEnabled(true);

        // Generate emergency recovery codes
        List<String> recoveryCodes = totpService.generateRecoveryCodes();
        List<String> hashedCodes = recoveryCodes.stream().map(passwordEncoder::encode).toList();
        tenant.setRecoveryCodesHash(String.join(",", hashedCodes));

        tenantRepository.save(tenant);

        recordAudit(tenant, "2FA_ENABLED", "Two-factor authentication activated using Authenticator app", request);

        return new AdminSettingsDto.TwoFactorActivateResponse(recoveryCodes, "Two-factor authentication is now active.");
    }

    @Transactional
    public Map<String, String> disableTwoFactor(TenantPrincipal principal, AdminSettingsDto.TwoFactorDisableRequest req, HttpServletRequest request) {
        Tenant tenant = getSuperAdmin(principal);
        validatePassword(tenant, req.currentPassword());

        if (!Boolean.TRUE.equals(tenant.getTotpEnabled())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Two-factor authentication is already disabled");
        }

        // Validate code using TOTP or recovery code
        String code = req.code().trim().toUpperCase().replaceAll("\\s+", "");
        boolean verified = false;

        if (code.matches("^\\d{6}$")) {
            String decrypted = encryptionService.decrypt(tenant.getTotpSecret());
            if (totpService.verifyCode(decrypted, code)) verified = true;
        }

        if (!verified && tenant.getRecoveryCodesHash() != null) {
            for (String hash : tenant.getRecoveryCodesHash().split(",")) {
                if (passwordEncoder.matches(code, hash)) {
                    verified = true;
                    break;
                }
            }
        }

        if (!verified) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid authenticator code or recovery code");
        }

        tenant.setTotpEnabled(false);
        tenant.setTotpSecret(null);
        tenant.setRecoveryCodesHash(null);
        tenantRepository.save(tenant);

        recordAudit(tenant, "2FA_DISABLED", "Two-factor authentication disabled", request);

        return Map.of("message", "Two-factor authentication has been disabled.");
    }

    @Transactional
    public List<String> regenerateRecoveryCodes(TenantPrincipal principal, AdminSettingsDto.RegenerateRecoveryCodesRequest req, HttpServletRequest request) {
        Tenant tenant = getSuperAdmin(principal);
        validatePassword(tenant, req.currentPassword());

        if (!Boolean.TRUE.equals(tenant.getTotpEnabled())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Two-factor authentication must be enabled to generate recovery codes");
        }

        String decrypted = encryptionService.decrypt(tenant.getTotpSecret());
        if (!totpService.verifyCode(decrypted, req.code())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid 6-digit authenticator code");
        }

        List<String> recoveryCodes = totpService.generateRecoveryCodes();
        List<String> hashedCodes = recoveryCodes.stream().map(passwordEncoder::encode).toList();
        tenant.setRecoveryCodesHash(String.join(",", hashedCodes));
        tenantRepository.save(tenant);

        recordAudit(tenant, "RECOVERY_CODES_REGENERATED", "Emergency recovery codes regenerated; previous codes invalidated.", request);

        return recoveryCodes;
    }

    // =========================================================================
    // ACTIVE SESSIONS MANAGEMENT
    // =========================================================================

    @Transactional(readOnly = true)
    public List<AdminSettingsDto.SessionResponse> getActiveSessions(TenantPrincipal principal) {
        Tenant tenant = getSuperAdmin(principal);
        List<UserSession> sessions = userSessionRepository.findAllByTenantIdAndRevokedFalseOrderByLastActiveAtDesc(tenant.getId());
        String currentSid = principal.getSessionId();

        return sessions.stream().map(s -> new AdminSettingsDto.SessionResponse(
                s.getSessionId(),
                s.getIpAddress(),
                s.getUserAgent(),
                Objects.equals(s.getSessionId(), currentSid),
                s.getCreatedAt(),
                s.getLastActiveAt()
        )).toList();
    }

    @Transactional
    public Map<String, String> revokeSession(TenantPrincipal principal, String sessionId, HttpServletRequest request) {
        Tenant tenant = getSuperAdmin(principal);
        userSessionRepository.findBySessionId(sessionId).ifPresent(s -> {
            if (s.getTenant().getId().equals(tenant.getId())) {
                s.setRevoked(true);
                userSessionRepository.save(s);
            }
        });
        recordAudit(tenant, "SESSION_REVOKED", "Session " + sessionId.substring(0, Math.min(8, sessionId.length())) + "... revoked", request);
        return Map.of("message", "Session revoked.");
    }

    @Transactional
    public Map<String, String> revokeAllOtherSessions(TenantPrincipal principal, HttpServletRequest request) {
        Tenant tenant = getSuperAdmin(principal);
        if (principal.getSessionId() != null) {
            userSessionRepository.revokeAllExcept(tenant.getId(), principal.getSessionId());
        }
        recordAudit(tenant, "ALL_OTHER_SESSIONS_REVOKED", "All other active sessions revoked", request);
        return Map.of("message", "All other devices signed out.");
    }

    // =========================================================================
    // PLATFORM SETTINGS
    // =========================================================================

    @Transactional(readOnly = true)
    public Map<String, String> getPlatformSettings() {
        Map<String, String> map = new LinkedHashMap<>();
        // Sensible defaults
        map.put("platform_name", "AirPairy");
        map.put("tagline", "Connecting WiFi. Simplifying Business.");
        map.put("parent_brand", "Thewton");
        map.put("support_email", "support@airpairy.com");
        map.put("support_phone", "+256 700 000000");
        map.put("default_subscription_fee", "50000");
        map.put("currency", "UGX");
        map.put("timezone", "Africa/Kampala");
        map.put("marzpay_mode", "LIVE");
        map.put("security_alerts_enabled", "true");
        map.put("new_tenant_alerts_enabled", "true");
        map.put("router_offline_alerts_enabled", "true");

        platformSettingRepository.findAll().forEach(p -> map.put(p.getKey(), p.getValue()));
        return map;
    }

    @Transactional
    public Map<String, String> updatePlatformSettings(TenantPrincipal principal, Map<String, String> updates, HttpServletRequest request) {
        Tenant tenant = getSuperAdmin(principal);
        updates.forEach((k, v) -> {
            if (k != null && !k.isBlank() && v != null) {
                PlatformSetting setting = platformSettingRepository.findById(k.trim())
                        .orElse(PlatformSetting.builder().key(k.trim()).value(v.trim()).build());
                setting.setValue(v.trim());
                platformSettingRepository.save(setting);
            }
        });
        recordAudit(tenant, "PLATFORM_SETTINGS_UPDATED", "Updated keys: " + updates.keySet(), request);
        return getPlatformSettings();
    }

    // =========================================================================
    // AUDIT LOGS
    // =========================================================================

    @Transactional(readOnly = true)
    public List<AdminSettingsDto.AuditLogResponse> getAuditLogs(TenantPrincipal principal) {
        Tenant tenant = getSuperAdmin(principal);
        return auditLogRepository.findTop50ByTenantIdOrderByCreatedAtDesc(tenant.getId()).stream()
                .map(a -> new AdminSettingsDto.AuditLogResponse(
                        a.getId(),
                        a.getAction(),
                        a.getActorEmail(),
                        a.getIpAddress(),
                        a.getDetails(),
                        a.getCreatedAt()
                )).toList();
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private Tenant getSuperAdmin(TenantPrincipal principal) {
        if (principal == null || !"ROLE_SUPERADMIN".equals(principal.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Super Admin privileges required");
        }
        return tenantRepository.findById(principal.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Super Admin account not found"));
    }

    private void validatePassword(Tenant tenant, String password) {
        if (password == null || !passwordEncoder.matches(password, tenant.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password verification failed");
        }
    }

    private void recordAudit(Tenant tenant, String action, String details, HttpServletRequest request) {
        auditLogRepository.save(AuditLog.builder()
                .tenantId(tenant.getId())
                .action(action)
                .actorEmail(tenant.getEmail())
                .ipAddress(AuthService.extractClientIp(request))
                .details(details)
                .build());
    }
}
