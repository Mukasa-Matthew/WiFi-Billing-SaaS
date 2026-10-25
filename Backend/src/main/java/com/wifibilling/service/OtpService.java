package com.wifibilling.service;

import com.wifibilling.model.EmailOtp;
import com.wifibilling.repository.EmailOtpRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;

/**
 * Issues and verifies single-use email OTPs. Safety properties:
 *  - codes are 6 digits, hashed at rest, expire quickly, limited attempts;
 *  - issuing a new code invalidates the previous one (same email+purpose);
 *  - resend is rate-limited to blunt mail bombing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private final EmailOtpRepository otpRepository;
    private final EmailService emailService;
    private final EmailTemplates templates;
    private final SecureRandom random = new SecureRandom();

    @Value("${otp.ttl-minutes:10}")
    private int ttlMinutes;
    @Value("${otp.max-attempts:5}")
    private int maxAttempts;
    @Value("${otp.resend-cooldown-seconds:60}")
    private int resendCooldownSeconds;
    /** Dev-only: return the plaintext code in the API response when email is
     *  unconfigured. MUST stay false in production. */
    @Value("${otp.dev-fallback-enabled:false}")
    private boolean devFallbackEnabled;

    /**
     * Issue a code to an email. Returns the plaintext code ONLY when email
     * delivery is not configured (dev mode) — never expose it otherwise.
     */
    @Transactional
    public String issue(String email, String purpose, Long tenantId, String businessName, String context) {
        String normalized = email.trim().toLowerCase();
        var latest = otpRepository.findFirstByEmailAndPurposeOrderByIdDesc(normalized, purpose);
        if (latest.isPresent()) {
            EmailOtp prev = latest.get();
            if (prev.getCreatedAt() != null
                    && prev.getCreatedAt().isAfter(OffsetDateTime.now().minusSeconds(resendCooldownSeconds))) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "A code was just sent — check your email or wait a minute");
            }
            // Invalidate the previous code so only one is live at a time.
            if (prev.getConsumedAt() == null) {
                prev.setConsumedAt(OffsetDateTime.now());
                otpRepository.save(prev);
            }
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        otpRepository.save(EmailOtp.builder()
                .email(normalized).purpose(purpose).tenantId(tenantId)
                .codeHash(hash(code))
                .expiresAt(OffsetDateTime.now().plusMinutes(ttlMinutes))
                .build());
        String subject = EmailOtp.PURPOSE_PASSWORD_RESET.equals(purpose)
                ? "Reset your AirPairy password"
                : "Your AirPairy verification code";
        String html = EmailOtp.PURPOSE_PASSWORD_RESET.equals(purpose)
                ? templates.passwordResetEmail(businessName, code, ttlMinutes)
                : templates.otpEmail(businessName, code, ttlMinutes, context);
        emailService.send(normalized, subject, html);
        if (!emailService.configured()) {
            if (!devFallbackEnabled) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Email delivery is not configured on this server");
            }
            return code;
        }
        return null;
    }

    /** Verify and consume a code. Throws 400 on any mismatch — no detail leakage. */
    @Transactional
    public void verify(String email, String purpose, String code) {
        String normalized = email.trim().toLowerCase();
        EmailOtp otp = otpRepository.findFirstByEmailAndPurposeOrderByIdDesc(normalized, purpose)
                .filter(o -> o.getConsumedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No active code — request a new one"));
        if (otp.getExpiresAt().isBefore(OffsetDateTime.now())) {
            otp.setConsumedAt(OffsetDateTime.now());
            otpRepository.save(otp);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Code expired — request a new one");
        }
        if (otp.getAttempts() >= maxAttempts) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts — request a new code");
        }
        if (!otp.getCodeHash().equals(hash(code == null ? "" : code.trim()))) {
            otp.setAttempts(otp.getAttempts() + 1);
            otpRepository.save(otp);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Incorrect code");
        }
        otp.setConsumedAt(OffsetDateTime.now());
        otpRepository.save(otp);
    }

    private String hash(String code) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
