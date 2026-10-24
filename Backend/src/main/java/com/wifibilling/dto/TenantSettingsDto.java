package com.wifibilling.dto;

import jakarta.validation.constraints.*;

public record TenantSettingsDto(
        @Size(max = 120) String name,
        String email,
        @Size(max = 60) String supportPhone,
        @Email @Size(max = 120) String supportEmail,
        @Pattern(regexp = "#[0-9A-Fa-f]{6}", message = "Brand color must be a hex color like #3B82F6")
        String brandColor,
        @Size(max = 255) String redirectUrl,
        @Pattern(regexp = "[A-Za-z0-9]{0,4}") String voucherPrefix,
        @Min(5) @Max(16) Integer voucherCodeLength,
        @Pattern(regexp = "ALPHANUMERIC|NUMERIC|LETTERS") String voucherCodeFormat,
        Boolean portalShowPackages,
        @Size(max = 500) String notifyEmails,
        @Size(max = 255) String notifyPhones,
        @Pattern(regexp = "[a-z0-9-]{1,40}") String portalTemplate,
        @Pattern(regexp = "OFF|LOG|BLOCK") String tetheringProtection,
        @Size(max = 60) String supportPhone2,
        @Size(max = 60) String whatsappPhone,
        @Size(max = 60) String portalFont,
        @Size(max = 30) String portalLanguage,
        @Pattern(regexp = "#[0-9A-Fa-f]{6}|^$") String portalPrimaryColor,
        @Pattern(regexp = "#[0-9A-Fa-f]{6}|^$") String portalSecondaryColor,
        @Min(40) @Max(120) Integer portalLogoSize,
        Boolean portalShowDeviceLimits,
        Boolean portalShowDeviceMac,
        Boolean portalShowPackageTime,
        Boolean portalAllowPurchases,
        Boolean portalBuyForOther,
        Boolean portalShowFeedback,
        boolean hasLogo,
        boolean hasBackground
) {}
