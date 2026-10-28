package com.wifibilling.util;

import com.wifibilling.model.Voucher;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Voucher access state shared by the user directory and the customer list. */
public final class AccessState {
    private AccessState() {}

    /** Stored expiry, else first login plus the package's session time; null until first use. */
    public static OffsetDateTime expiry(Voucher v, OffsetDateTime firstLogin) {
        if (v.getExpiresAt() != null) return v.getExpiresAt();
        return firstLogin == null ? null : firstLogin.plusSeconds(v.getPackagePlan().getSessionTimeout());
    }

    /** revoked, expired, active (in use), or the stored status (e.g. unused). */
    public static String status(Voucher v, OffsetDateTime firstLogin, OffsetDateTime expiry, OffsetDateTime now) {
        if ("revoked".equals(v.getStatus())) return "revoked";
        if (expiry != null && !expiry.isAfter(now)) return "expired";
        return firstLogin != null ? "active" : v.getStatus();
    }

    /** Native-query timestamp column to a UTC OffsetDateTime. */
    public static OffsetDateTime toUtc(Object v) {
        return v == null ? null
                : v instanceof OffsetDateTime o ? o
                : v instanceof java.time.Instant i ? i.atOffset(ZoneOffset.UTC)
                : v instanceof java.sql.Timestamp ts ? ts.toLocalDateTime().atOffset(ZoneOffset.UTC)
                : v instanceof java.sql.Date d ? d.toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC)
                : null;
    }
}
