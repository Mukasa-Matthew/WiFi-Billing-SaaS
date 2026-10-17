package world.edubridge.wifibilling.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import world.edubridge.wifibilling.domain.UserAccount;
import world.edubridge.wifibilling.repository.UserAccountRepository;

@Component
public class DevelopmentDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentDataSeeder.class);

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean seedEnabled;
    private final String defaultDevPassword;

    public DevelopmentDataSeeder(UserAccountRepository userAccountRepository,
                                 PasswordEncoder passwordEncoder,
                                 @Value("${app.seed.enabled:true}") boolean seedEnabled,
                                 @Value("${app.seed.default-password:Admin@2026!}") String defaultDevPassword) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.seedEnabled = seedEnabled;
        this.defaultDevPassword = defaultDevPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            return;
        }

        ensureUserAccount(
            null,
            "superadmin@edubridge.world",
            "Platform Super Admin",
            "+256700000000",
            "super_admin"
        );

        ensureUserAccount(
            1L,
            "admin@default-hotspot.local",
            "Default Hotspot Business Admin",
            "+256700000001",
            "business_admin"
        );

        ensureUserAccount(
            2L,
            "admin@tenant2.local",
            "Tenant Two Business Admin",
            "+256700000002",
            "business_admin"
        );
    }

    private void ensureUserAccount(Long tenantId, String email, String fullName, String phone, String role) {
        if (userAccountRepository.findByEmailIgnoreCase(email).isPresent()) {
            return;
        }
        UserAccount user = new UserAccount();
        user.setTenantId(tenantId);
        user.setEmail(email);
        user.setFullName(fullName);
        user.setPhone(phone);
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode(defaultDevPassword));
        user.setStatus("active");
        userAccountRepository.save(user);
        log.info("Seeded development account: {} (role={}, tenantId={})", email, role, tenantId);
    }
}
