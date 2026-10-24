package com.wifibilling.dto;

import java.util.List;

/** Public, tenant-scoped configuration consumed by a router-hosted portal. */
public record PortalConfigResponse(
        String version,
        PortalSettings settings,
        List<PortalPackageResponse> packages
) {
    public record PortalSettings(
            String businessName,
            String supportPhone,
            String supportPhone2,
            String supportEmail,
            String whatsappPhone,
            String brandColor,
            String redirectUrl,
            String template,
            boolean showPackages,
            String font,
            String language,
            String primaryColor,
            String secondaryColor,
            Integer logoSize,
            String logoUrl,
            String backgroundUrl,
            boolean showDeviceLimits,
            boolean showDeviceMac,
            boolean showPackageTime,
            boolean allowPurchases,
            boolean buyForOther,
            boolean showFeedback
    ) {}
}
