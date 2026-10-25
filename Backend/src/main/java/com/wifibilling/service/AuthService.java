package com.wifibilling.service;

import com.wifibilling.dto.AuthRequest;
import com.wifibilling.dto.AuthResponse;
import com.wifibilling.dto.TwoFactorVerifyRequest;
import com.wifibilling.model.AuditLog;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.UserSession;
import com.wifibilling.repository.AuditLogRepository;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.repository.UserSessionRepository;
import com.wifibilling.security.JwtService;
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
public class AuthService {

    private final TenantRepository tenantRepository;
    private final UserSessionRepository userSessionRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final OtpService otpService;
    private final TotpService totpService;
    private final EncryptionService encryptionService;

    @Transactional
    public AuthResponse login(AuthRequest req, HttpServletRequest servletRequest) {
        String email = req.email().trim().toLowerCase();
        Tenant tenant = tenantRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if ("DISABLED".equalsIgnoreCase(tenant.getStatus()) || "SUSPENDED".equalsIgnoreCase(tenant.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is " + tenant.getStatus().toLowerCase() + ". Please contact administrator.");
        }

        if (!passwordEncoder.matches(req.password(), tenant.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        // Check if Two-Factor Authentication is enabled
        if (Boolean.TRUE.equals(tenant.getTotpEnabled())) {
            String tempToken = jwtService.generate2faChallengeToken(tenant.getId(), tenant.getEmail());
            return AuthResponse.twoFactorRequired(tempToken);
        }

        return createAuthenticatedSession(tenant, servletRequest);
    }

    @Transactional
    public AuthResponse verifyTwoFactorLogin(TwoFactorVerifyRequest req, HttpServletRequest servletRequest) {
        String tempToken = req.tempToken();
        if (!jwtService.isTokenValid(tempToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Verification session expired. Please sign in again.");
        }

        String purpose = jwtService.extractPurpose(tempToken);
        if (!"2FA_CHALLENGE".equalsIgnoreCase(purpose)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid verification token");
        }

        Long tenantId = jwtService.extractTenantId(tempToken);
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account not found"));

        if (!Boolean.TRUE.equals(tenant.getTotpEnabled())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Two-factor authentication is not active for this account");
        }

        String code = req.code().trim().toUpperCase().replaceAll("\\s+", "");
        boolean verified = false;
        boolean usedRecoveryCode = false;

        // 1. Try TOTP code first
        if (code.matches("^\\d{6}$")) {
            String decryptedSecret = encryptionService.decrypt(tenant.getTotpSecret());
            if (totpService.verifyCode(decryptedSecret, code)) {
                verified = true;
            }
        }

        // 2. If not verified as TOTP, try recovery code
        if (!verified && tenant.getRecoveryCodesHash() != null && !tenant.getRecoveryCodesHash().isBlank()) {
            List<String> storedHashes = new ArrayList<>(Arrays.asList(tenant.getRecoveryCodesHash().split(",")));
            Iterator<String> iterator = storedHashes.iterator();
            while (iterator.hasNext()) {
                String hash = iterator.next();
                if (passwordEncoder.matches(code, hash)) {
                    iterator.remove();
                    tenant.setRecoveryCodesHash(String.join(",", storedHashes));
                    tenantRepository.save(tenant);
                    verified = true;
                    usedRecoveryCode = true;
                    auditLogRepository.save(AuditLog.builder()
                            .tenantId(tenant.getId())
                            .action("RECOVERY_CODE_USED")
                            .actorEmail(tenant.getEmail())
                            .ipAddress(extractClientIp(servletRequest))
                            .details("Super Admin authenticated using a one-time recovery code. Remaining codes: " + storedHashes.size())
                            .build());
                    break;
                }
            }
        }

        if (!verified) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid verification code or recovery code");
        }

        return createAuthenticatedSession(tenant, servletRequest);
    }

    private AuthResponse createAuthenticatedSession(Tenant tenant, HttpServletRequest servletRequest) {
        String sessionId = UUID.randomUUID().toString();
        String ip = extractClientIp(servletRequest);
        String userAgent = servletRequest != null ? servletRequest.getHeader("User-Agent") : null;

        UserSession session = UserSession.builder()
                .sessionId(sessionId)
                .tenant(tenant)
                .ipAddress(ip)
                .userAgent(userAgent != null && userAgent.length() > 300 ? userAgent.substring(0, 300) : userAgent)
                .revoked(false)
                .lastActiveAt(OffsetDateTime.now())
                .build();
        userSessionRepository.save(session);

        tenant.setLastLoginAt(OffsetDateTime.now());
        tenant.setLastLoginIp(ip);
        tenantRepository.save(tenant);

        String token = jwtService.generateToken(tenant.getId(), tenant.getEmail(), tenant.getRole(), sessionId);
        return new AuthResponse(token, tenant.getId(), tenant.getName(), tenant.getEmail(), tenant.getRole(),
                Boolean.TRUE.equals(tenant.getMustChangePassword()));
    }

    /**
     * Sends a reset code if the account exists. Always succeeds — callers cannot
     * distinguish registered from unregistered emails.
     */
    @Transactional
    public void forgotPassword(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        tenantRepository.findByEmailIgnoreCase(normalized).ifPresent(tenant -> {
            if (!"DISABLED".equalsIgnoreCase(tenant.getStatus())
                    && !"SUSPENDED".equalsIgnoreCase(tenant.getStatus())) {
                otpService.issue(normalized, com.wifibilling.model.EmailOtp.PURPOSE_PASSWORD_RESET,
                        tenant.getId(), tenant.getName(), null);
            }
        });
    }

    @Transactional
    public void resetPassword(String email, String code, String newPassword) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        if (newPassword == null || newPassword.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters");
        }
        Tenant tenant = tenantRepository.findByEmailIgnoreCase(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired code"));
        otpService.verify(normalized, com.wifibilling.model.EmailOtp.PURPOSE_PASSWORD_RESET, code);
        tenant.setPasswordHash(passwordEncoder.encode(newPassword));
        tenant.setMustChangePassword(false);
        tenantRepository.save(tenant);

        // Revoke all existing sessions
        userSessionRepository.revokeAllForTenant(tenant.getId());
    }

    @Transactional
    public void changePassword(Long tenantId, String currentPassword, String newPassword, String currentSessionId) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters");
        }
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, tenant.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        if (passwordEncoder.matches(newPassword, tenant.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be different from the previous password");
        }
        tenant.setPasswordHash(passwordEncoder.encode(newPassword));
        tenant.setMustChangePassword(false);
        tenantRepository.save(tenant);

        // Invalidate all sessions except the current active session
        if (currentSessionId != null) {
            userSessionRepository.revokeAllExcept(tenant.getId(), currentSessionId);
        } else {
            userSessionRepository.revokeAllForTenant(tenant.getId());
        }

        auditLogRepository.save(AuditLog.builder()
                .tenantId(tenant.getId())
                .action("PASSWORD_CHANGED")
                .actorEmail(tenant.getEmail())
                .details("Password changed successfully; other active sessions revoked.")
                .build());
    }

    public static String extractClientIp(HttpServletRequest req) {
        if (req == null) return "127.0.0.1";
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = req.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return req.getRemoteAddr();
    }
}
