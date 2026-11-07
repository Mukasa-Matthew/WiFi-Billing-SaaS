package com.wifibilling.school;

import com.wifibilling.dto.GenerateVouchersRequest;
import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Router;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.Transaction;
import com.wifibilling.model.Voucher;
import com.wifibilling.radius.RadCheck;
import com.wifibilling.radius.RadReply;
import com.wifibilling.repository.*;
import com.wifibilling.service.ReportingClock;
import com.wifibilling.service.VoucherExpirationWorker;
import com.wifibilling.service.VoucherService;
import com.wifibilling.util.RouterTokens;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Captive-portal access for school tenants. Students and staff prove who they are with their school ID and PIN;
 * staff and visitors then get free vouchers, students buy as usual. Every school voucher belongs to one person
 * (or visitor pass) and one device, and a fresh ID and PIN check moves it to a new device.
 */
@Slf4j
@Service
public class SchoolAccessService {
    public static final String SYSTEM_AVAILABILITY = "SCHOOL";
    static final String STAFF_PLAN = "School staff access";
    static final String VISITOR_PLAN = "School visitor pass";
    private static final DateTimeFormatter RADIUS_EXPIRATION = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss", Locale.ENGLISH);

    private final IdentityClient identity;
    private final SchoolTickets tickets;
    private final RouterRepository routers;
    private final TenantRepository tenants;
    private final VoucherRepository vouchers;
    private final PackagePlanRepository plans;
    private final RadCheckRepository radCheck;
    private final RadReplyRepository radReply;
    private final VoucherService voucherService;
    private final VoucherExpirationWorker expiration;
    private final ReportingClock clock;
    private final TransactionTemplate transaction;

    public SchoolAccessService(IdentityClient identity, SchoolTickets tickets, RouterRepository routers, TenantRepository tenants,
                               VoucherRepository vouchers, PackagePlanRepository plans, RadCheckRepository radCheck, RadReplyRepository radReply,
                               VoucherService voucherService, VoucherExpirationWorker expiration, ReportingClock clock, PlatformTransactionManager manager) {
        this.identity = identity; this.tickets = tickets; this.routers = routers; this.tenants = tenants; this.vouchers = vouchers;
        this.plans = plans; this.radCheck = radCheck; this.radReply = radReply; this.voucherService = voucherService;
        this.expiration = expiration; this.clock = clock; this.transaction = new TransactionTemplate(manager);
    }

    /** What the portal should do next: connect with {@code code}, show packages with {@code ticket}, or ask for a new PIN. */
    public record PortalResult(String action, String code, String ticket, String pinChangeToken, String fullName, String category,
                               LocalDate accessUntil, OffsetDateTime expiresAt) {
        static PortalResult connect(String code, String fullName, String category, OffsetDateTime expiresAt) {
            return new PortalResult("CONNECT", code, null, null, fullName, category, null, expiresAt);
        }
    }

    public PortalResult verify(String routerToken, String identifier, String pin, String rawMac) {
        Router router = schoolRouter(routerToken);
        String mac = requireMac(rawMac);
        return outcome(router, mac, identity.verify(router.getTenant().getId(), identifier, pin, mac));
    }

    public PortalResult changePin(String routerToken, String pinChangeToken, String newPin, String rawMac) {
        Router router = schoolRouter(routerToken);
        String mac = requireMac(rawMac);
        return outcome(router, mac, identity.changePin(router.getTenant().getId(), pinChangeToken, newPin));
    }

    public PortalResult visitor(String routerToken, String code, String rawMac) {
        Router router = schoolRouter(routerToken);
        String mac = requireMac(rawMac);
        Tenant tenant = router.getTenant();
        JsonNode pass = identity.admitVisitor(tenant.getId(), code, mac);
        String passId = pass.path("passId").asString();
        OffsetDateTime until = OffsetDateTime.parse(pass.path("expiresAt").asString());
        String fullName = pass.path("fullName").asString();
        return transaction.execute(status -> {
            Voucher voucher = vouchers.findAllByTenantIdAndVisitorPassIdAndStatusIn(tenant.getId(), passId, List.of("unused", "active")).stream().findFirst()
                    .orElseGet(() -> issue(tenant, systemPlan(tenant, VISITOR_PLAN), router, mac, null, "VISITOR", passId, until));
            reprovision(voucher, router, mac, until);
            return PortalResult.connect(voucher.getCode(), fullName, "VISITOR", until);
        });
    }

