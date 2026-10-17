package world.edubridge.wifibilling.security;

import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import world.edubridge.wifibilling.domain.Tenant;
import world.edubridge.wifibilling.domain.UserAccount;
import world.edubridge.wifibilling.repository.TenantRepository;
import world.edubridge.wifibilling.repository.UserAccountRepository;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserAccountRepository userAccountRepository;
    private final TenantRepository tenantRepository;

    public CustomUserDetailsService(UserAccountRepository userAccountRepository,
                                    TenantRepository tenantRepository) {
        this.userAccountRepository = userAccountRepository;
        this.tenantRepository = tenantRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        UserAccount user = userAccountRepository.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new UsernameNotFoundException("Invalid email or password"));

        if (!"active".equalsIgnoreCase(user.getStatus())) {
            throw new DisabledException("User account is disabled");
        }

        if (user.getTenantId() != null && !"super_admin".equalsIgnoreCase(user.getRole())) {
            Tenant tenant = tenantRepository.findById(user.getTenantId())
                .orElseThrow(() -> new DisabledException("Associated business tenant not found"));
            if (!"active".equalsIgnoreCase(tenant.getStatus()) && !"trial".equalsIgnoreCase(tenant.getStatus())) {
                throw new DisabledException("Business tenant is currently suspended");
            }
        }

        return new AuthenticatedUserPrincipal(user);
    }
}
