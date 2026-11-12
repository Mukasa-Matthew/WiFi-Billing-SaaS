package com.wifibilling.service;

import com.wifibilling.dto.AuthRequest;
import com.wifibilling.dto.AuthResponse;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.AuditLogRepository;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.repository.UserSessionRepository;
import com.wifibilling.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceRegistrationTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private UserSessionRepository userSessionRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private OtpService otpService;
    @Mock private TotpService totpService;
    @Mock private EncryptionService encryptionService;
    @Mock private SubscriptionService subscriptionService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                tenantRepository, userSessionRepository, auditLogRepository,
                passwordEncoder, jwtService, otpService, totpService,
                encryptionService, subscriptionService);
    }

    @Test
    void register_NewEmail_CreatesUnverifiedTenantAndSendsCode() {
        when(tenantRepository.findByEmailIgnoreCase("owner@cafe.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secret123")).thenReturn("hashed");
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.register("Cafe WiFi", "Owner@Cafe.com", "0700000000", "secret123");

        verify(tenantRepository, times(2)).save(argThat(t ->
                Boolean.FALSE.equals(t.getEmailVerified())
                        && "owner@cafe.com".equals(t.getEmail())
                        && "ROLE_ADMIN".equals(t.getRole())));
        verify(subscriptionService).grantTrialIfEligible(any(Tenant.class));
        verify(otpService).issue(eq("owner@cafe.com"), eq("REGISTRATION"), any(), eq("Cafe WiFi"), any());
    }

    @Test
    void register_VerifiedEmail_Conflicts() {
        Tenant taken = Tenant.builder().email("owner@cafe.com").emailVerified(true).build();
        when(tenantRepository.findByEmailIgnoreCase("owner@cafe.com")).thenReturn(Optional.of(taken));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> authService.register("Cafe WiFi", "owner@cafe.com", "0700000000", "secret123"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(otpService, never()).issue(any(), any(), any(), any(), any());
    }

    @Test
    void register_UnverifiedEmail_RollsOverDetailsAndResends() {
        Tenant pending = Tenant.builder().email("owner@cafe.com").emailVerified(false).build();
        when(tenantRepository.findByEmailIgnoreCase("owner@cafe.com")).thenReturn(Optional.of(pending));
        when(passwordEncoder.encode("newpass123")).thenReturn("hashed2");
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.register("New Name", "owner@cafe.com", "0711111111", "newpass123");

        assertEquals("New Name", pending.getName());
        assertEquals("hashed2", pending.getPasswordHash());
        verify(otpService).issue(eq("owner@cafe.com"), eq("REGISTRATION"), any(), eq("New Name"), any());
    }

    @Test
    void verifyRegistration_GoodCode_MarksVerifiedAndLogsIn() {
        Tenant pending = Tenant.builder().id(5L).name("Cafe").email("owner@cafe.com")
                .emailVerified(false).role("ROLE_ADMIN").build();
        when(tenantRepository.findByEmailIgnoreCase("owner@cafe.com")).thenReturn(Optional.of(pending));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.generateToken(eq(5L), eq("owner@cafe.com"), eq("ROLE_ADMIN"), anyString()))
                .thenReturn("jwt-token");
        HttpServletRequest servlet = mock(HttpServletRequest.class);

        AuthResponse res = authService.verifyRegistration("owner@cafe.com", "123456", servlet);

        assertTrue(Boolean.TRUE.equals(pending.getEmailVerified()));
        verify(otpService).verify("owner@cafe.com", "REGISTRATION", "123456");
        assertEquals("jwt-token", res.token());
        assertEquals("Cafe", res.name());
    }

    @Test
    void verifyRegistration_UnknownEmail_GenericError() {
        when(tenantRepository.findByEmailIgnoreCase("ghost@cafe.com")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> authService.verifyRegistration("ghost@cafe.com", "123456", null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(otpService, never()).verify(any(), any(), any());
    }

    @Test
    void login_UnverifiedAccount_Forbidden() {
        Tenant pending = Tenant.builder().email("owner@cafe.com")
                .emailVerified(false).status("ACTIVE").build();
        when(tenantRepository.findByEmailIgnoreCase("owner@cafe.com")).thenReturn(Optional.of(pending));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> authService.login(new AuthRequest("owner@cafe.com", "whatever"), null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void resendCode_VerifiedAccount_DoesNothing() {
        Tenant done = Tenant.builder().email("owner@cafe.com").emailVerified(true).build();
        when(tenantRepository.findByEmailIgnoreCase("owner@cafe.com")).thenReturn(Optional.of(done));

        authService.resendRegistrationCode("owner@cafe.com");
        verify(otpService, never()).issue(any(), any(), any(), any(), any());
    }
}
