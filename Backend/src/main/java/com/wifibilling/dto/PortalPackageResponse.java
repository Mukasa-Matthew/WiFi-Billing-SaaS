package com.wifibilling.dto;

import java.math.BigDecimal;

public record PortalPackageResponse(
        Long id,
        String name,
        BigDecimal price,
        String rateLimit,
        Integer sessionTimeout, Boolean featured) {
}
