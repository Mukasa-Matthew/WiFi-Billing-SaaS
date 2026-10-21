package com.wifibilling.config;

import com.wifibilling.model.Tenant;
import com.wifibilling.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseSeeder implements CommandLineRunner {

    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${bootstrap.admin.email:}")
    private String superadminEmail;

    @Value("${bootstrap.admin.password:}")
    private String superadminPassword;

    @Value("${bootstrap.admin.name:Platform Superadmin}")
    private String superadminName;

    @Override
    public void run(String... args) {
        if (superadminEmail.isBlank() && superadminPassword.isBlank()) {
            log.info("Superadmin bootstrap is disabled; set BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD for first-time setup");
            return;
        }
        if (superadminEmail.isBlank() || superadminPassword.length() < 12) {
            throw new IllegalStateException("Superadmin bootstrap requires an email and a password of at least 12 characters");
        }

        String normalizedEmail = superadminEmail.trim().toLowerCase();
        if (!tenantRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            Tenant superadmin = Tenant.builder()
                    .name(superadminName.trim())
                    .email(normalizedEmail)
                    .passwordHash(passwordEncoder.encode(superadminPassword))
                    .role("ROLE_SUPERADMIN")
                    .status("ACTIVE")
                    .build();
            tenantRepository.save(superadmin);
            log.info("Initialized bootstrap Superadmin account: {}", normalizedEmail);
        }
    }
}
