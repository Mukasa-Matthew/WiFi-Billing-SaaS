package com.wifibilling.school;

import com.wifibilling.repository.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

/** Super-admin support for school tenants: link a school, pause it or its self-registration, manage its IT accounts, look people up and unlock them. */
@RestController
@RequestMapping("/api/admin/tenants/{tenantId}/school")
@Validated
public class AdminSchoolController {
    private final SchoolManagementService schools;
    private final IdentityClient identity;
    private final TenantRepository tenants;
    private final SchoolAccessService access;

    public AdminSchoolController(SchoolManagementService schools, IdentityClient identity, TenantRepository tenants, SchoolAccessService access) {
        this.schools = schools; this.identity = identity; this.tenants = tenants; this.access = access;
    }

    public record Link(@NotBlank @Email @Size(max = 254) String itAdminEmail, @NotBlank @Size(max = 200) String itAdminName) {}
    public record NewAdmin(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 200) String fullName, @NotNull @Pattern(regexp = "ADMIN|HELPDESK") String role) {}
    public record Active(@NotNull Boolean active) {}
    /** Pausing the school, or forcing student self-registration off whatever the school's IT person has chosen. */
    public record SchoolChanges(Boolean active, Boolean registrationBlocked) {}

    /** Turns an existing ordinary tenant into a school. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SchoolManagementService.Credentials link(@PathVariable Long tenantId, @Valid @RequestBody Link body) {
        var tenant = tenants.findById(tenantId).filter(t -> !"ROLE_SUPERADMIN".equals(t.getRole()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        return schools.link(tenant, body.itAdminEmail(), body.itAdminName());
    }

    @GetMapping
    public Map<String, Object> overview(@PathVariable Long tenantId) { return schools.overview(schools.school(tenantId)); }

    @PatchMapping
    public JsonNode update(@PathVariable Long tenantId, @RequestBody SchoolChanges body) {
        var changes = new java.util.LinkedHashMap<String, Object>();
        if (body.active() != null) changes.put("active", body.active());
        if (body.registrationBlocked() != null) changes.put("registrationBlocked", body.registrationBlocked());
        if (changes.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to change");
        JsonNode school = identity.updateSchool(schools.school(tenantId).getId(), changes);
        access.forgetRegistrationForm(tenantId);
        return school;
    }

    @PostMapping("/admins")
    @ResponseStatus(HttpStatus.CREATED)
    public SchoolManagementService.Credentials addAdmin(@PathVariable Long tenantId, @Valid @RequestBody NewAdmin body) {
        var tenant = schools.school(tenantId);
        JsonNode created = identity.addAdmin(tenant.getId(), body.email().trim().toLowerCase(), body.fullName().trim(), body.role());
        return schools.deliver(body.fullName().trim(), tenant.getName(), created.path("admin").path("email").asString(), created.path("temporaryPassword").asString());
    }

    @PostMapping("/admins/{adminId}/reset-password")
    public SchoolManagementService.Credentials resetPassword(@PathVariable Long tenantId, @PathVariable UUID adminId) {
        var tenant = schools.school(tenantId);
        JsonNode reset = identity.resetAdminPassword(tenant.getId(), adminId);
        return schools.deliver(reset.path("admin").path("fullName").asString(), tenant.getName(), reset.path("admin").path("email").asString(), reset.path("temporaryPassword").asString());
    }

    @PostMapping("/admins/{adminId}/reset-totp")
    public JsonNode resetTotp(@PathVariable Long tenantId, @PathVariable UUID adminId) { return identity.resetAdminTotp(schools.school(tenantId).getId(), adminId); }

    @PatchMapping("/admins/{adminId}")
    public JsonNode setAdminActive(@PathVariable Long tenantId, @PathVariable UUID adminId, @Valid @RequestBody Active body) {
        return identity.setAdminActive(schools.school(tenantId).getId(), adminId, body.active());
    }

    @GetMapping("/people")
    public JsonNode people(@PathVariable Long tenantId, @RequestParam(defaultValue = "") @Size(max = 128) String query, @RequestParam(defaultValue = "0") int page) {
        return identity.people(schools.school(tenantId).getId(), query, Math.max(0, page));
    }

    @PostMapping("/people/{personId}/unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlock(@PathVariable Long tenantId, @PathVariable @Size(max = 80) String personId) { identity.unlock(schools.school(tenantId).getId(), personId); }
}
