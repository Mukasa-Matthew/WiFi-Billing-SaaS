package com.wifibilling.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private final String secret = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    private final JwtService jwtService = new JwtService(secret, 24);

    @Test
    void generatesAndValidatesToken() {
        String token = jwtService.generateToken(42L, "tenant@example.com", "ROLE_ADMIN");
        assertNotNull(token);
        assertTrue(jwtService.isTokenValid(token));
        assertEquals("tenant@example.com", jwtService.extractEmail(token));
        assertEquals(42L, jwtService.extractTenantId(token));
        assertEquals("ROLE_ADMIN", jwtService.extractRole(token));
    }

    @Test
    void rejectsMalformedToken() {
        assertFalse(jwtService.isTokenValid("not.a.valid.jwt"));
    }
}
