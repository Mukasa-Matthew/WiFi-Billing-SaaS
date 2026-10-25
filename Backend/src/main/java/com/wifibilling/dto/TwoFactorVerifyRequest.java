package com.wifibilling.dto;

import jakarta.validation.constraints.NotBlank;

public record TwoFactorVerifyRequest(
        @NotBlank(message = "Temporary challenge token is required")
        String tempToken,

        @NotBlank(message = "Verification code or recovery code is required")
        String code
) {
}
