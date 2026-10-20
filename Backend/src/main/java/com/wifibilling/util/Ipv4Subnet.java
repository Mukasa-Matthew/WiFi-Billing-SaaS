package com.wifibilling.util;

import java.util.regex.Pattern;

/**
 * Minimal IPv4 CIDR helper (no external dependencies).
 */
public record Ipv4Subnet(long network, int prefix) {

    private static final Pattern IPV4 =
            Pattern.compile("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

    public static Ipv4Subnet parse(String cidr) {
        String[] parts = cidr.trim().split("/");
        if (parts.length != 2 || !isIpv4(parts[0])) {
            throw new IllegalArgumentException("Invalid IPv4 CIDR: " + cidr);
        }
        int prefix = Integer.parseInt(parts[1]);
        if (prefix < 8 || prefix > 30) {
            throw new IllegalArgumentException("Unsupported prefix length: " + prefix);
        }
        long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return new Ipv4Subnet(toLong(parts[0]) & mask, prefix);
    }

    public long broadcast() {
        return network | ((1L << (32 - prefix)) - 1);
    }

    public boolean contains(String ip) {
        if (!isIpv4(ip)) return false;
        long v = toLong(ip);
        return v >= network && v <= broadcast();
    }

    public static boolean isIpv4(String s) {
        return s != null && IPV4.matcher(s).matches();
    }

    /** Strips an optional "/prefix" suffix, e.g. "10.9.0.5/16" -> "10.9.0.5". */
    public static String stripPrefix(String ip) {
        if (ip == null) return null;
        int slash = ip.indexOf('/');
        return (slash >= 0 ? ip.substring(0, slash) : ip).trim();
    }

    public static long toLong(String ip) {
        long v = 0;
        for (String octet : ip.split("\\.")) {
            v = (v << 8) | Integer.parseInt(octet);
        }
        return v;
    }

    public static String toIp(long v) {
        return ((v >> 24) & 0xFF) + "." + ((v >> 16) & 0xFF) + "." + ((v >> 8) & 0xFF) + "." + (v & 0xFF);
    }
}
