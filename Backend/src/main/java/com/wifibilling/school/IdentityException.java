package com.wifibilling.school;

import tools.jackson.databind.JsonNode;

/** A problem reported by the identity system, carrying its stable code and user-facing detail. */
public class IdentityException extends RuntimeException {
    private final int status;
    private final String code;
    private final transient JsonNode body;

    public IdentityException(int status, String code, String detail, JsonNode body) {
        super(detail);
        this.status = status;
        this.code = code;
        this.body = body;
    }

    public int status() { return status; }
    public String code() { return code; }
    public JsonNode body() { return body; }

    public static IdentityException unavailable() {
        return new IdentityException(503, "IDENTITY_UNAVAILABLE", "School sign-in is temporarily unavailable. Please try again shortly.", null);
    }
}
