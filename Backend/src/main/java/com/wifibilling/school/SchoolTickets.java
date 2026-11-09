package com.wifibilling.school;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;

/**
 * Short-lived proof that a student passed the ID and PIN check on a given device. A school tenant's
 * payment is refused without one, so only verified students can buy, and the voucher is tied to them.
 */
@Component
public class SchoolTickets {
    static final long LIFETIME_SECONDS = 15 * 60;
    private final byte[] key;

    public SchoolTickets(@Value("${identity.shared-secret:}") String secret) {
        key = ("school-ticket:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    public record Ticket(long tenantId, String personId, LocalDate accessUntil, String mac, String fullName) {}

    public String issue(Ticket ticket) {
        long expires = Instant.now().getEpochSecond() + LIFETIME_SECONDS;
        String name = ticket.fullName() == null ? "" : ticket.fullName().replace('|', ' ');
        String payload = ticket.tenantId() + "|" + ticket.personId() + "|" + ticket.accessUntil() + "|" + ticket.mac() + "|" + expires + "|" + name;
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + mac(encoded);
    }

    /** The ticket's student, if it is genuine, unexpired, and for this tenant and device. A name-less 5-field ticket from before the name rode along still works. */
    public Ticket require(String token, long tenantId, String mac) {
        if (token == null || token.length() > 600 || !token.matches("[A-Za-z0-9_-]+\\.[0-9a-f]{64}")) throw invalid();
        String[] parts = token.split("\\.");
        if (!MessageDigest.isEqual(mac(parts[0]).getBytes(StandardCharsets.US_ASCII), parts[1].getBytes(StandardCharsets.US_ASCII))) throw invalid();
        String[] fields = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split("\\|", -1);
        if ((fields.length != 5 && fields.length != 6) || Long.parseLong(fields[4]) < Instant.now().getEpochSecond()
                || Long.parseLong(fields[0]) != tenantId || mac == null || !fields[3].equals(mac)) throw invalid();
        return new Ticket(tenantId, fields[1], LocalDate.parse(fields[2]), fields[3], fields.length == 6 && !fields[5].isEmpty() ? fields[5] : null);
    }

    private String mac(String data) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(hmac.doFinal(data.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Sign in with your school ID number and PIN before buying a package.");
    }
}
