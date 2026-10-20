package com.wifibilling.util;

import java.util.regex.Pattern;

/**
 * Router provisioning tokens are embedded verbatim into RouterOS scripts and JSON,
 * so we only ever accept a strict URL/script-safe alphabet.
 */
public final class RouterTokens {

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private RouterTokens() {
    }

    public static boolean isValid(String token) {
        return token != null && VALID.matcher(token).matches();
    }
}
