package com.wifibilling.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Retired token-based setup endpoints.
 *
 * The router token is a public identifier: it is embedded in captive portal
 * pages every hotspot customer loads. It used to release the full setup script
 * (RADIUS secret, management password) and accept WireGuard key registration.
 * These routes now refuse unconditionally and build nothing; setup goes through
 * one-time codes at /api/router/setup/{code} (RouterSetupController).
 *
 * Recurring routes for deployed routers under this prefix (management.rsc,
 * acks, hosts) live in RouterManagementController and carry no credentials.
 */
@RestController
@RequestMapping("/api/router/provision")
public class ProvisioningController {

    static final String RETIRED = ":error \"This setup link has been retired. Open your dashboard, choose the router and generate a new setup command.\"\n";

    static ResponseEntity<String> gone() {
        return ResponseEntity.status(HttpStatus.GONE)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_PLAIN)
                .body(RETIRED);
    }

    @GetMapping("/{token}")
    public ResponseEntity<String> provision(@PathVariable String token) { return gone(); }

    @PostMapping("/{token}/complete")
    public ResponseEntity<String> complete(@PathVariable String token) { return gone(); }

    @PostMapping("/{token}/register")
    public ResponseEntity<String> register(@PathVariable String token) { return gone(); }
}
