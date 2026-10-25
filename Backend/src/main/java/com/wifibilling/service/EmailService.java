package com.wifibilling.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Transactional email via Brevo (formerly Sendinblue) HTTP API.
 * If no API key is configured the email content is logged instead — keeps local
 * development usable without a mail account while never pretending to send.
 */
@Slf4j
@Service
public class EmailService {

    private final RestTemplate rest = new RestTemplate();

    @Value("${brevo.api-key:}")
    private String apiKey;
    @Value("${brevo.sender-email:no-reply@airpairy.com}")
    private String senderEmail;
    @Value("${brevo.sender-name:AirPairy}")
    private String senderName;

    public boolean configured() { return apiKey != null && !apiKey.isBlank(); }

    public void send(String toEmail, String subject, String htmlContent) {
        if (!configured()) {
            log.warn("Email not sent — BREVO_API_KEY not configured. To={} Subject={}", toEmail, subject);
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("api-key", apiKey);
            Map<String, Object> payload = Map.of(
                    "sender", Map.of("email", senderEmail, "name", senderName),
                    "to", List.of(Map.of("email", toEmail)),
                    "subject", subject,
                    "htmlContent", htmlContent);
            rest.postForEntity("https://api.brevo.com/v3/smtp/email",
                    new HttpEntity<>(payload, headers), String.class);
        } catch (Exception e) {
            // Never leak the API key; callers decide whether email failure is fatal.
            log.error("Brevo send failed for {}: {}", toEmail, e.getMessage());
            throw new EmailDeliveryException("Could not deliver email");
        }
    }

    public static class EmailDeliveryException extends RuntimeException {
        public EmailDeliveryException(String message) { super(message); }
    }
}
