package world.edubridge.wifibilling.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import world.edubridge.wifibilling.domain.Tenant;
import world.edubridge.wifibilling.repository.TenantRepository;

import java.util.Set;

/**
 * Enforces server-side multi-tenant and role boundaries.
 * Never trusts client-supplied tenant or role headers; always inspects the
 * server-side SecurityContext principal and active database tenant state.
 */
@Component
public class TenantAccessGuard {

    private static final Set<String> BUSINESS_PORTAL_ROLES = Set.of(
        "business_admin", "manager", "technician", "cashier"
    );

    private final TenantRepository tenantRepository;

    public TenantAccessGuard(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    public AuthenticatedUserPrincipal requireAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AuthenticatedUserPrincipal principal)) {
            throw new AccessDeniedException("Authentication required");
        }
        return principal;
    }

    public AuthenticatedUserPrincipal requireSuperAdmin() {
        AuthenticatedUserPrincipal principal = requireAuthenticatedUser();
        if (!principal.isSuperAdmin()) {
            throw new AccessDeniedException("Super Admin privileges are required for this operation");
        }
        return principal;
    }

    /**
     * Resolves and validates the tenant ID for a Business Admin / Staff request.
     * - Business users are strictly bound to their session's tenantId.
     * - If a path or query tenantId is supplied and does not match the session's tenantId,
     *   an AccessDeniedException (HTTP 403) is thrown immediately.
     */
    public Long requireBusinessTenantAccess(Long requestedTenantId, String... allowedRoles) {
        AuthenticatedUserPrincipal principal = requireAuthenticatedUser();

        if (allowedRoles != null && allowedRoles.length > 0) {
            boolean roleMatched = false;
            for (String allowed : allowedRoles) {
                if (allowed.equalsIgnoreCase(principal.getRole())) {
                    roleMatched = true;
                    break;
                }
            }
            if (!roleMatched && !principal.isSuperAdmin()) {
                throw new AccessDeniedException("Role '" + principal.getRole() + "' is not permitted to perform this operation");
            }
        } else if (!BUSINESS_PORTAL_ROLES.contains(principal.getRole()) && !principal.isSuperAdmin()) {
            throw new AccessDeniedException("Unrecognized business role");
        }

        Long effectiveTenantId;
        if (principal.isSuperAdmin()) {
            if (requestedTenantId == null) {
                throw new AccessDeniedException("Super Admin must specify a target tenant ID when accessing business-scoped endpoints");
            }
            effectiveTenantId = requestedTenantId;
        } else {
            Long sessionTenantId = principal.getTenantId();
            if (sessionTenantId == null) {
                throw new AccessDeniedException("Account is not bound to a tenant");
            }
            if (requestedTenantId != null && !sessionTenantId.equals(requestedTenantId)) {
                throw new AccessDeniedException("Cross-tenant access denied: account belongs to tenant " + sessionTenantId);
            }
            effectiveTenantId = sessionTenantId;
        }

        Tenant tenant = tenantRepository.findById(effectiveTenantId)
            .orElseThrow(() -> new AccessDeniedException("Target tenant does not exist"));

        if (!principal.isSuperAdmin()
            && !"active".equalsIgnoreCase(tenant.getStatus())
            && !"trial".equalsIgnoreCase(tenant.getStatus())) {
            throw new AccessDeniedException("Business tenant is suspended");
        }

        return effectiveTenantId;
    }
}
