package com.wifibilling.service;

import com.wifibilling.dto.AuthRequest;
import com.wifibilling.dto.AuthResponse;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final OtpService otpService;

    @Transactional(readOnly = true)
    public AuthResponse login(AuthRequest req) {
        String email = req.email().trim().toLowerCase();
        Tenant tenant = tenantRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if ("DISABLED".equalsIgnoreCase(tenant.getStatus()) || "SUSPENDED".equalsIgnoreCase(tenant.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is " + tenant.getStatus().toLowerCase() + ". Please contact administrator.");
        }

        if (!passwordEncoder.matches(req.password(), tenant.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        String token = jwtService.generateToken(tenant.getId(), tenant.getEmail(), tenant.getRole());
        return new AuthResponse(token, tenant.getId(), tenant.getName(), tenant.getEmail(), tenant.getRole());
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
        tenantRepository.save(tenant);
    }
}
