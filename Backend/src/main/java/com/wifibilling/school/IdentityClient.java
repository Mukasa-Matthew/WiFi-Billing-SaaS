package com.wifibilling.school;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Signed calls to the school identity system's platform API. Schools are addressed by AirPairy tenant ID.
 * See the identity repository's docs/airpairy-integration.md for the contract.
 */
@Slf4j
@Component
public class IdentityClient {
    private static final String BASE_PATH = "/api/platform/schools";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String secret;

    public IdentityClient(@Value("${identity.base-url:}") String baseUrl, @Value("${identity.shared-secret:}") String secret) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.secret = secret;
    }

    public boolean configured() { return !baseUrl.isBlank() && secret.length() >= 32; }

    public JsonNode createSchool(long tenantId, String name, String itEmail, String itName) {
        return call("POST", BASE_PATH, Map.of("tenantId", tenantId, "name", name, "timezone", "Africa/Kampala",
                "itAdmin", Map.of("email", itEmail, "fullName", itName)));
    }
    public JsonNode school(long tenantId) { return call("GET", school(tenantId, ""), null); }
    public JsonNode updateSchool(long tenantId, Map<String, Object> changes) { return call("PATCH", school(tenantId, ""), changes); }
    public JsonNode addAdmin(long tenantId, String email, String fullName, String role) {
        return call("POST", school(tenantId, "/admins"), Map.of("email", email, "fullName", fullName, "role", role));
    }
    public JsonNode resetAdminPassword(long tenantId, UUID admin) { return call("POST", school(tenantId, "/admins/" + admin + "/reset-password"), null); }
    public JsonNode resetAdminTotp(long tenantId, UUID admin) { return call("POST", school(tenantId, "/admins/" + admin + "/reset-totp"), null); }
    public JsonNode setAdminActive(long tenantId, UUID admin, boolean active) { return call("PATCH", school(tenantId, "/admins/" + admin), Map.of("active", active)); }
    /** The tenant owner pausing or restoring an IT account; separate from the super admin's active switch. */
    public JsonNode setAdminOwnerBlocked(long tenantId, UUID admin, boolean blocked) {
        return call("POST", school(tenantId, "/admins/" + admin + "/owner-access"), Map.of("blocked", blocked));
    }
    public JsonNode people(long tenantId, String query, int page) {
        return call("GET", school(tenantId, "/people?query=" + URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8) + "&page=" + page + "&size=20"), null);
    }
    public void unlock(long tenantId, String personId) { call("POST", school(tenantId, "/people/" + URLEncoder.encode(personId, StandardCharsets.UTF_8) + "/unlock"), null); }
    public JsonNode visitorPasses(long tenantId, int page) { return call("GET", school(tenantId, "/visitor-passes?page=" + page + "&size=50"), null); }
    public JsonNode staffExceptions(long tenantId, String status) {
        return call("GET", school(tenantId, "/staff-exceptions" + (status == null ? "" : "?status=" + URLEncoder.encode(status, StandardCharsets.UTF_8))), null);
    }
    public JsonNode decideStaffException(long tenantId, UUID request, boolean approve, String decidedBy) {
        return call("POST", school(tenantId, "/staff-exceptions/" + request + "/decision"), Map.of("approve", approve, "decidedBy", decidedBy));
    }
    public JsonNode registrations(long tenantId, String status, int page) {
        return call("GET", school(tenantId, "/registrations?status=" + URLEncoder.encode(status, StandardCharsets.UTF_8) + "&page=" + Math.max(0, page) + "&size=50"), null);
    }
    public JsonNode decideRegistrations(long tenantId, java.util.List<UUID> ids, boolean approve, String reason, String decidedBy) {
        var body = new java.util.HashMap<String, Object>();
        body.put("ids", ids);
        body.put("approve", approve);
        if (reason != null && !reason.isBlank()) body.put("reason", reason.trim());
        body.put("decidedBy", decidedBy);
        return call("POST", school(tenantId, "/registrations/decision"), body);
    }
    public void correctRegistration(long tenantId, UUID id, String fullName, String registrationNumber, java.time.LocalDate accessUntil, String decidedBy) {
        var body = new java.util.HashMap<String, Object>();
        body.put("decidedBy", decidedBy);
        if (fullName != null) body.put("fullName", fullName);
        if (registrationNumber != null) body.put("registrationNumber", registrationNumber);
        if (accessUntil != null) body.put("accessUntil", accessUntil.toString());
        call("PATCH", school(tenantId, "/registrations/" + id), body);
    }
    public JsonNode verify(long tenantId, String identifier, String pin, String mac) {
        return call("POST", school(tenantId, "/portal/verify"), mac == null ? Map.of("identifier", identifier, "pin", pin) : Map.of("identifier", identifier, "pin", pin, "deviceMac", mac));
    }
    public JsonNode changePin(long tenantId, String pinChangeToken, String newPin) {
        return call("POST", school(tenantId, "/portal/change-pin"), Map.of("pinChangeToken", pinChangeToken, "newPin", newPin));
    }
    public JsonNode admitVisitor(long tenantId, String code, String mac) {
        return call("POST", school(tenantId, "/portal/visitor"), Map.of("code", code, "deviceMac", mac));
    }

    public JsonNode registrationForm(long tenantId) { return call("GET", school(tenantId, "/portal/registration"), null); }
    public JsonNode register(long tenantId, String fullName, String registrationNumber, String email, int completionYear, String mac) {
        return call("POST", school(tenantId, "/portal/register"), Map.of("fullName", fullName, "registrationNumber", registrationNumber, "email", email,
                "completionYear", completionYear, "deviceMac", mac));
    }

    private static String school(long tenantId, String suffix) { return BASE_PATH + "/" + tenantId + suffix; }

    private JsonNode call(String method, String pathAndQuery, Object payload) {
        if (!configured()) throw new IdentityException(503, "IDENTITY_NOT_CONFIGURED", "The school identity system is not configured.", null);
        byte[] body;
        try { body = payload == null ? new byte[0] : mapper.writeValueAsBytes(payload); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Identity request could not be encoded", e); }
        long timestamp = Instant.now().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).timeout(Duration.ofSeconds(10))
                .header("X-Timestamp", String.valueOf(timestamp)).header("X-Nonce", nonce)
                .header("X-Signature", IdentitySignature.sign(secret, IdentitySignature.TO_IDENTITY, timestamp, nonce, method, pathAndQuery, body))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<String> response;
        try { response = http.send(request, HttpResponse.BodyHandlers.ofString()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw IdentityException.unavailable(); }
        catch (Exception e) { log.warn("Identity system unreachable for {} {}: {}", method, pathAndQuery.replaceAll("\\?.*", ""), e.getClass().getSimpleName()); throw IdentityException.unavailable(); }
        JsonNode json;
        try { json = response.body() == null || response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body()); }
        catch (RuntimeException e) { json = mapper.createObjectNode(); }
        if (response.statusCode() >= 200 && response.statusCode() < 300) return json;
        if (response.statusCode() >= 500 || response.statusCode() == 401) {
            log.warn("Identity system rejected {} {} with {}", method, pathAndQuery.replaceAll("\\?.*", ""), response.statusCode());
            throw IdentityException.unavailable();
        }
        throw new IdentityException(response.statusCode(), json.path("code").asString("IDENTITY_ERROR"),
                json.path("detail").asString("The school system could not complete this request."), json);
    }
}
