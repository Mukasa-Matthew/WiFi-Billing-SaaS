package com.wifibilling.school;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signatures shared with the school identity system. The canonical string is
 * direction \n timestamp \n nonce \n METHOD \n raw path[?query] \n hex SHA-256(body); the direction
 * line means a request signed for one direction is never valid in the other.
 */
public final class IdentitySignature {
    public static final String TO_IDENTITY = "airpairy-to-identity";
    public static final String FROM_IDENTITY = "identity-to-airpairy";

    private IdentitySignature() {}

    public static String sign(String secret, String direction, long timestamp, String nonce, String method, String pathAndQuery, byte[] body) {
        try {
            HexFormat hex = HexFormat.of();
            String canonical = direction + "\n" + timestamp + "\n" + nonce + "\n" + method + "\n" + pathAndQuery + "\n"
                    + hex.formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex.formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Identity request signing failed", e);
        }
    }

    public static boolean matches(String expected, String supplied) {
        return supplied != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }
}
