package com.wifibilling.controller;

import com.wifibilling.dto.PaymentDto;
import com.wifibilling.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final String webhookSecret;

    public PaymentController(
            PaymentService paymentService,
            @Value("${payment.webhook.secret:}") String webhookSecret) {
        this.paymentService = paymentService;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping(value = "/initiate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PaymentDto.InitiateResponse initiate(@Valid @RequestBody PaymentDto.InitiateRequest body) {
        try {
            return paymentService.initiatePayment(body);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // Two requests for the same checkout raced on the unique checkout_ref.
            // The original rolled back; retrying in a fresh transaction resumes
            // the winner — still exactly one transaction and one collection.
            return paymentService.initiatePayment(body);
        }
    }

    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> webhook(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String suppliedSecret,
            @Valid @RequestBody PaymentDto.WebhookRequest body) {
        if (webhookSecret.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (suppliedSecret == null || !MessageDigest.isEqual(
                webhookSecret.getBytes(StandardCharsets.UTF_8),
                suppliedSecret.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        paymentService.processWebhook(body);
        return ResponseEntity.ok().build();
    }

    @GetMapping(value = "/status/{reference}", produces = MediaType.APPLICATION_JSON_VALUE)
    public PaymentDto.StatusResponse getStatus(@PathVariable String reference) {
        return paymentService.getStatus(reference);
    }
}
