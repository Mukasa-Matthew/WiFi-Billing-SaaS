package com.wifibilling.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, unique = true, length = 120)
    private String email;

    @Column(nullable = false, length = 30)
    private String phone;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** Set on admin-provisioned accounts; cleared when the tenant sets their own password. */
    @Column(name = "must_change_password")
    @Builder.Default
    private Boolean mustChangePassword = false;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String role = "ROLE_ADMIN"; // ROLE_SUPERADMIN, ROLE_ADMIN

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE"; // ACTIVE, SUSPENDED, DISABLED

    // ---- Two-Factor Authentication (TOTP) ----
    @Column(name = "totp_enabled")
    @Builder.Default
    private Boolean totpEnabled = false;

    /** Base64(IV + Ciphertext) of Base32 TOTP secret, encrypted with server master key. */
    @Column(name = "totp_secret", length = 255)
    private String totpSecret;

    /** Delimited bcrypt/sha256 hashes of unused one-time emergency recovery codes. */
    @Column(name = "recovery_codes_hash", length = 1500)
    private String recoveryCodesHash;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "last_login_ip", length = 64)
    private String lastLoginIp;

    @Column(name = "pending_new_email", length = 120)
    private String pendingNewEmail;

    @Column(name = "pending_new_phone", length = 30)
    private String pendingNewPhone;

    // ---- Tenant-configurable settings (captive portal + voucher defaults) ----
    @Column(name = "support_phone", length = 60)
    private String supportPhone;

    @Column(name = "support_email", length = 120)
    private String supportEmail;

    @Column(name = "brand_color", length = 16)
    @Builder.Default
    private String brandColor = "#3B82F6";

    @Column(name = "redirect_url", length = 255)
    private String redirectUrl;

    @Column(name = "voucher_prefix", length = 4)
    private String voucherPrefix;

    @Column(name = "voucher_code_length")
    @Builder.Default
    private Integer voucherCodeLength = 8;

    @Column(name = "voucher_code_format", length = 20)
    @Builder.Default
    private String voucherCodeFormat = "ALPHANUMERIC";

    @Column(name = "portal_show_packages")
    @Builder.Default
    private Boolean portalShowPackages = true;

    @Column(name = "notify_emails", length = 500)
    private String notifyEmails;

    @Column(name = "notify_router_offline")
    @Builder.Default
    private Boolean notifyRouterOffline = true;

    @Column(name = "notify_phones", length = 255)
    private String notifyPhones;

    @Column(name = "portal_template", length = 40)
    @Builder.Default
    private String portalTemplate = "midnight";

    @Column(name = "tethering_protection", length = 12)
    @Builder.Default
    private String tetheringProtection = "OFF"; // OFF, LOG, BLOCK

    @Column(name = "support_phone2", length = 60)
    private String supportPhone2;

    @Column(name = "whatsapp_phone", length = 60)
    private String whatsappPhone;

    @Column(name = "portal_font", length = 60)
    @Builder.Default
    private String portalFont = "Poppins";

    @Column(name = "portal_language", length = 30)
    @Builder.Default
    private String portalLanguage = "English";

    @Column(name = "portal_primary_color", length = 7)
    private String portalPrimaryColor;

    @Column(name = "portal_secondary_color", length = 7)
    private String portalSecondaryColor;

    @Column(name = "portal_logo_size")
    private Integer portalLogoSize;

    @Column(name = "portal_logo_file", length = 120)
    private String portalLogoFile;

    @Column(name = "portal_background_file", length = 120)
    private String portalBackgroundFile;

    @Column(name = "portal_show_device_limits")
    @Builder.Default
    private Boolean portalShowDeviceLimits = true;

    @Column(name = "portal_show_device_mac")
    @Builder.Default
    private Boolean portalShowDeviceMac = false;

    @Column(name = "portal_show_package_time")
    @Builder.Default
    private Boolean portalShowPackageTime = true;

    @Column(name = "portal_allow_purchases")
    @Builder.Default
    private Boolean portalAllowPurchases = true;

    @Column(name = "portal_buy_for_other")
    @Builder.Default
    private Boolean portalBuyForOther = true;

    @Column(name = "portal_show_feedback")
    @Builder.Default
    private Boolean portalShowFeedback = true;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
    public Boolean getNotifyRouterOffline() { return notifyRouterOffline == null || notifyRouterOffline; }
    public void setNotifyRouterOffline(Boolean notifyRouterOffline) { this.notifyRouterOffline = notifyRouterOffline; }
}
