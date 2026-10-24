package com.wifibilling.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM authenticated encryption for tenant gateway credentials at rest.
 * Key comes from APP_ENCRYPTION_KEY (base64, 32 bytes); when unset it is derived
 * from jwt.secret so deployments without a dedicated key still encrypt.
 * Format: base64(iv[12] || ciphertext+tag).
 */
@Slf4j
@Service
public class EncryptionService {

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public EncryptionService(
            @Value("${app.encryption-key:}") String configuredKey,
            @Value("${jwt.secret}") String jwtSecret) throws Exception {
        byte[] keyBytes;
        if (configuredKey != null && !configuredKey.isBlank()) {
            keyBytes = Base64.getDecoder().decode(configuredKey.trim());
            if (keyBytes.length != 32) throw new IllegalStateException("APP_ENCRYPTION_KEY must be 32 bytes base64");
        } else {
            keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(jwtSecret.getBytes(StandardCharsets.UTF_8));
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null) return null;
        try {
            byte[] in = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[12];
            System.arraycopy(in, 0, iv, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    /** Last-4 masking for display, e.g. "…4f2a". */
    public static String mask(String secret) {
        if (secret == null || secret.isBlank()) return null;
        return secret.length() <= 4 ? "••••" : "••••••" + secret.substring(secret.length() - 4);
    }
}
