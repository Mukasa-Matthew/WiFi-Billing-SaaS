package com.wifibilling.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record VoucherResponse(
        Long id,
        String code,
        String status,
        Long packageId,
        String packageName,
        BigDecimal price,
        String claimedMac,
        OffsetDateTime firstLogin,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt,
        Long customerId, String customerName, String customerPhone,
        String batchReference, String saleType, String delivery, Boolean archived) {
}
