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
    private final com.wifibilling.service.PortalRateLimiter rateLimiter;

    public SchoolPortalController(SchoolAccessService access, com.wifibilling.service.PortalRateLimiter rateLimiter) {
        this.access = access;
        this.rateLimiter = rateLimiter;
    }

    public record Verify(@NotBlank @Size(max = 128) String token, @NotBlank @Size(max = 128) String identifier,
                         @NotBlank @Size(max = 8) String pin, @Size(max = 24) String mac) {
        @Override public String toString() { return "Verify[pin=redacted]"; }
    }
    public record ChangePin(@NotBlank @Size(max = 128) String token, @NotBlank @Size(max = 2048) String pinChangeToken,
                            @NotBlank @Size(max = 8) String newPin, @Size(max = 24) String mac) {
        @Override public String toString() { return "ChangePin[redacted]"; }
    }
    public record Visitor(@NotBlank @Size(max = 128) String token, @NotBlank @Size(max = 16) String code, @Size(max = 24) String mac) {}
    public record Resume(@NotBlank @Size(max = 128) String token, @NotBlank @Size(max = 24) String mac) {}
    public record StudentFree(@NotBlank @Size(max = 128) String token, @NotBlank @Size(max = 600) String ticket, @NotBlank @Size(max = 24) String mac) {}
    public record Register(@NotBlank @Size(max = 128) String token, @NotBlank @Size(max = 200) String fullName,
                           @NotBlank @Size(max = 128) String registrationNumber, @NotBlank @jakarta.validation.constraints.Email @Size(max = 254) String email,
                           @jakarta.validation.constraints.NotNull Integer completionYear, @NotBlank @Size(max = 24) String mac) {
        @Override public String toString() { return "Register[redacted]"; }
    }
    public record MovePackage(@NotBlank @Size(max = 128) String token, @NotBlank @Size(max = 600) String ticket,
                              @jakarta.validation.constraints.NotNull Long voucherId, @NotBlank @Size(max = 24) String mac) {}

    @PostMapping("/resume")
    public SchoolAccessService.PortalResult resume(@Valid @RequestBody Resume body) {
        rateLimiter.check("resume", body.mac());
        return access.resume(body.token(), body.mac());
    }

    @PostMapping("/student-free")
    public SchoolAccessService.PortalResult studentFree(@Valid @RequestBody StudentFree body) {
        rateLimiter.check("student-free", body.mac());
        return access.studentFree(body.token(), body.ticket(), body.mac());
    }

    @PostMapping("/move-package")
    public SchoolAccessService.PortalResult movePackage(@Valid @RequestBody MovePackage body) {
        rateLimiter.check("move-package", body.mac());
        return access.movePaidPackage(body.token(), body.ticket(), body.voucherId(), body.mac());
    }

    @PostMapping("/verify")
    public SchoolAccessService.PortalResult verify(@Valid @RequestBody Verify body) {
        rateLimiter.check("verify", body.mac());
        return access.verify(body.token(), body.identifier(), body.pin(), body.mac());
    }

    @PostMapping("/change-pin")
    public SchoolAccessService.PortalResult changePin(@Valid @RequestBody ChangePin body) {
        rateLimiter.check("change-pin", body.mac());
        return access.changePin(body.token(), body.pinChangeToken(), body.newPin(), body.mac());
    }

    /** Whether this school's portal offers "Register as a student", with the completion years to choose from. */
    @GetMapping("/registration")
    public SchoolAccessService.RegistrationForm registration(@RequestParam String token) {
        return access.registrationForm(token);
    }

    @PostMapping("/register")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public java.util.Map<String, String> register(@Valid @RequestBody Register body) {
        rateLimiter.check("register", body.mac());
        access.register(body.token(), body.fullName().trim(), body.registrationNumber().trim(), body.email().trim(), body.completionYear(), body.mac());
        return java.util.Map.of("status", "PENDING");
    }

    @PostMapping("/visitor")
    public SchoolAccessService.PortalResult visitor(@Valid @RequestBody Visitor body) {
        rateLimiter.check("visitor", body.mac());
        return access.visitor(body.token(), body.code(), body.mac());
    }
}
