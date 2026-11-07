package com.wifibilling.school;

import com.wifibilling.model.Tenant;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.service.EmailService;
import com.wifibilling.service.EmailTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Links tenants to schools in the identity system and keeps the owner's school settings in step with it. */
@Slf4j
@Service
public class SchoolManagementService {
    private final IdentityClient identity;
    private final TenantRepository tenants;
    private final SchoolAccessService access;
    private final EmailService email;
    private final EmailTemplates templates;
    private final String dashboardUrl;

    public SchoolManagementService(IdentityClient identity, TenantRepository tenants, SchoolAccessService access, EmailService email,
                                   EmailTemplates templates, @Value("${identity.dashboard-url:}") String dashboardUrl) {
        this.identity = identity; this.tenants = tenants; this.access = access; this.email = email; this.templates = templates; this.dashboardUrl = dashboardUrl;
    }

    /** The IT person's temporary password is emailed; it is returned only when email could not be sent. */
    public record Credentials(boolean emailSent, String temporaryPassword) {}

    /** Creates the school in the identity system, then marks the tenant as a school. */
    public Credentials link(Tenant tenant, String itEmail, String itName) {
        if (tenant.isSchool()) throw new ResponseStatusException(HttpStatus.CONFLICT, "This tenant is already a school");
        JsonNode created = identity.createSchool(tenant.getId(), tenant.getName(), itEmail.trim().toLowerCase(), itName.trim());
        tenant.setTenantType("SCHOOL");
        tenant.setSchoolVisitorsEnabled(false);
        tenant.setSchoolVisitorDailyCap(0);
        tenants.save(tenant);
        return deliver(created.path("admin").path("fullName").asString(itName), tenant.getName(),
                created.path("admin").path("email").asString(itEmail), created.path("temporaryPassword").asString());
    }

    public Credentials deliver(String fullName, String schoolName, String address, String temporaryPassword) {
        if (email.configured()) {
            try {
                email.send(address, "Your school WiFi dashboard account", templates.schoolItAccountEmail(fullName, schoolName, address, temporaryPassword, dashboardUrl));
                return new Credentials(true, null);
            } catch (EmailService.EmailDeliveryException e) {
                log.warn("School IT credentials email to tenant {} could not be sent", schoolName);
            }
        }
        return new Credentials(false, temporaryPassword);
    }

    public Tenant school(Long tenantId) {
        return tenants.findById(tenantId).filter(Tenant::isSchool)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This account is not a school"));
    }

    /** Local settings plus the identity system's live summary (counts, visitor switch, IT accounts). */
    public Map<String, Object> overview(Tenant tenant) {
        var result = new LinkedHashMap<String, Object>();
        result.put("settings", settings(tenant));
        result.put("school", identity.school(tenant.getId()));
        result.put("dashboardUrl", dashboardUrl);
        return result;
    }

    public Map<String, Object> settings(Tenant tenant) {
        var result = new LinkedHashMap<String, Object>();
        result.put("visitorsEnabled", Boolean.TRUE.equals(tenant.getSchoolVisitorsEnabled()));
        result.put("visitorDailyCap", tenant.getSchoolVisitorDailyCap() == null ? 0 : tenant.getSchoolVisitorDailyCap());
        result.put("staffRateLimit", tenant.getSchoolStaffRateLimit());
        result.put("visitorRateLimit", tenant.getSchoolVisitorRateLimit());
        return result;
    }

    public record SettingsRequest(Boolean visitorsEnabled, @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(1000) Integer visitorDailyCap,
                                  @jakarta.validation.constraints.Size(max = 30) String staffRateLimit,
                                  @jakarta.validation.constraints.Size(max = 30) String visitorRateLimit) {}

    /** Visitor changes go to the identity system first, so the IT dashboard never disagrees with AirPairy. */
    public Map<String, Object> updateSettings(Tenant tenant, SettingsRequest req) {
        var visitorChanges = new LinkedHashMap<String, Object>();
        if (req.visitorsEnabled() != null) visitorChanges.put("visitorsEnabled", req.visitorsEnabled());
        if (req.visitorDailyCap() != null) visitorChanges.put("visitorDailyCap", req.visitorDailyCap());
        if (!visitorChanges.isEmpty()) {
            JsonNode school = identity.updateSchool(tenant.getId(), visitorChanges);
            tenant.setSchoolVisitorsEnabled(school.path("visitorsEnabled").asBoolean());
            tenant.setSchoolVisitorDailyCap(school.path("visitorDailyCap").asInt());
        }
        if (req.staffRateLimit() != null) tenant.setSchoolStaffRateLimit(rate(req.staffRateLimit()));
        if (req.visitorRateLimit() != null) tenant.setSchoolVisitorRateLimit(rate(req.visitorRateLimit()));
        tenants.save(tenant);
        access.systemPlan(tenant, SchoolAccessService.STAFF_PLAN);
        access.systemPlan(tenant, SchoolAccessService.VISITOR_PLAN);
        return settings(tenant);
    }

    /** Blank means unlimited; otherwise a MikroTik rate such as "5M/5M". */
    private static String rate(String value) {
        if (value.isBlank()) return null;
        String clean = com.wifibilling.controller.PackagePlanController.sanitizeRateLimit(value);
        if (!clean.matches("[0-9]+[KMG]?(/[0-9]+[KMG]?)?")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a speed like 5M/5M, or leave it empty for no limit.");
        return clean;
    }
}
