package com.wifibilling.school;

import com.wifibilling.security.TenantPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The school tenant owner's school settings, visitor log and staff-exception approvals. Never student records. */
@RestController
@RequestMapping("/api/school")
@Validated
public class SchoolController {
    private final SchoolManagementService schools;
    private final IdentityClient identity;

    public SchoolController(SchoolManagementService schools, IdentityClient identity) { this.schools = schools; this.identity = identity; }

    /** Lets the dashboard decide whether to show the School page; safe for every tenant. */
    @GetMapping("/status")
    public Map<String, Object> status(@AuthenticationPrincipal TenantPrincipal principal) {
        try { schools.school(principal.getTenantId()); return Map.of("school", true); }
        catch (org.springframework.web.server.ResponseStatusException notSchool) { return Map.of("school", false); }
    }

    @GetMapping
    public Map<String, Object> overview(@AuthenticationPrincipal TenantPrincipal principal) {
        return schools.overview(schools.school(principal.getTenantId()));
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(@AuthenticationPrincipal TenantPrincipal principal, @Valid @RequestBody SchoolManagementService.SettingsRequest body) {
        return schools.updateSettings(schools.school(principal.getTenantId()), body);
    }

    @PostMapping("/network/enlarge")
    public Map<String, Object> enlargeNetwork(@AuthenticationPrincipal TenantPrincipal principal) {
        return Map.of("routers", schools.enlargeNetwork(schools.school(principal.getTenantId())));
    }

    @GetMapping("/visitor-passes")
    public JsonNode visitorPasses(@AuthenticationPrincipal TenantPrincipal principal, @RequestParam(defaultValue = "0") int page) {
        return identity.visitorPasses(schools.school(principal.getTenantId()).getId(), Math.max(0, page));
    }

    @GetMapping("/staff-exceptions")
    public JsonNode staffExceptions(@AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(required = false) @jakarta.validation.constraints.Pattern(regexp = "PENDING|APPROVED|REJECTED") String status) {
        return identity.staffExceptions(schools.school(principal.getTenantId()).getId(), status);
    }

    public record ItAccess(@NotNull Boolean blocked) {}

    public record NewItAccount(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 200) String fullName,
                               @NotNull @Pattern(regexp = "ADMIN|HELPDESK") String role) {}

    /** Adds another IT account so the owner isn't dependent on the existing IT person. Returns its one-time temporary password. */
    @PostMapping("/it-accounts")
    public JsonNode addItAccount(@AuthenticationPrincipal TenantPrincipal principal, @Valid @RequestBody NewItAccount body) {
        return identity.addAdmin(schools.school(principal.getTenantId()).getId(), body.email(), body.fullName(), body.role());
    }

    /** Pauses or restores an IT person's sign-in to the identity dashboard. A pause signs them out at once. */
    @PostMapping("/it-accounts/{id}/access")
    public JsonNode itAccess(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id, @Valid @RequestBody ItAccess body) {
        return identity.setAdminOwnerBlocked(schools.school(principal.getTenantId()).getId(), id, body.blocked());
    }

    public record Decision(@NotNull Boolean approve) {}

    @PostMapping("/staff-exceptions/{id}/decision")
    public JsonNode decide(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id, @Valid @RequestBody Decision body) {
        var tenant = schools.school(principal.getTenantId());
        return identity.decideStaffException(tenant.getId(), id, body.approve(), principal.getName() + " (" + principal.getUsername() + ")");
    }

    /** Students waiting for IT's approval, so the owner can stand in when IT is busy. */
    @GetMapping("/registrations")
    public JsonNode registrations(@AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(defaultValue = "PENDING") @Pattern(regexp = "PENDING|APPROVED|REJECTED") String status,
            @RequestParam(defaultValue = "") @Size(max = 128) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page) {
        return identity.registrations(schools.school(principal.getTenantId()).getId(), status, q, page);
    }

    public record RegistrationDecision(@NotEmpty @Size(max = 500) List<@NotNull UUID> ids, @NotNull Boolean approve, @Size(max = 500) String reason) {}

    @PostMapping("/registrations/decision")
    public JsonNode decideRegistrations(@AuthenticationPrincipal TenantPrincipal principal, @Valid @RequestBody RegistrationDecision body) {
        var tenant = schools.school(principal.getTenantId());
        return identity.decideRegistrations(tenant.getId(), body.ids(), body.approve(), body.reason(), principal.getName() + " (" + principal.getUsername() + ")");
    }

    /** Fixes a waiting registration's name, registration number or leaving date, the same correction IT can make before approving. */
    public record RegistrationCorrection(@Size(max = 200) String fullName, @Size(max = 128) String registrationNumber, java.time.LocalDate accessUntil) {}

    @PatchMapping("/registrations/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void correctRegistration(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id, @Valid @RequestBody RegistrationCorrection body) {
        var tenant = schools.school(principal.getTenantId());
        if (body.fullName() == null && body.registrationNumber() == null && body.accessUntil() == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Nothing to change");
        }
        identity.correctRegistration(tenant.getId(), id, body.fullName(), body.registrationNumber(), body.accessUntil(), principal.getName() + " (" + principal.getUsername() + ")");
    }
}
