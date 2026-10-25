package com.wifibilling.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/**
 * Standard RFC 6238 TOTP (HMAC-SHA1, 30s window, 6 digits) and Base32 implementation.
 * Self-contained without external library version conflicts.
 */
@Slf4j
@Service
public class TotpService {

    private static final String BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int TIME_STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    private static final int WINDOW = 1; // Allow +/- 1 time step for clock drift
    private final SecureRandom random = new SecureRandom();

    /**
     * Generates a 20-byte (160-bit) random secret encoded in Base32.
     */
    public String generateSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    /**
     * Generates standard otpauth URI compatible with Google Authenticator, Microsoft Authenticator, 1Password, etc.
     */
    public String getOtpAuthUri(String issuer, String accountEmail, String secret) {
        String encodedIssuer = URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedAccount = URLEncoder.encode(accountEmail, StandardCharsets.UTF_8).replace("+", "%20");
        return String.format("otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=%d&period=%d",
                encodedIssuer, encodedAccount, secret, encodedIssuer, CODE_DIGITS, TIME_STEP_SECONDS);
    }

    /**
     * Verifies the 6-digit TOTP code against the secret within the drift window.
     */
    public boolean verifyCode(String secret, String code) {
        if (secret == null || code == null) return false;
        String cleanCode = code.trim().replaceAll("\\s+", "");
        if (cleanCode.length() != CODE_DIGITS) return false;

        try {
            int targetCode = Integer.parseInt(cleanCode);
            long currentInterval = System.currentTimeMillis() / 1000 / TIME_STEP_SECONDS;
            byte[] key = decodeBase32(secret);

            for (int i = -WINDOW; i <= WINDOW; i++) {
                int calculated = generateCode(key, currentInterval + i);
                if (calculated == targetCode) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("TOTP verification error: {}", e.getMessage());
            return false;
        }
        return false;
    }

    /**
     * Generates a batch of 8 cryptographically random alphanumeric backup recovery codes (e.g. "8A3F-9K2M").
     */
    public List<String> generateRecoveryCodes() {
        List<String> codes = new ArrayList<>(8);
        String chars = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        for (int i = 0; i < 8; i++) {
            StringBuilder sb = new StringBuilder(9);
            for (int j = 0; j < 8; j++) {
                if (j == 4) sb.append('-');
                sb.append(chars.charAt(random.nextInt(chars.length())));
            }
            codes.add(sb.toString());
        }
        return codes;
    }

    private int generateCode(byte[] key, long timeInterval) throws Exception {
        byte[] data = ByteBuffer.allocate(8).putLong(timeInterval).array();
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(data);

        int offset = hash[hash.length - 1] & 0xF;
        long truncatedHash = 0;
        for (int i = 0; i < 4; ++i) {
            truncatedHash <<= 8;
            truncatedHash |= (hash[offset + i] & 0xFF);
        }
        truncatedHash &= 0x7FFFFFFF;
        truncatedHash %= (long) Math.pow(10, CODE_DIGITS);
        return (int) truncatedHash;
    }

    public static String encodeBase32(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int next = 0;
        int bitsLeft = 0;
        while (next < data.length) {
            buffer = (buffer << 8) | (data[next++] & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                sb.append(BASE32_CHARS.charAt((buffer >> bitsLeft) & 0x1F));
            }
        }
        if (bitsLeft > 0) {
            buffer <<= (5 - bitsLeft);
            sb.append(BASE32_CHARS.charAt(buffer & 0x1F));
        }
        return sb.toString();
    }

    public static byte[] decodeBase32(String base32) {
        String clean = base32.toUpperCase(Locale.US).replaceAll("[^A-Z2-7]", "");
        int numBytes = clean.length() * 5 / 8;
        byte[] result = new byte[numBytes];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;

        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            int val = BASE32_CHARS.indexOf(c);
            if (val < 0) continue;
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                if (index < numBytes) {
                    result[index++] = (byte) ((buffer >> bitsLeft) & 0xFF);
                }
            }
        }
        return result;
    }
}
