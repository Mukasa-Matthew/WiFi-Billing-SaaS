package com.wifibilling.controller;

import com.wifibilling.dto.PaymentDto;
import com.wifibilling.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping(value = "/initiate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PaymentDto.InitiateResponse initiate(@Valid @RequestBody PaymentDto.InitiateRequest body) {
        return paymentService.initiatePayment(body);
    }

    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> webhook(@Valid @RequestBody PaymentDto.WebhookRequest body) {
        paymentService.processWebhook(body);
        return ResponseEntity.ok().build();
    }

    @GetMapping(value = "/status/{reference}", produces = MediaType.APPLICATION_JSON_VALUE)
    public PaymentDto.StatusResponse getStatus(@PathVariable String reference) {
        return paymentService.getStatus(reference);
    }
}
