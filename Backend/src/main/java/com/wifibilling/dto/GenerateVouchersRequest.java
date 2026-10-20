package com.wifibilling.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record GenerateVouchersRequest(
        @NotNull Long packageId,
        @NotNull @Min(1) @Max(500) Integer count) {
}
