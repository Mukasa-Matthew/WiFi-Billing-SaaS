package com.wifibilling.service.payment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin MarzPay API client. Every call is made with an explicit credential pair so
 * the same client serves the platform account and any tenant-owned account.
 * Never logs credentials or Authorization headers.
 */
@Slf4j
@Component
public class MarzPayClient {

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;

    public record Credentials(String apiKey, String apiSecret) {}

    public MarzPayClient(@Value("${marzpay.api-base:https://wallet.wearemarz.com/api/v1}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private HttpHeaders headers(Credentials c) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        h.set(HttpHeaders.AUTHORIZATION,
                "Basic " + Base64.getEncoder().encodeToString(
                        (c.apiKey() + ":" + c.apiSecret()).getBytes(StandardCharsets.UTF_8)));
        return h;
    }

    private JsonNode post(String path, Credentials c, Map<String, Object> body) {
        try {
            ResponseEntity<String> res = rest.exchange(baseUrl + path, HttpMethod.POST,
                    new HttpEntity<>(body, headers(c)), String.class);
            return mapper.readTree(res.getBody());
        } catch (HttpStatusCodeException e) {
            throw new MarzPayException(e.getStatusCode().value(), safeBody(e.getResponseBodyAsString()));
        } catch (MarzPayException e) {
            throw e;
        } catch (Exception e) {
            throw new MarzPayException(-1, "MarzPay request failed: " + e.getMessage());
        }
    }

    private JsonNode get(String path, Credentials c) {
        try {
            ResponseEntity<String> res = rest.exchange(baseUrl + path, HttpMethod.GET,
                    new HttpEntity<>(headers(c)), String.class);
            return mapper.readTree(res.getBody());
        } catch (HttpStatusCodeException e) {
            throw new MarzPayException(e.getStatusCode().value(), safeBody(e.getResponseBodyAsString()));
        } catch (MarzPayException e) {
            throw e;
        } catch (Exception e) {
            throw new MarzPayException(-1, "MarzPay request failed: " + e.getMessage());
        }
    }

    private String safeBody(String body) {
        return body == null ? "" : body.substring(0, Math.min(body.length(), 500));
    }

    /** POST /collect-money — initiates a mobile-money collection. Returns parsed response. */
    public JsonNode collectMoney(Credentials c, long amount, String phoneE164, String referenceUuid,
                                 String country, String description, String callbackUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("phone_number", phoneE164);
        body.put("reference", referenceUuid);
        body.put("country", country);
        if (description != null) body.put("description", description.substring(0, Math.min(description.length(), 255)));
        if (callbackUrl != null) body.put("callback_url", callbackUrl);
        return post("/collect-money", c, body);
    }

    /** GET /collect-money/{uuid} — authoritative status check (callback-shaped body). */
    public JsonNode getCollection(Credentials c, String uuid) {
        return get("/collect-money/" + uuid, c);
    }

    /** POST /send-money — disbursement. reference is echoed back as transaction.provider_reference. */
    public JsonNode sendMoney(Credentials c, long amount, String phoneE164, String reference,
                              String country, String description, String callbackUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("phone_number", phoneE164);
        body.put("reference", reference);
        body.put("country", country);
        if (description != null) body.put("description", description.substring(0, Math.min(description.length(), 255)));
        if (callbackUrl != null) body.put("callback_url", callbackUrl);
        return post("/send-money", c, body);
    }

    /** GET /send-money/{uuid} — disbursement status. */
    public JsonNode getDisbursement(Credentials c, String uuid) {
        return get("/send-money/" + uuid, c);
    }

    /** GET /balance?country=XX — merchant wallet balances (IP-whitelisted server side). */
    public JsonNode getBalance(Credentials c, String country) {
        return get("/balance?country=" + country, c);
    }

    /** Validates credentials without moving money. */
    public boolean testConnection(Credentials c, String country) {
        try {
            JsonNode res = getBalance(c, country);
            return res != null && "success".equalsIgnoreCase(res.path("status").asText());
        } catch (MarzPayException e) {
            log.warn("MarzPay connection test failed: HTTP {} {}", e.getStatus(), e.getMessage());
            return false;
        }
    }

    public static class MarzPayException extends RuntimeException {
        private final int status;
        public MarzPayException(int status, String message) {
            super(message);
            this.status = status;
        }
        public int getStatus() { return status; }
    }
}
