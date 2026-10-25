package com.wifibilling.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthResponse(
        String token,
        Long tenantId,
        String name,
        String email,
        String role,
        boolean mustChangePassword,
        Boolean requires2fa,
        String tempToken) {

    public AuthResponse(String token, Long tenantId, String name, String email, String role, boolean mustChangePassword) {
        this(token, tenantId, name, email, role, mustChangePassword, null, null);
    }

    public static AuthResponse twoFactorRequired(String tempToken) {
        return new AuthResponse(null, null, null, null, null, false, true, tempToken);
    }
}
