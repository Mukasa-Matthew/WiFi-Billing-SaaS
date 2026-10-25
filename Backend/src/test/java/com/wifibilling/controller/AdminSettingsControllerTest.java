package com.wifibilling.controller;

import com.wifibilling.dto.AdminSettingsDto;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.AuditLogRepository;
import com.wifibilling.repository.PlatformSettingRepository;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.repository.UserSessionRepository;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.AdminSettingsService;
import com.wifibilling.service.EncryptionService;
import com.wifibilling.service.OtpService;
import com.wifibilling.service.TotpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminSettingsControllerTest {

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private PlatformSettingRepository platformSettingRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TotpService totpService;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private OtpService otpService;

    private AdminSettingsService service;
    private AdminSettingsController controller;

    private Tenant superAdmin;
    private TenantPrincipal superAdminPrincipal;
    private TenantPrincipal normalTenantPrincipal;

    @BeforeEach
    void setUp() {
        service = new AdminSettingsService(
                tenantRepository,
                userSessionRepository,
                auditLogRepository,
                platformSettingRepository,
                passwordEncoder,
                totpService,
                encryptionService,
                otpService
        );
        controller = new AdminSettingsController(service);

        superAdmin = Tenant.builder()
                .id(1L)
                .name("Super Admin")
                .email("admin@airpairy.com")
                .phone("+256700112233")
                .role("ROLE_SUPERADMIN")
                .status("ACTIVE")
                .totpEnabled(false)
                .createdAt(OffsetDateTime.now())
                .build();

        superAdminPrincipal = new TenantPrincipal(1L, "admin@airpairy.com", "Super Admin", "ROLE_SUPERADMIN", false, "sess-1");
        normalTenantPrincipal = new TenantPrincipal(2L, "tenant@hotspot.com", "Tenant", "ROLE_ADMIN", false, "sess-2");
    }

    @Test
    void normalTenantCannotAccessSuperAdminSettings() {
        assertThrows(ResponseStatusException.class, () -> controller.getProfile(normalTenantPrincipal));
    }

    @Test
    void superAdminCanViewProfile() {
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(superAdmin));

        AdminSettingsDto.ProfileResponse res = controller.getProfile(superAdminPrincipal);
        assertNotNull(res);
        assertEquals("admin@airpairy.com", res.email());
        assertEquals("Super Admin", res.name());
        assertEquals("ROLE_SUPERADMIN", res.role());
    }

    @Test
    void superAdminCanUpdateProfileName() {
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(superAdmin));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> i.getArgument(0));

        AdminSettingsDto.ProfileResponse res = controller.updateProfile(
                superAdminPrincipal,
                new AdminSettingsDto.UpdateProfileRequest("AirPairy Lead Admin"),
                null
        );

        assertNotNull(res);
        assertEquals("AirPairy Lead Admin", res.name());
        verify(tenantRepository).save(argThat(t -> "AirPairy Lead Admin".equals(t.getName())));
    }

    @Test
    void setupTwoFactorReturnsQrAndSecret() {
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(superAdmin));
        when(totpService.generateSecret()).thenReturn("JBSWY3DPEHPK3PXP");
        when(totpService.getOtpAuthUri(anyString(), anyString(), anyString()))
                .thenReturn("otpauth://totp/AirPairy:admin@airpairy.com?secret=JBSWY3DPEHPK3PXP");

        AdminSettingsDto.TwoFactorSetupResponse res = controller.setupTwoFactor(superAdminPrincipal);
        assertNotNull(res);
        assertEquals("JBSWY3DPEHPK3PXP", res.secret());
        assertTrue(res.qrCodeUri().contains("JBSWY3DPEHPK3PXP"));
    }

    @Test
    void activateTwoFactor_RejectsBadOtp() {
        superAdmin.setPasswordHash("hashed_pw");
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(superAdmin));
        when(passwordEncoder.matches("Password123!", "hashed_pw")).thenReturn(true);
        when(totpService.verifyCode("JBSWY3DPEHPK3PXP", "123456")).thenReturn(false);

        AdminSettingsDto.TwoFactorActivateRequest req = new AdminSettingsDto.TwoFactorActivateRequest(
                "Password123!", "JBSWY3DPEHPK3PXP", "123456"
        );

        assertThrows(ResponseStatusException.class, () -> controller.activateTwoFactor(superAdminPrincipal, req, null));
    }

    @Test
    void activateTwoFactor_SuccessGeneratesRecoveryCodes() {
        superAdmin.setPasswordHash("hashed_pw");
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(superAdmin));
        when(passwordEncoder.matches("Password123!", "hashed_pw")).thenReturn(true);
        when(totpService.verifyCode("JBSWY3DPEHPK3PXP", "654321")).thenReturn(true);
        when(encryptionService.encrypt("JBSWY3DPEHPK3PXP")).thenReturn("enc_secret");
        when(totpService.generateRecoveryCodes()).thenReturn(List.of("1111-2222", "3333-4444"));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed_rec");
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> i.getArgument(0));

        AdminSettingsDto.TwoFactorActivateRequest req = new AdminSettingsDto.TwoFactorActivateRequest(
                "Password123!", "JBSWY3DPEHPK3PXP", "654321"
        );

        AdminSettingsDto.TwoFactorActivateResponse res = controller.activateTwoFactor(superAdminPrincipal, req, null);
        assertNotNull(res);
        assertEquals(2, res.recoveryCodes().size());
        assertTrue(superAdmin.getTotpEnabled());
        assertEquals("enc_secret", superAdmin.getTotpSecret());
    }

    @Test
    void updatePlatformSettings() {
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(superAdmin));
        when(platformSettingRepository.findAll()).thenReturn(List.of());

        Map<String, String> res = controller.updatePlatformSettings(
                superAdminPrincipal,
                Map.of("platform_name", "AirPairy Enterprise", "default_subscription_fee", "75000"),
                null
        );

        assertNotNull(res);
        verify(platformSettingRepository, times(2)).save(any());
    }
}
