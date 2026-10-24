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
            String supportEmail,
            String brandColor,
            String redirectUrl,
            String template,
            boolean showPackages
    ) {}
}
