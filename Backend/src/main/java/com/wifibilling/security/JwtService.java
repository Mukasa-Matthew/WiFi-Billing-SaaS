package com.wifibilling.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationHours;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-hours:24}") long expirationHours) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be configured");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationHours = expirationHours;
    }

    public String generateToken(Long tenantId, String email, String role) {
        return generateToken(tenantId, email, role, null);
    }

    public String generateToken(Long tenantId, String email, String role, String sessionId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(email)
                .claim("tenantId", tenantId)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationHours, ChronoUnit.HOURS)));

        if (sessionId != null) {
            builder.claim("sessionId", sessionId);
        }

        return builder.signWith(key).compact();
    }

    /**
     * Issues a short-lived (5 min) restricted challenge token used strictly to complete 2FA.
     */
    public String generate2faChallengeToken(Long tenantId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(email)
                .claim("tenantId", tenantId)
                .claim("purpose", "2FA_CHALLENGE")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(5, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractEmail(String token) {
        return extractAllClaims(token).getSubject();
    }

    public Long extractTenantId(String token) {
        Object tid = extractAllClaims(token).get("tenantId");
        if (tid instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(tid.toString());
    }

    public String extractRole(String token) {
        Object role = extractAllClaims(token).get("role");
        return role != null ? role.toString() : "ROLE_ADMIN";
    }

    public String extractSessionId(String token) {
        Object sid = extractAllClaims(token).get("sessionId");
        return sid != null ? sid.toString() : null;
    }

    public String extractPurpose(String token) {
        Object p = extractAllClaims(token).get("purpose");
        return p != null ? p.toString() : null;
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return claims.getExpiration().after(new Date());
        } catch (Exception e) {
            return false;
        }
    }
}
