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

    /** STUDENT, STUDENT_FREE (daily free study data), STAFF, VISITOR, or PAID_CUSTOMER for a voucher. */
    public static String category(Voucher v) {
        if (v == null) return "PAID_CUSTOMER";
        if ("STUDENT".equals(v.getSchoolCategory()) && "SCHOOL_STUDENT_FREE".equals(v.getSaleType())) return "STUDENT_FREE";
        if (v.getSchoolCategory() != null && !v.getSchoolCategory().isBlank()) return v.getSchoolCategory();
        if ("SCHOOL_STAFF".equals(v.getSaleType())) return "STAFF";
        if ("SCHOOL_VISITOR".equals(v.getSaleType())) return "VISITOR";
        return "PAID_CUSTOMER";
    }

    /** Human-readable label for a voucher's connection category. */
    public static String categoryLabel(Voucher v) {
        if (v != null && "GIVEAWAY".equals(v.getSaleType())) return "Giveaway";
        return switch (category(v)) {
            case "STUDENT" -> "Paid Student";
            case "STUDENT_FREE" -> "Free Student";
            case "STAFF" -> "School Staff";
            case "VISITOR" -> "Visitor Pass";
            default -> "Paid Customer";
        };
    }

    /** Display name for the connected user without exposing private student records. */
    public static String displayName(Voucher v) {
        if (v == null) return "Paid customer";
        if (v.getCustomer() != null && v.getCustomer().getName() != null && !v.getCustomer().getName().isBlank()) {
            return v.getCustomer().getName();
        }
        if (v.getPersonName() != null && !v.getPersonName().isBlank()) return v.getPersonName();
        if ("GIVEAWAY".equals(v.getSaleType())) return "Giveaway";
        return switch (category(v)) {
            case "STUDENT" -> "Paid Student";
            case "STUDENT_FREE" -> "Free Student";
            case "STAFF" -> "School Staff";
            case "VISITOR" -> "School Visitor";
            default -> "Paid Customer";
        };
    }
}
