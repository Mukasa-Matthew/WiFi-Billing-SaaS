package com.wifibilling.util;

import java.util.Locale;

/**
 * Which Ugandan mobile money network a payment went through.
 *
 * <p>The phone prefix is only a first guess made when a payment starts; the provider's own
 * answer in the completion callback ("provider": "mtn" / "airtel") is authoritative and
 * overrides it. Prefixes were confirmed against MarzPay callbacks: 079 numbers settle on
 * MTN and 073 numbers on Airtel.
 */
public final class MobileNetwork {
    public static final String MTN = "MTN";
    public static final String AIRTEL = "AIRTEL";
    public static final String UNKNOWN = "UNKNOWN";
    /** Mobile money on a network other than MTN or Airtel; still mobile money, never walk-in. */
    public static final String OTHER_MOMO = "MOMO";

    private MobileNetwork() {}

    /** The network for a Ugandan phone number, or UNKNOWN. */
    public static String fromPhone(String phone) {
        if (phone == null) return UNKNOWN;
        String clean = phone.replaceAll("[^0-9]", "");
        if (clean.startsWith("256")) clean = clean.substring(3);
        else if (clean.startsWith("0")) clean = clean.substring(1);
        if (clean.startsWith("76") || clean.startsWith("77") || clean.startsWith("78") || clean.startsWith("79")) return MTN;
        if (clean.startsWith("70") || clean.startsWith("73") || clean.startsWith("74") || clean.startsWith("75")) return AIRTEL;
        return UNKNOWN;
    }

    /** MarzPay's provider name ("mtn", "airtel"), or null when it names neither. */
    public static String fromProvider(String provider) {
        if (provider == null) return null;
        String p = provider.trim().toLowerCase(Locale.ROOT);
        if (p.startsWith("mtn")) return MTN;
        if (p.startsWith("airtel")) return AIRTEL;
        return null;
    }

    /**
     * The network a stored payment belongs to: its recorded value, or for older rows saved
     * as UNKNOWN, the phone prefix. Walk-in methods such as CASH are returned unchanged.
     */
    public static String of(String recorded, String phone) {
        String n = recorded == null ? "" : recorded.trim().toUpperCase(Locale.ROOT);
        return n.isEmpty() || UNKNOWN.equals(n) ? fromPhone(phone) : n;
    }

    /**
     * How a stored payment was received: MTN, AIRTEL, MOMO (any other mobile money network),
     * or the hand-recorded method (CASH, BANK, OTHER). Everything that came through the online
     * gateway was mobile money, so it is never reported as anything else.
     */
    public static String ofPayment(String recorded, String phone, String gatewayMode) {
        String n = of(recorded, phone);
        boolean online = gatewayMode != null && !gatewayMode.isBlank();
        return online && !MTN.equals(n) && !AIRTEL.equals(n) ? OTHER_MOMO : n;
    }

    /** True for money received by mobile money on any network. */
    public static boolean isMobileMoney(String received) {
        return MTN.equals(received) || AIRTEL.equals(received) || OTHER_MOMO.equals(received);
    }
}
