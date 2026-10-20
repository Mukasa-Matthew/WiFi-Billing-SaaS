package com.wifibilling.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Email @Size(max = 120) String email,
        @NotBlank @Size(max = 30) String phone,
        @NotBlank @Size(min = 6, message = "Password must be at least 6 characters") String password) {
}
