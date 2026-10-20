package com.wifibilling.dto;

public record AuthResponse(
        String token,
        Long tenantId,
        String name,
        String email,
        String role) {
}
