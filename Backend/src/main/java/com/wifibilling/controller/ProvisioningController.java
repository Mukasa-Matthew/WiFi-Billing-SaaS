package com.wifibilling.controller;

import com.wifibilling.dto.RegisterKeyRequest;
import com.wifibilling.dto.RegisterKeyResponse;
import com.wifibilling.service.ProvisioningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Router onboarding endpoints. On the MikroTik:
 * <pre>
 * /tool fetch url="https://api.example.com/api/router/provision/TOKEN" dst-path=wb.rsc
 * /import wb.rsc
 * </pre>
 */
@RestController
@RequestMapping("/api/router/provision")
@RequiredArgsConstructor
public class ProvisioningController {

    private final ProvisioningService provisioningService;

    @GetMapping(value = "/{token}", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> provision(@PathVariable String token) {
        String script = provisioningService.buildProvisioningScript(token);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore()) // contains the RADIUS secret
                .header("Content-Disposition", "inline; filename=\"wifi-billing.rsc\"")
                .body(script);
    }

    @PostMapping(value = "/{token}/complete")
    public ResponseEntity<Void> complete(@PathVariable String token) {
        provisioningService.markComplete(token);
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/{token}/register",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RegisterKeyResponse register(@PathVariable String token, @Valid @RequestBody RegisterKeyRequest body) {
        return provisioningService.registerPublicKey(token, body.publicKey());
    }
}