    private PortalResult outcome(Router router, String mac, JsonNode verified) {
        String fullName = verified.path("fullName").asString();
        String category = verified.path("category").asString();
        if ("PIN_CHANGE_REQUIRED".equals(verified.path("status").asString())) {
            return new PortalResult("CHANGE_PIN", null, null, verified.path("pinChangeToken").asString(), fullName, category, null, null);
        }
        Tenant tenant = router.getTenant();
        String personId = verified.path("personId").asString();
        LocalDate accessUntil = LocalDate.parse(verified.path("accessUntil").asString());
        OffsetDateTime hardLimit = endOf(accessUntil);
        return transaction.execute(status -> {
            Voucher live = live(tenant.getId(), personId, category);
            if ("STAFF".equals(category)) {
                Voucher voucher = live != null ? live : issue(tenant, systemPlan(tenant, STAFF_PLAN), router, mac, personId, "STAFF", null, hardLimit);
                reprovision(voucher, router, mac, hardLimit);
                return PortalResult.connect(voucher.getCode(), fullName, category, voucher.getExpiresAt() != null ? voucher.getExpiresAt() : hardLimit);
            }
            if (live != null) {
                reprovision(live, router, mac, hardLimit);
                return PortalResult.connect(live.getCode(), fullName, category, live.getExpiresAt());
            }
            String ticket = tickets.issue(new SchoolTickets.Ticket(tenant.getId(), personId, accessUntil, mac));
            return new PortalResult("BUY", null, ticket, null, fullName, category, accessUntil, null);
        });
    }

    /** After a successful school payment: ties the new voucher to the student and their leaving date. */
    public void bindPaidVoucher(Transaction tx, String code) {
        Voucher voucher = vouchers.findByCode(code).orElseThrow();
        voucher.setPersonId(tx.getPersonId());
        voucher.setSchoolCategory("STUDENT");
        voucher.setHardExpiresAt(endOf(tx.getPersonAccessUntil()));
        vouchers.save(voucher);
        radCheck.save(RadCheck.builder().username(code).attribute("Expiration").op(":=").value(radiusTime(voucher.getHardExpiresAt())).build());
    }

    /** The identity system asked for sign-out: end sessions and block logins until a fresh ID and PIN check. */
    public int disconnect(long tenantId, String personId, String visitorPassId) {
        return transaction.execute(status -> disconnectInTransaction(tenantId, personId, visitorPassId));
    }

    private int disconnectInTransaction(long tenantId, String personId, String visitorPassId) {
        Tenant tenant = tenants.findById(tenantId).filter(Tenant::isSchool)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School tenant not found"));
        if (visitorPassId != null) {
            var passes = vouchers.findAllByTenantIdAndVisitorPassIdAndStatusIn(tenant.getId(), visitorPassId, List.of("unused", "active"));
            passes.forEach(voucher -> voucherService.revoke(tenant.getId(), voucher.getCode()));
            return passes.size();
        }
        var held = vouchers.findAllLiveForPerson(tenant.getId(), personId);
        for (Voucher voucher : held) {
            radCheck.deleteAllByUsername(voucher.getCode());
            radReply.deleteAllByUsername(voucher.getCode());
            voucher.setSchoolHold(true);
            vouchers.save(voucher);
            expiration.disconnectActiveSessions(voucher.getCode());
        }
        return held.size();
    }

    private Voucher live(Long tenantId, String personId, String category) {
        OffsetDateTime now = clock.now();
        return vouchers.findLiveForPerson(tenantId, personId, category).stream()
                .filter(v -> (v.getExpiresAt() == null || v.getExpiresAt().isAfter(now)) && (v.getHardExpiresAt() == null || v.getHardExpiresAt().isAfter(now)))
                .min((a, b) -> Boolean.compare(!"active".equals(a.getStatus()), !"active".equals(b.getStatus())))
                .orElse(null);
    }

