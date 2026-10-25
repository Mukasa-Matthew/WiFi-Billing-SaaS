package com.wifibilling.service;

import com.wifibilling.model.RouterSetupCode;
import com.wifibilling.model.RouterSetupCode.Purpose;
import com.wifibilling.repository.RouterSetupCodeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Issues and redeems router setup codes. This is the only path to the
 * credential-bearing provisioning script; the router's public identifier
 * (Router.token) never authorizes it.
 *
 * Every failure is a 404 with the same message, so callers can't tell an
 * expired, used, revoked or unknown code apart.
 */
@Service
public class RouterSetupService {

    static final Pattern FORMAT = Pattern.compile("^su_[A-Za-z0-9_-]{32}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String INVALID = "Setup link is invalid or has expired. Generate a new one from your dashboard.";

    public record Issued(String code, OffsetDateTime expiresAt) {}

    private final RouterSetupCodeRepository codes;
    private final Clock clock;
    private final Duration ttl;
    private final Duration callbackWindow;

    @Autowired
    public RouterSetupService(RouterSetupCodeRepository codes,
                              @Value("${provisioning.setup-ttl-minutes:30}") long ttlMinutes,
                              @Value("${provisioning.callback-window-minutes:120}") long callbackMinutes) {
        this(codes, Clock.systemUTC(), Duration.ofMinutes(ttlMinutes), Duration.ofMinutes(callbackMinutes));
    }

    RouterSetupService(RouterSetupCodeRepository codes, Clock clock, Duration ttl, Duration callbackWindow) {
        this.codes = codes;
        this.clock = clock;
        this.ttl = ttl;
        this.callbackWindow = callbackWindow;
    }

    private OffsetDateTime now() { return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC); }

    /** New code for one router; any earlier unfinished code for it stops working. */
    @Transactional
    public Issued issue(com.wifibilling.model.Router router, Purpose purpose) {
        OffsetDateTime now = now();
        codes.revokeOutstanding(router.getId(), now);
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String code = "su_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        codes.save(RouterSetupCode.builder()
                .router(router).codeHash(hash(code)).purpose(purpose)
                .createdAt(now).expiresAt(now.plus(ttl))
                .build());
        return new Issued(code, now.plus(ttl));
    }

    /**
     * Releases the script for this code exactly once and returns the router id.
     * Callers must build the response in the same transaction (see
     * ProvisioningService.buildScriptForSetup) so a failure rolls the redemption back.
     */
    @Transactional
    public Long redeem(String code, Purpose purpose) {
        RouterSetupCode c = find(code);
        OffsetDateTime now = now();
        if (c.getPurpose() != purpose || c.getRevokedAt() != null || c.getCompletedAt() != null
                || c.getScriptFetchedAt() != null || !now.isBefore(c.getExpiresAt())) {
            throw invalid();
        }
        Long routerId = c.getRouter().getId();
        if (codes.markScriptFetched(c.getId(), now) != 1) throw invalid(); // lost a concurrent race
        return routerId;
    }

    /** The router's own register/complete calls after it fetched a FULL script. */
    @Transactional(readOnly = true)
    public Long authorizeCallback(String code) {
        RouterSetupCode c = find(code);
        if (c.getPurpose() != Purpose.FULL || c.getRevokedAt() != null || c.getCompletedAt() != null
                || c.getScriptFetchedAt() == null || !now().isBefore(c.getScriptFetchedAt().plus(callbackWindow))) {
            throw invalid();
        }
        return c.getRouter().getId();
    }

    /**
     * Key registration for this setup. The first key registered is bound to
     * the code; the router's own retries repeat the same key and succeed, any
     * different key is refused, so a leaked code cannot replace the router's
     * WireGuard peer.
     */
    @Transactional
    public Long authorizeRegistration(String code, String publicKey) {
        Long routerId = authorizeCallback(code);
        RouterSetupCode c = find(code);
        String keyHash = hash(publicKey == null ? "" : publicKey.trim());
        if (c.getRegisteredKeyHash() == null && codes.bindRegisteredKey(c.getId(), keyHash) == 1) return routerId;
        RouterSetupCode current = find(code);
        if (!keyHash.equals(current.getRegisteredKeyHash())) throw invalid();
        return routerId;
    }

    /** Closes the code: no further callbacks are accepted. */
    @Transactional
    public Long complete(String code) {
        Long routerId = authorizeCallback(code);
        RouterSetupCode c = find(code);
        c.setCompletedAt(now());
        codes.save(c);
        return routerId;
    }

    private RouterSetupCode find(String code) {
        if (code == null || !FORMAT.matcher(code).matches()) throw invalid();
        return codes.findByCodeHash(hash(code)).orElseThrow(RouterSetupService::invalid);
    }

    static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, INVALID);
    }
}
