package com.wifibilling.util;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Matches a phone number however it was stored: portal payments keep +256…, walk-in
 * sales keep what staff typed (0772…, 256…, spaced). {@code exact} lists the usual
 * spellings; {@code suffix} (a LIKE pattern on the significant digits) catches the rest.
 */
public record PhoneMatch(String display, Set<String> exact, String suffix) {

    /** Fewer digits than this would match unrelated numbers. */
    static final int MIN_DIGITS = 7;

    public static Optional<PhoneMatch> of(String raw) {
        if (raw == null) return Optional.empty();
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() < MIN_DIGITS) return Optional.empty();
        String local = localPhone(digits);
        Set<String> exact = new LinkedHashSet<>();
        exact.add(raw.trim());
        if (local != null) {
            exact.addAll(ugandanVariants(local));
            return Optional.of(new PhoneMatch(local, exact, "%" + local.substring(1)));
        }
        exact.add(digits);
        exact.add("+" + digits);
        return Optional.of(new PhoneMatch(digits, exact, "%" + digits));
    }

    /** The same test in memory, for rows already loaded. */
    public boolean matches(String stored) {
        if (stored == null) return false;
        return exact.contains(stored) || stored.replaceAll("[^0-9]", "").endsWith(suffix.substring(1));
    }

    /** Ugandan numbers in local form (0772270145); null for MANUAL and other non-numbers. */
    public static String localPhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("256") && digits.length() == 12) return "0" + digits.substring(3);
        if (digits.startsWith("0") && digits.length() == 10) return digits;
        if (digits.length() == 9 && digits.startsWith("7")) return "0" + digits;
        return null;
    }

    /** Every stored spelling of a local number, so manually entered customers match too. */
    public static Set<String> ugandanVariants(String local) {
        String rest = local.substring(1);
        Set<String> out = new LinkedHashSet<>();
        out.add(local);
        out.add("256" + rest);
        out.add("+256" + rest);
        out.add("+256 " + rest.substring(0, 3) + " " + rest.substring(3, 6) + " " + rest.substring(6));
        out.add(local.substring(0, 4) + " " + rest.substring(3, 6) + " " + rest.substring(6));
        return out;
    }
}
