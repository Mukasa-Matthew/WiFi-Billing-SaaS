package com.wifibilling.controller;

import com.wifibilling.dto.RegisterKeyRequest;
import com.wifibilling.dto.RegisterKeyResponse;
import com.wifibilling.service.ProvisioningService;
import com.wifibilling.service.RouterManagementService;
import com.wifibilling.service.RouterSetupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * One-time router setup, authorized by a code the owner generates in the
 * dashboard (POST /api/routers/{id}/setup-code). On the MikroTik:
 * <pre>
 * /tool fetch url="https://HOST/api/router/setup/su_..." dst-path=wb.rsc
 * /import wb.rsc
 * </pre>
 * The script is released once; the code then authorizes only that setup's
 * register/complete callbacks for a bounded window.
 */
@RestController
@RequestMapping("/api/router/setup")
@RequiredArgsConstructor
public class RouterSetupController {

    private final RouterSetupService setup;
    private final ProvisioningService provisioning;
    private final RouterManagementService management;

    /** Credential-bearing responses: never cached, never sent as a referrer. */
    private static ResponseEntity<String> sensitive(String body, String filename) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Disposition", "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
    }

    @GetMapping("/{code}")
    public ResponseEntity<String> script(@PathVariable String code) {
        return sensitive(provisioning.buildScriptForSetup(code), "wifi-billing.rsc");
    }

    @PostMapping(value = "/{code}/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public RegisterKeyResponse register(@PathVariable String code, @Valid @RequestBody RegisterKeyRequest body) {
        return provisioning.registerPublicKey(setup.authorizeRegistration(code, body.publicKey()), body.publicKey());
    }

    @PostMapping("/{code}/complete")
    public ResponseEntity<Void> complete(@PathVariable String code) {
        provisioning.markComplete(setup.complete(code));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{code}/install-management.rsc")
    public ResponseEntity<String> installManagement(@PathVariable String code) {
        return sensitive(management.installerForSetup(code), "wb-management.rsc");
    }
}
