package com.wifibilling.security;

import com.wifibilling.model.Tenant;
import com.wifibilling.model.UserSession;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.repository.UserSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TenantRepository tenantRepository;
    private final UserSessionRepository userSessionRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        try {
            if (jwtService.isTokenValid(token) && SecurityContextHolder.getContext().getAuthentication() == null) {
                // Reject temporary 2FA challenge tokens on normal API endpoints
                String purpose = jwtService.extractPurpose(token);
                if ("2FA_CHALLENGE".equalsIgnoreCase(purpose)) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Two-factor authentication challenge incomplete");
                    return;
                }

                String sessionId = jwtService.extractSessionId(token);
                if (sessionId != null) {
                    Optional<UserSession> sessionOpt = userSessionRepository.findBySessionId(sessionId);
                    if (sessionOpt.isEmpty() || Boolean.TRUE.equals(sessionOpt.get().getRevoked())) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Session has been revoked or expired");
                        return;
                    }
                    // Update last active
                    UserSession session = sessionOpt.get();
                    session.setLastActiveAt(OffsetDateTime.now());
                    userSessionRepository.save(session);
                }

                Long tenantId = jwtService.extractTenantId(token);
                Optional<Tenant> tenantOpt = tenantRepository.findById(tenantId);
                if (tenantOpt.isPresent()) {
                    Tenant tenant = tenantOpt.get();
                    if (!"DISABLED".equalsIgnoreCase(tenant.getStatus()) && !"SUSPENDED".equalsIgnoreCase(tenant.getStatus())) {
                        TenantPrincipal principal = new TenantPrincipal(
                                tenant.getId(),
                                tenant.getEmail(),
                                tenant.getName(),
                                tenant.getRole(),
                                Boolean.TRUE.equals(tenant.getMustChangePassword()),
                                sessionId
                        );
                        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                principal.getAuthorities()
                        );
                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authToken);

                        String path = request.getRequestURI();
                        if (principal.isMustChangePassword()
                                && !path.equals("/api/auth/change-password")) {
                            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                                    "Password change required before accessing the account");
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // invalid token -> proceed without setting auth context
        }

        filterChain.doFilter(request, response);
    }
}
