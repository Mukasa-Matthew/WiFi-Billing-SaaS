package com.wifibilling.school;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

/** Public captive-portal endpoints for school tenants. The router token decides the school; client input never does. */
@RestController
@RequestMapping("/api/portal/school")
public class SchoolPortalController {
    private final SchoolAccessService access;

    public SchoolPortalController(SchoolAccessService access) { this.access = access; }

    public record Verify(@NotBlank @Size(max = 128) String token, @NotBlank @Size(max = 128) String identifier,
                         @NotBlank @Size(max = 8) String pin, @Size(max = 24) String mac) {
        @Override public String toString() { return "Verify[pin=redacted]"; }
    }
    public record ChangePin(@NotBlank @Size(max = 128) String token, @NotBlank @Size(max = 2048) String pinChangeToken,
                            @NotBlank @Size(max = 8) String newPin, @Size(max = 24) String mac) {
        @Override public String toString() { return "ChangePin[redacted]"; }
    }
    public record Visitor(@NotBlank @Size(max = 128) String token, @NotBlank @Size(max = 16) String code, @Size(max = 24) String mac) {}

    @PostMapping("/verify")
    public SchoolAccessService.PortalResult verify(@Valid @RequestBody Verify body) {
        return access.verify(body.token(), body.identifier(), body.pin(), body.mac());
    }

    @PostMapping("/change-pin")
    public SchoolAccessService.PortalResult changePin(@Valid @RequestBody ChangePin body) {
        return access.changePin(body.token(), body.pinChangeToken(), body.newPin(), body.mac());
    }

    @PostMapping("/visitor")
    public SchoolAccessService.PortalResult visitor(@Valid @RequestBody Visitor body) {
        return access.visitor(body.token(), body.code(), body.mac());
    }
}
