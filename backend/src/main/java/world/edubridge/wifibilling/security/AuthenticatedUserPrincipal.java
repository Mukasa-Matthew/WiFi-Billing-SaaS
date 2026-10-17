package world.edubridge.wifibilling.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import world.edubridge.wifibilling.domain.UserAccount;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class AuthenticatedUserPrincipal implements UserDetails, Serializable {

    private final Long userId;
    private final Long tenantId;
    private final String email;
    private final String fullName;
    private final String passwordHash;
    private final String role;
    private final boolean active;

    public AuthenticatedUserPrincipal(UserAccount account) {
        this.userId = account.getId();
        this.tenantId = account.getTenantId();
        this.email = account.getEmail();
        this.fullName = account.getFullName();
        this.passwordHash = account.getPasswordHash();
        this.role = account.getRole().toLowerCase(Locale.ROOT);
        this.active = "active".equalsIgnoreCase(account.getStatus());
    }

    public Long getUserId() {
        return userId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public String getRole() {
        return role;
    }

    public boolean isSuperAdmin() {
        return "super_admin".equals(role);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return active;
    }

    @Override
    public boolean isAccountNonLocked() {
        return active;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return active;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
