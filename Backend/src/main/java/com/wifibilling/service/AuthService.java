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
}
