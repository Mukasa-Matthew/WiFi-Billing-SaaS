package com.wifibilling.config;

import com.wifibilling.model.Tenant;
import com.wifibilling.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseSeeder implements CommandLineRunner {

    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        String superadminEmail = "admin@wifibilling.com";
        if (!tenantRepository.existsByEmailIgnoreCase(superadminEmail)) {
            Tenant superadmin = Tenant.builder()
                    .name("Platform Superadmin")
                    .email(superadminEmail)
                    .phone("+256700000000")
                    .passwordHash(passwordEncoder.encode("Admin@123456"))
                    .role("ROLE_SUPERADMIN")
                    .status("ACTIVE")
                    .build();
            tenantRepository.save(superadmin);
            log.info("Initialized default Superadmin account: {} (Password: Admin@123456)", superadminEmail);
        }
    }
}
