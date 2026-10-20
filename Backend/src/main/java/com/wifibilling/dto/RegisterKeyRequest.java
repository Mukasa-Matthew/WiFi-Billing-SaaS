package com.wifibilling.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Body posted by the router after it generates its WireGuard keypair. */
public record RegisterKeyRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9+/]{43}=$", message = "must be a base64 WireGuard public key")
        String publicKey) {
}
