package com.wifibilling.dto;

import jakarta.validation.constraints.NotBlank;

public record DisconnectSessionRequest(
    @NotBlank(message = "Username / voucher code is required")
    String username
) {}
