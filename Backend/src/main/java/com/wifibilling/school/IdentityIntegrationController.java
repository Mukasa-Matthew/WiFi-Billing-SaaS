package com.wifibilling.school;

import com.wifibilling.model.IntegrationNonce;
import com.wifibilling.repository.IntegrationNonceRepository;
import com.wifibilling.service.EmailService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Signed server-to-server calls from the school identity system. Only reachable on the internal network in
 * production; the signature, a five-minute clock window and single-use nonces protect it regardless.
 */
@Slf4j
@RestController
@RequestMapping("/api/integrations/identity")
public class IdentityIntegrationController {
    private static final long MAX_SKEW_SECONDS = 300;
    private final SchoolAccessService access;
    private final IntegrationNonceRepository nonces;
    private final EmailService emailService;
    private final String secret;
    private final ObjectMapper mapper = new ObjectMapper();

    public IdentityIntegrationController(SchoolAccessService access, IntegrationNonceRepository nonces,
                                         EmailService emailService, @Value("${identity.shared-secret:}") String secret) {
        this.access = access; this.nonces = nonces; this.emailService = emailService; this.secret = secret;
    }

    /** Sends an IT administrator's account email (sign-in, password reset and email-change codes) for the identity system. */
    @PostMapping("/email")
    public ResponseEntity<Map<String, Object>> sendEmail(@RequestBody(required = false) byte[] body, HttpServletRequest request,
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Signature", required = false) String signature) {
        byte[] bytes = body == null ? new byte[0] : body;
        if (!authentic(bytes, request, timestamp, nonce, signature)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        JsonNode json = mapper.readTree(bytes);
        String to = json.path("to").isString() ? json.path("to").asString().trim() : "";
        String subject = json.path("subject").isString() ? json.path("subject").asString().trim() : "";
        String html = json.path("html").isString() ? json.path("html").asString() : "";
        if (to.isEmpty() || subject.isEmpty() || html.isEmpty() || to.length() > 254 || subject.length() > 200 || html.length() > 20_000) {
            return ResponseEntity.badRequest().build();
        }
        // Sign-in codes must really arrive: without a mail account, say so rather than pretend.
        if (!emailService.configured()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        try { emailService.send(to, subject, html); }
        catch (EmailService.EmailDeliveryException failed) { return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build(); }
        return ResponseEntity.ok(Map.of("sent", true));
    }

    /** Ends the active sessions of a school person (until they sign in again) or of a cancelled visitor pass. */
    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect(@RequestBody(required = false) byte[] body, HttpServletRequest request,
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Signature", required = false) String signature) {
        byte[] bytes = body == null ? new byte[0] : body;
        if (!authentic(bytes, request, timestamp, nonce, signature)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        JsonNode json = mapper.readTree(bytes);
        long tenantId = json.path("tenantId").asLong();
        String personId = json.path("personId").isString() ? json.path("personId").asString() : null;
        String passId = json.path("visitorPassId").isString() ? json.path("visitorPassId").asString() : null;
        if (tenantId <= 0 || (personId == null) == (passId == null)) return ResponseEntity.badRequest().build();
        int ended = access.disconnect(tenantId, personId, passId);
        return ResponseEntity.ok(Map.of("vouchers", ended));
    }

    private boolean authentic(byte[] body, HttpServletRequest request, String timestamp, String nonce, String signature) {
        if (secret.length() < 32 || timestamp == null || !timestamp.matches("[0-9]{1,12}") || nonce == null || !nonce.matches("[A-Za-z0-9-]{16,64}")
                || Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp)) > MAX_SKEW_SECONDS) return false;
        String target = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        String expected = IdentitySignature.sign(secret, IdentitySignature.FROM_IDENTITY, Long.parseLong(timestamp), nonce, request.getMethod(), target, body);
        if (!IdentitySignature.matches(expected, signature)) return false;
        return claim(nonce);
    }

    private boolean claim(String nonce) {
        if (nonces.existsById(nonce)) return false;
        try { nonces.saveAndFlush(new IntegrationNonce(nonce, OffsetDateTime.now())); return true; }
        catch (org.springframework.dao.DataIntegrityViolationException replayed) { return false; }
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT2M")
    @Transactional
    public void forgetOldNonces() { nonces.deleteOlderThan(OffsetDateTime.now().minusMinutes(15)); }
}
