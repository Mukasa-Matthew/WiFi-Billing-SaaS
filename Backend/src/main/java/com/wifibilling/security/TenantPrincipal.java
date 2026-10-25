package com.wifibilling.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Getter
public class TenantPrincipal implements UserDetails {

    private final Long tenantId;
    private final String email;
    private final String name;
    private final String role;
    private final boolean mustChangePassword;

    public TenantPrincipal(Long tenantId, String email, String name, String role, boolean mustChangePassword) {
        this.tenantId = tenantId;
        this.email = email;
        this.name = name;
        this.role = (role != null && !role.isBlank()) ? role : "ROLE_ADMIN";
        this.mustChangePassword = mustChangePassword;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(this.role));
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
