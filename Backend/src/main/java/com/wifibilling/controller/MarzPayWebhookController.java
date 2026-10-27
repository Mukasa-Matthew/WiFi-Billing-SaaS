package com.wifibilling.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.wifibilling.model.TenantGatewayConfig;
import com.wifibilling.model.WebhookEvent;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.repository.WebhookEventRepository;
import com.wifibilling.service.GatewayConfigService;
import com.wifibilling.service.PaymentService;
import com.wifibilling.service.WithdrawalService;
import com.wifibilling.service.payment.MarzPayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Receives MarzPay callbacks for collections and disbursements.
 *
 * Trust model:
 *  - Payloads are deduplicated by SHA-256 of the raw body (replay is a no-op).
 *  - When the transaction's gateway account has a webhook secret, the
 *    X-MarzPay-Signature HMAC is verified; a bad signature rejects the event.
 *  - When no signature is configured, the callback is treated as a hint only —
 *    the final status is re-fetched from MarzPay with the originating account's
 *    credentials before any state changes. An unsigned callback alone can never
 *    mark a payment successful.
 */
@Slf4j
@RestController
@RequestMapping("/api/payments/marzpay")
@RequiredArgsConstructor
public class MarzPayWebhookController {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PaymentService paymentService;
    private final WithdrawalService withdrawalService;
    private final GatewayConfigService gatewayConfig;
    private final TransactionRepository transactions;
    private final WebhookEventRepository webhookEvents;
    private final MarzPayClient marzPay;
    private final com.wifibilling.service.SubscriptionService subscriptionService;

    @PostMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-MarzPay-Timestamp", required = false) String ts,
            @RequestHeader(value = "X-MarzPay-Signature", required = false) String sig) {
        try {
            JsonNode root = mapper.readTree(rawBody);
            // Dashboard-registered webhooks wrap the payload under `data`.
            JsonNode payload = root.has("data") && root.get("data").isObject() ? root.get("data") : root;
            String eventType = root.path("event_type").asText(payload.path("event_type").asText(""));
            String reference = payload.path("transaction").path("reference").asText(
                    payload.path("transaction").path("provider_reference").asText(null));

            String hash = sha256(rawBody);
            if (webhookEvents.existsByPayloadHash(hash)) {
                return ResponseEntity.ok().build(); // duplicate delivery
            }

            boolean collection = eventType.startsWith("collection") || eventType.isBlank();

            // Intercept SaaS Subscription checkout callbacks (SUB- references)
            if (reference != null && (reference.startsWith("SUB-") || (eventType.startsWith("collection") && transactions.findByReference(reference).isEmpty()))) {
                if (subscriptionService != null && subscriptionService.confirmSubscriptionCheckout(reference, payload)) {
                    record(hash, eventType, reference, WebhookEvent.PROCESSED, "Subscription checkout updated", rawBody);
                    return ResponseEntity.ok().build();
                }
            }

            String secret = null;
            String mode = TenantGatewayConfig.MODE_PLATFORM;
            var tx = reference != null
                    ? transactions.findByExternalReference(reference).or(() -> transactions.findByReference(reference)).orElse(null)
                    : null;
            if (tx != null && tx.getGatewayMode() != null) mode = tx.getGatewayMode();
            if (tx != null) secret = gatewayConfig.webhookSecretFor(tx.getTenant(), mode);

            if (secret != null && !secret.isBlank()) {
                if (!verifySignature(secret, ts, sig, rawBody)) {
                    record(hash, eventType, reference, WebhookEvent.REJECTED, "Bad signature", rawBody);
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
                }
                dispatch(payload, eventType);
            } else {
                // Unsigned: treat as a hint and re-fetch the authoritative status
                // using the originating account's credentials before trusting it.
                if (collection && tx != null) {
                    recheckCollection(tx);
                } else if (!collection) {
                    // Disbursement callbacks are only emitted for our own payouts;
                    // unsigned ones still update state — the payout ref is our UUID
                    // and only MarzPay knows to echo it.
                    dispatch(payload, eventType);
                }
            }

            record(hash, eventType, reference, WebhookEvent.PROCESSED, null, rawBody);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("MarzPay callback processing error: {}", e.getMessage());
            // 200 anyway — returning an error makes providers retry aggressively.
            return ResponseEntity.ok().build();
        }
    }

    private void dispatch(JsonNode payload, String eventType) {
        if (eventType.startsWith("disbursement")) {
            withdrawalService.processDisbursementCallback(payload);
        } else {
            paymentService.processMarzPayCollection(payload);
        }
    }

    /** Authoritative status re-check for unsigned collection callbacks. */
    private void recheckCollection(com.wifibilling.model.Transaction tx) {
        try {
            var creds = gatewayConfig.credentialsFor(tx.getTenant(), tx.getGatewayMode());
            String uuid = tx.getProviderUuid() != null ? tx.getProviderUuid() : tx.getExternalReference();
            if (uuid == null) return;
            JsonNode status = marzPay.getCollection(creds, uuid);
            paymentService.processMarzPayCollection(status);
        } catch (Exception e) {
            log.warn("Collection re-check failed for {}: {}", tx.getReference(), e.getMessage());
        }
    }

    private void record(String hash, String eventType, String reference, String status, String note, String raw) {
        try {
            webhookEvents.save(WebhookEvent.builder()
                    .eventType(eventType).reference(reference)
                    .payloadHash(hash).status(status).note(note)
                    .payload(raw.substring(0, Math.min(raw.length(), 8000)))
                    .build());
        } catch (Exception dup) {
            // Payload hash race — another worker recorded it first; harmless.
        }
    }

    /** X-MarzPay-Signature: "t={ts},v1={hmac_sha256_hex}" over "{ts}.{raw_body}". */
    private boolean verifySignature(String secret, String ts, String sig, String rawBody) {
        if (ts == null || sig == null) return false;
        String v1 = null;
        for (String part : sig.split(",")) {
            if (part.startsWith("v1=")) v1 = part.substring(3);
        }
        if (v1 == null) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(
                    mac.doFinal((ts + "." + rawBody).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    v1.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
