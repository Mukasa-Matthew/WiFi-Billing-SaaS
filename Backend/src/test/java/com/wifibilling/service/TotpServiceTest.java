package com.wifibilling.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TotpServiceTest {

    private final TotpService totpService = new TotpService();

    @Test
    void testGenerateSecretAndVerify() {
        String secret = totpService.generateSecret();
        assertNotNull(secret);
        assertTrue(secret.length() >= 16);

        String uri = totpService.getOtpAuthUri("AirPairy", "admin@airpairy.com", secret);
        assertTrue(uri.startsWith("otpauth://totp/AirPairy:admin%40airpairy.com"));
        assertTrue(uri.contains("secret=" + secret));
    }

    @Test
    void testRecoveryCodesFormat() {
        List<String> codes = totpService.generateRecoveryCodes();
        assertNotNull(codes);
        assertEquals(8, codes.size());
        for (String c : codes) {
            assertEquals(9, c.length());
            assertEquals('-', c.charAt(4));
        }
    }

    @Test
    void testBase32EncodeDecode() {
        byte[] original = "SuperSecretKey123".getBytes();
        String encoded = TotpService.encodeBase32(original);
        byte[] decoded = TotpService.decodeBase32(encoded);
        assertArrayEquals(original, decoded);
    }
}
