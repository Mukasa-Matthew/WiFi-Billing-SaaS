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

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String role = "ROLE_ADMIN"; // ROLE_SUPERADMIN, ROLE_ADMIN

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE"; // ACTIVE, SUSPENDED, DISABLED

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

    @Column(name = "notify_phones", length = 255)
    private String notifyPhones;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
