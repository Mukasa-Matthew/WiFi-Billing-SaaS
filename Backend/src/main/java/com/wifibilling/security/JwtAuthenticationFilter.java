package com.wifibilling.security;

import com.wifibilling.model.Tenant;
import com.wifibilling.repository.TenantRepository;
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
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TenantRepository tenantRepository;

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
                Long tenantId = jwtService.extractTenantId(token);
                Optional<Tenant> tenantOpt = tenantRepository.findById(tenantId);
                if (tenantOpt.isPresent()) {
                    Tenant tenant = tenantOpt.get();
                    if (!"DISABLED".equalsIgnoreCase(tenant.getStatus()) && !"SUSPENDED".equalsIgnoreCase(tenant.getStatus())) {
                        TenantPrincipal principal = new TenantPrincipal(
                                tenant.getId(),
                                tenant.getEmail(),
                                tenant.getName(),
                                tenant.getRole()
                        );
                        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                principal.getAuthorities()
                        );
                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authToken);
                    }
                }
            }
        } catch (Exception e) {
            // invalid token -> proceed without setting auth context
        }

        filterChain.doFilter(request, response);
    }
}
