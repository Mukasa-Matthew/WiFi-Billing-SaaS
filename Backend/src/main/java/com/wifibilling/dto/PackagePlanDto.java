package com.wifibilling.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class PackagePlanDto {

    public record CreateRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull @DecimalMin("0.0") BigDecimal price,
            @NotBlank @Size(max = 30) String rateLimit,
            @NotNull @Min(60) Integer sessionTimeout,
            @Min(1) Integer sharedUsers) {
    }

    public record Response(
            Long id,
            String name,
            BigDecimal price,
            String rateLimit,
            Integer sessionTimeout,
            Integer sharedUsers,
            OffsetDateTime createdAt) {
    }
}
