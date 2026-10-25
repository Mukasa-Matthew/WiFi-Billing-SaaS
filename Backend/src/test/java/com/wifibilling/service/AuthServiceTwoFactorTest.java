package com.wifibilling.service;

import com.wifibilling.dto.AuthRequest;
import com.wifibilling.dto.AuthResponse;
import com.wifibilling.dto.TwoFactorVerifyRequest;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.AuditLogRepository;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.repository.UserSessionRepository;
import com.wifibilling.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTwoFactorTest {

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private OtpService otpService;
    @Mock
    private TotpService totpService;
    @Mock
    private EncryptionService encryptionService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                tenantRepository,
                userSessionRepository,
                auditLogRepository,
                passwordEncoder,
                jwtService,
                otpService,
                totpService,
                encryptionService
        );
    }

    @Test
    void loginWith2faDisabled_IssuesFullSession() {
        Tenant tenant = Tenant.builder()
                .id(1L)
                .email("admin@airpairy.com")
                .role("ROLE_SUPERADMIN")
                .passwordHash("hashed")
                .totpEnabled(false)
                .build();

        when(tenantRepository.findByEmailIgnoreCase("admin@airpairy.com")).thenReturn(Optional.of(tenant));
        when(passwordEncoder.matches("password", "hashed")).thenReturn(true);
        when(jwtService.generateToken(eq(1L), eq("admin@airpairy.com"), eq("ROLE_SUPERADMIN"), any())).thenReturn("full_jwt");

        AuthResponse res = authService.login(new AuthRequest("admin@airpairy.com", "password"), null);

        assertNotNull(res.token());
        assertNull(res.requires2fa());
        assertEquals("full_jwt", res.token());
    }

    @Test
    void loginWith2faEnabled_RequiresTwoFactor() {
        Tenant tenant = Tenant.builder()
                .id(1L)
                .email("admin@airpairy.com")
                .role("ROLE_SUPERADMIN")
                .passwordHash("hashed")
                .totpEnabled(true)
                .build();

        when(tenantRepository.findByEmailIgnoreCase("admin@airpairy.com")).thenReturn(Optional.of(tenant));
        when(passwordEncoder.matches("password", "hashed")).thenReturn(true);
        when(jwtService.generate2faChallengeToken(1L, "admin@airpairy.com")).thenReturn("challenge_token");

        AuthResponse res = authService.login(new AuthRequest("admin@airpairy.com", "password"), null);

        assertNull(res.token());
        assertTrue(res.requires2fa());
        assertEquals("challenge_token", res.tempToken());
    }

    @Test
    void verifyTwoFactor_WithValidTotp_IssuesFullSession() {
        Tenant tenant = Tenant.builder()
                .id(1L)
                .email("admin@airpairy.com")
                .role("ROLE_SUPERADMIN")
                .totpEnabled(true)
                .totpSecret("enc_secret")
                .build();

        when(jwtService.isTokenValid("challenge_token")).thenReturn(true);
        when(jwtService.extractPurpose("challenge_token")).thenReturn("2FA_CHALLENGE");
        when(jwtService.extractTenantId("challenge_token")).thenReturn(1L);
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(tenant));
        when(encryptionService.decrypt("enc_secret")).thenReturn("decrypted_secret");
        when(totpService.verifyCode("decrypted_secret", "123456")).thenReturn(true);
        when(jwtService.generateToken(eq(1L), eq("admin@airpairy.com"), eq("ROLE_SUPERADMIN"), any())).thenReturn("full_jwt");

        AuthResponse res = authService.verifyTwoFactorLogin(new TwoFactorVerifyRequest("challenge_token", "123456"), null);

        assertNotNull(res.token());
        assertEquals("full_jwt", res.token());
    }

    @Test
    void verifyTwoFactor_WithInvalidOtp_ThrowsUnauthorized() {
        Tenant tenant = Tenant.builder()
                .id(1L)
                .email("admin@airpairy.com")
                .role("ROLE_SUPERADMIN")
                .totpEnabled(true)
                .totpSecret("enc_secret")
                .build();

        when(jwtService.isTokenValid("challenge_token")).thenReturn(true);
        when(jwtService.extractPurpose("challenge_token")).thenReturn("2FA_CHALLENGE");
        when(jwtService.extractTenantId("challenge_token")).thenReturn(1L);
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(tenant));
        when(encryptionService.decrypt("enc_secret")).thenReturn("decrypted_secret");
        when(totpService.verifyCode("decrypted_secret", "000000")).thenReturn(false);

        assertThrows(ResponseStatusException.class, () ->
                authService.verifyTwoFactorLogin(new TwoFactorVerifyRequest("challenge_token", "000000"), null));
    }
}