    private Voucher issue(Tenant tenant, PackagePlan plan, Router router, String mac, String personId, String category, String passId, OffsetDateTime hardLimit) {
        String code = voucherService.generate(tenant.getId(), new GenerateVouchersRequest(plan.getId(), 1), mac, router.getWgIp()).getFirst().code();
        Voucher voucher = vouchers.findByCode(code).orElseThrow();
        voucher.setSaleType("SCHOOL_" + category);
        voucher.setPersonId(personId);
        voucher.setSchoolCategory(category);
        voucher.setVisitorPassId(passId);
        voucher.setHardExpiresAt(hardLimit);
        return vouchers.save(voucher);
    }

    /** Rebuilds the voucher's RADIUS entries for this device and router, ending any session elsewhere. */
    private void reprovision(Voucher voucher, Router router, String mac, OffsetDateTime hardLimit) {
        if (Boolean.TRUE.equals(voucher.getSchoolHold()) || voucher.getClaimedMac() != null && !voucher.getClaimedMac().equalsIgnoreCase(mac)) {
            expiration.disconnectActiveSessions(voucher.getCode());
        }
        voucher.setHardExpiresAt(hardLimit);
        voucher.setSchoolHold(false);
        if (voucher.getFirstLogin() != null) voucher.setClaimedMac(mac);
        vouchers.save(voucher);
        PackagePlan plan = voucher.getPackagePlan();
        String code = voucher.getCode();
        radCheck.deleteAllByUsername(code);
        radReply.deleteAllByUsername(code);
        OffsetDateTime until = voucher.getExpiresAt() != null && voucher.getExpiresAt().isBefore(hardLimit) ? voucher.getExpiresAt() : hardLimit;
        var checks = new java.util.ArrayList<>(List.of(
                check(code, "Cleartext-Password", code),
                RadCheck.builder().username(code).attribute("Calling-Station-Id").op("==").value(mac).build(),
                check(code, "Simultaneous-Use", "1"),
                check(code, "Expiration", radiusTime(until))));
        if (router.getWgIp() != null) checks.add(RadCheck.builder().username(code).attribute("NAS-IP-Address").op("==").value(router.getWgIp()).build());
        radCheck.saveAll(checks);
        if (plan.getRateLimit() != null && !plan.getRateLimit().isBlank()) radReply.save(RadReply.builder().username(code).attribute("Mikrotik-Rate-Limit").op(":=").value(plan.getRateLimit()).build());
        if (plan.getSessionTimeout() != null && plan.getSessionTimeout() > 0) radReply.save(RadReply.builder().username(code).attribute("Session-Timeout").op(":=").value(String.valueOf(plan.getSessionTimeout())).build());
    }

    /** The hidden free package behind staff or visitor access, created on first use with the owner's speed limit. */
    PackagePlan systemPlan(Tenant tenant, String name) {
        boolean staff = STAFF_PLAN.equals(name);
        String rate = staff ? tenant.getSchoolStaffRateLimit() : tenant.getSchoolVisitorRateLimit();
        PackagePlan plan = plans.findFirstByTenantIdAndAvailabilityAndName(tenant.getId(), SYSTEM_AVAILABILITY, name)
                .orElseGet(() -> PackagePlan.builder().tenant(tenant).name(name).price(BigDecimal.ZERO).sharedUsers(1)
                        .availability(SYSTEM_AVAILABILITY).featured(false).displayOrder(0)
                        .description(staff ? "Free staff access, renewed by signing in each day" : "Free visitor access until the pass expires")
                        .sessionTimeout(staff ? 86_400 : 172_800).rateLimit("").build());
        plan.setRateLimit(rate == null ? "" : rate);
        return plans.save(plan);
    }

    private Router schoolRouter(String token) {
        if (!RouterTokens.isValid(token)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found");
        Router router = routers.findByToken(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found"));
        if (!router.getTenant().isSchool()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "School sign-in is not enabled here");
        return router;
    }

    static String requireMac(String raw) {
        String clean = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace('-', ':');
        if (!clean.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Connect to the school WiFi and open this page again.");
        return clean;
    }

    /** The last moment of a school date: access ends at midnight in the school's timezone. */
    OffsetDateTime endOf(LocalDate date) { return clock.startOf(date.plusDays(1)).minusSeconds(1); }

    private static RadCheck check(String username, String attribute, String value) {
        return RadCheck.builder().username(username).attribute(attribute).op(":=").value(value).build();
    }

    private static String radiusTime(OffsetDateTime at) { return at.withOffsetSameInstant(ZoneOffset.UTC).format(RADIUS_EXPIRATION); }
}
