package world.edubridge.wifibilling.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.edubridge.wifibilling.domain.Tenant;
import world.edubridge.wifibilling.repository.TenantRepository;
import world.edubridge.wifibilling.repository.UserAccountRepository;
import world.edubridge.wifibilling.security.AuthenticatedUserPrincipal;
import world.edubridge.wifibilling.security.TenantAccessGuard;
import world.edubridge.wifibilling.service.AuditService;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final UserAccountRepository userAccountRepository;
    private final TenantRepository tenantRepository;
    private final TenantAccessGuard tenantAccessGuard;
    private final AuditService auditService;

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          UserAccountRepository userAccountRepository,
                          TenantRepository tenantRepository,
                          TenantAccessGuard tenantAccessGuard,
                          AuditService auditService) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.userAccountRepository = userAccountRepository;
        this.tenantRepository = tenantRepository;
        this.tenantAccessGuard = tenantAccessGuard;
        this.auditService = auditService;
    }

    public record LoginRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Valid email address is required")
        String email,
        @NotBlank(message = "Password is required")
        String password
    ) {}

    @PostMapping("/login")
    @Transactional
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest loginRequest,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(loginRequest.email().trim(), loginRequest.password())
        );

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        AuthenticatedUserPrincipal principal = (AuthenticatedUserPrincipal) authentication.getPrincipal();

        userAccountRepository.findById(principal.getUserId()).ifPresent(account -> {
            account.setLastLoginAt(OffsetDateTime.now());
            userAccountRepository.save(account);
        });

        auditService.record(
            principal.getTenantId(),
            principal.getUserId(),
            "USER_LOGIN_SUCCESS",
            "user_account",
            String.valueOf(principal.getUserId()),
            Map.of("email", principal.getEmail(), "role", principal.getRole()),
            request.getRemoteAddr()
        );

        return ResponseEntity.ok(buildSessionUserPayload(principal));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> currentUser() {
        AuthenticatedUserPrincipal principal = tenantAccessGuard.requireAuthenticatedUser();
        return ResponseEntity.ok(buildSessionUserPayload(principal));
    }

    private Map<String, Object> buildSessionUserPayload(AuthenticatedUserPrincipal principal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", principal.getUserId());
        payload.put("tenantId", principal.getTenantId());
        payload.put("email", principal.getEmail());
        payload.put("fullName", principal.getFullName());
        payload.put("role", principal.getRole());
        payload.put("superAdmin", principal.isSuperAdmin());

        if (principal.getTenantId() != null) {
            Tenant tenant = tenantRepository.findById(principal.getTenantId()).orElse(null);
            if (tenant != null) {
                payload.put("tenantName", tenant.getName());
                payload.put("tenantSlug", tenant.getSlug());
                payload.put("tenantStatus", tenant.getStatus());
            }
        }
        return payload;
    }
}
