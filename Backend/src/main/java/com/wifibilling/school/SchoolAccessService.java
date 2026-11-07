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
    public static final String SALE_STUDENT_FREE = "SCHOOL_STUDENT_FREE";
    static final String STAFF_PLAN = "School staff access";
    static final String VISITOR_PLAN = "School visitor pass";
    static final String STUDENT_FREE_PLAN = "School student daily free access";
    private static final DateTimeFormatter RADIUS_EXPIRATION = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss", Locale.ENGLISH);

    private final IdentityClient identity;
    private final SchoolTickets tickets;
    private final RouterRepository routers;
    private final TenantRepository tenants;
    private final VoucherRepository vouchers;
    private final PackagePlanRepository plans;
    private final RadCheckRepository radCheck;
    private final RadReplyRepository radReply;
    private final RadAcctRepository radAcct;
    private final VoucherService voucherService;
    private final VoucherExpirationWorker expiration;
    private final ReportingClock clock;
    private final TransactionTemplate transaction;

    public SchoolAccessService(IdentityClient identity, SchoolTickets tickets, RouterRepository routers, TenantRepository tenants,
                               VoucherRepository vouchers, PackagePlanRepository plans, RadCheckRepository radCheck, RadReplyRepository radReply,
                               RadAcctRepository radAcct, VoucherService voucherService, VoucherExpirationWorker expiration,
                               ReportingClock clock, PlatformTransactionManager manager) {
        this.identity = identity; this.tickets = tickets; this.routers = routers; this.tenants = tenants; this.vouchers = vouchers;
        this.plans = plans; this.radCheck = radCheck; this.radReply = radReply; this.radAcct = radAcct; this.voucherService = voucherService;
        this.expiration = expiration; this.clock = clock; this.transaction = new TransactionTemplate(manager);
    }

    public static final String HOLD_IT = "IT_HOLD";
    public static final String HOLD_CAP = "CAP_HOLD";

    /** What the portal should do next: connect with {@code code}, show packages with {@code ticket}, ask for a new PIN, or show the sign-in form. */
    public record PortalResult(String action, String code, String ticket, String pinChangeToken, String fullName, String category,
                               LocalDate accessUntil, OffsetDateTime expiresAt, String message,
                               Boolean studentFreeAvailable, Integer studentFreeDailyCapMb,
                               Integer studentFreeRemainingMb, String studentFreeAllowedSites) {
        public PortalResult(String action, String code, String ticket, String pinChangeToken, String fullName, String category,
                            LocalDate accessUntil, OffsetDateTime expiresAt) {
            this(action, code, ticket, pinChangeToken, fullName, category, accessUntil, expiresAt, null, null, null, null, null);
        }

        public PortalResult(String action, String code, String ticket, String pinChangeToken, String fullName, String category,
                            LocalDate accessUntil, OffsetDateTime expiresAt, String message) {
            this(action, code, ticket, pinChangeToken, fullName, category, accessUntil, expiresAt, message, null, null, null, null);
        }

        static PortalResult connect(String code, String fullName, String category, OffsetDateTime expiresAt) {
            return new PortalResult("CONNECT", code, null, null, fullName, category, null, expiresAt, null, null, null, null, null);
        }

        static PortalResult signIn(String message) {
            return new PortalResult("SIGN_IN", null, null, null, null, null, null, null, message, null, null, null, null);
        }

        static PortalResult buy(String ticket, String fullName, String category, LocalDate accessUntil,
                                Boolean studentFreeAvailable, Integer studentFreeDailyCapMb,
                                Integer studentFreeRemainingMb, String studentFreeAllowedSites) {
            return new PortalResult("BUY", null, ticket, null, fullName, category, accessUntil, null, null,
                    studentFreeAvailable, studentFreeDailyCapMb, studentFreeRemainingMb, studentFreeAllowedSites);
        }
    }

    /**
     * Zero-touch auto-reconnect for school devices: when a staff member, student,
     * or visitor's device (MAC) already holds an active, unexpired voucher in the
     * system, reconnect it automatically without asking for their ID and PIN again.
     */
    public PortalResult resume(String routerToken, String rawMac) {
        Router router = schoolRouter(routerToken);
        String mac = requireMac(rawMac);
        Tenant tenant = router.getTenant();
        OffsetDateTime now = clock.now();
        return transaction.execute(status -> {
            Voucher voucher = vouchers.findLiveByTenantIdAndClaimedMac(tenant.getId(), mac).stream()
                    .filter(v -> (v.getExpiresAt() == null || v.getExpiresAt().isAfter(now))
                            && (v.getHardExpiresAt() == null || v.getHardExpiresAt().isAfter(now)))
                    .min((a, b) -> {
                        int paidOrder = Boolean.compare(SALE_STUDENT_FREE.equals(a.getSaleType()), SALE_STUDENT_FREE.equals(b.getSaleType()));
                        if (paidOrder != 0) return paidOrder;
                        return Boolean.compare(!"active".equals(a.getStatus()), !"active".equals(b.getStatus()));
                    })
                    .orElse(null);
            if (voucher == null) {
                return PortalResult.signIn(null);
            }
            if (Boolean.TRUE.equals(voucher.getSchoolHold()) && !HOLD_CAP.equals(voucher.getDelivery())) {
                return PortalResult.signIn(null);
            }
            String category = voucher.getSchoolCategory();
            OffsetDateTime hardLimit = voucher.getHardExpiresAt() != null ? voucher.getHardExpiresAt() : now.plusDays(1);
            if ("STAFF".equals(category)) {
                if (!tenant.isSchoolStaffEnabled()) {
                    holdAndDisconnect(voucher);
                    return PortalResult.signIn("Free staff WiFi is currently paused by the network owner.");
                }
                Integer capMb = tenant.getSchoolStaffDailyDataCapMb();
                Long remainingBytes = null;
                if (capMb != null && capMb > 0) {
                    long capBytes = capMb.longValue() * 1024L * 1024L;
                    long used = staffUsedTodayBytes(tenant.getId(), voucher.getPersonId(), voucher.getCode());
                    if (used >= capBytes) {
                        holdAndDisconnect(voucher);
                        return PortalResult.signIn("You have reached your daily free data limit (" + capMb + " MB). Your free access resets tomorrow.");
                    }
                    remainingBytes = capBytes - used;
                }
                systemPlan(tenant, STAFF_PLAN);
                reprovision(voucher, router, mac, hardLimit, remainingBytes);
                return PortalResult.connect(voucher.getCode(), null, "STAFF", hardLimit);
            }
            if ("VISITOR".equals(category)) {
                if (Boolean.FALSE.equals(tenant.getSchoolVisitorsEnabled())) {
                    holdAndDisconnect(voucher);
                    return PortalResult.signIn("Visitor passes are currently switched off by the network owner.");
                }
                Integer capMb = tenant.getSchoolVisitorDataCapMb();
                Long remainingBytes = null;
                if (capMb != null && capMb > 0) {
                    long capBytes = capMb.longValue() * 1024L * 1024L;
                    long used = visitorPassUsedBytes(tenant.getId(), voucher.getVisitorPassId(), voucher.getCode());
                    if (used >= capBytes) {
                        holdAndDisconnect(voucher);
                        return PortalResult.signIn("This visitor pass has reached its data limit (" + capMb + " MB).");
                    }
                    remainingBytes = capBytes - used;
                }
                systemPlan(tenant, VISITOR_PLAN);
                reprovision(voucher, router, mac, hardLimit, remainingBytes);
                return PortalResult.connect(voucher.getCode(), null, "VISITOR", hardLimit);
            }
            if (SALE_STUDENT_FREE.equals(voucher.getSaleType())) {
                if (!tenant.isSchoolStudentFreeEnabled()) {
                    holdAndDisconnect(voucher);
                    return PortalResult.signIn("Free daily student study data is currently switched off. Sign in to buy a package.");
                }
                int capMb = tenant.getSchoolStudentDailyFreeMb();
                long capBytes = capMb * 1024L * 1024L;
                long used = studentFreeUsedTodayBytes(tenant.getId(), voucher.getPersonId(), voucher.getCode());
                if (used >= capBytes) {
                    holdAndDisconnect(voucher);
                    return PortalResult.signIn("You have used your " + capMb + " MB free daily study data for today. Sign in below to purchase a full-internet package.");
                }
                systemPlan(tenant, STUDENT_FREE_PLAN);
                reprovision(voucher, router, mac, hardLimit, capBytes - used);
                return PortalResult.connect(voucher.getCode(), null, "STUDENT_FREE", hardLimit);
            }
            if (Boolean.TRUE.equals(voucher.getSchoolHold())) {
                return PortalResult.signIn(null);
            }
            reprovision(voucher, router, mac, hardLimit, null);
            return PortalResult.connect(voucher.getCode(), null, "STUDENT",
                    voucher.getExpiresAt() != null ? voucher.getExpiresAt() : hardLimit);
        });
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
        if (Boolean.FALSE.equals(tenant.getSchoolVisitorsEnabled())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Visitor passes are currently switched off by the network owner.");
        }
        JsonNode pass = identity.admitVisitor(tenant.getId(), code, mac);
        String passId = pass.path("passId").asString();
        OffsetDateTime until = OffsetDateTime.parse(pass.path("expiresAt").asString());
        String fullName = pass.path("fullName").asString();
        return transaction.execute(status -> {
            Voucher existing = vouchers.findAllByTenantIdAndVisitorPassIdAndStatusIn(tenant.getId(), passId, List.of("unused", "active")).stream().findFirst().orElse(null);
            Long remainingBytes = checkVisitorDataCap(tenant, passId, existing);
            Voucher voucher = existing != null ? existing : issue(tenant, systemPlan(tenant, VISITOR_PLAN), router, mac, null, "VISITOR", passId, until);
            reprovision(voucher, router, mac, until, remainingBytes);
            return PortalResult.connect(voucher.getCode(), fullName, "VISITOR", until);
        });
    }

    /** Connects a verified student on their daily free study data allowance (restricted to school-approved sites). */
    public PortalResult studentFree(String routerToken, String ticketToken, String rawMac) {
        Router router = schoolRouter(routerToken);
        String mac = requireMac(rawMac);
        Tenant tenant = router.getTenant();
        SchoolTickets.Ticket t = tickets.require(ticketToken, tenant.getId(), mac);
        if (!tenant.isSchoolStudentFreeEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Free daily student study data is not enabled for this school.");
        }
        OffsetDateTime hardLimit = endOf(t.accessUntil());
        return transaction.execute(status -> {
            Voucher existing = liveFreeStudent(tenant.getId(), t.personId());
            Long remainingBytes = checkStudentFreeDailyCap(tenant, t.personId(), existing);
            Voucher voucher = existing != null ? existing : issueFreeStudent(tenant, router, mac, t.personId(), hardLimit);
            reprovision(voucher, router, mac, hardLimit, remainingBytes);
            return PortalResult.connect(voucher.getCode(), null, "STUDENT_FREE", hardLimit);
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
            if ("STAFF".equals(category)) {
                Voucher live = live(tenant.getId(), personId, category);
                if (!tenant.isSchoolStaffEnabled()) {
                    if (live != null) holdAndDisconnect(live);
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Free staff WiFi is currently paused by the network owner.");
                }
                Long remainingBytes = checkStaffDailyCap(tenant, personId, live);
                Voucher voucher = live != null ? live : issue(tenant, systemPlan(tenant, STAFF_PLAN), router, mac, personId, "STAFF", null, hardLimit);
                reprovision(voucher, router, mac, hardLimit, remainingBytes);
                return PortalResult.connect(voucher.getCode(), fullName, category, voucher.getExpiresAt() != null ? voucher.getExpiresAt() : hardLimit);
            }
            Voucher livePaid = livePaidStudent(tenant.getId(), personId);
            if (livePaid != null) {
                reprovision(livePaid, router, mac, hardLimit, null);
                return PortalResult.connect(livePaid.getCode(), fullName, category, livePaid.getExpiresAt());
            }
            String ticket = tickets.issue(new SchoolTickets.Ticket(tenant.getId(), personId, accessUntil, mac));
            if (tenant.isSchoolStudentFreeEnabled()) {
                int dailyCapMb = tenant.getSchoolStudentDailyFreeMb();
                long capBytes = dailyCapMb * 1024L * 1024L;
                long usedBytes = studentFreeUsedTodayBytes(tenant.getId(), personId, null);
                int remainingMb = usedBytes >= capBytes ? 0 : (int) Math.max(1L, (capBytes - usedBytes + (1024L * 1024L - 1L)) / (1024L * 1024L));
                return PortalResult.buy(ticket, fullName, category, accessUntil, true, dailyCapMb, remainingMb, tenant.getSchoolStudentAllowedSites());
            }
            return PortalResult.buy(ticket, fullName, category, accessUntil, false, null, null, null);
        });
    }

    /** After a successful school payment: ties the new voucher to the student and their leaving date, ending any free study session. */
    public void bindPaidVoucher(Transaction tx, String code) {
        if (tx.getPersonId() != null && tx.getTenant() != null) {
            vouchers.findLiveForPerson(tx.getTenant().getId(), tx.getPersonId(), "STUDENT").stream()
                    .filter(v -> SALE_STUDENT_FREE.equals(v.getSaleType()))
                    .forEach(this::holdAndDisconnect);
        }
        Voucher voucher = vouchers.findByCode(code).orElseThrow();
        voucher.setPersonId(tx.getPersonId());
        voucher.setSchoolCategory("STUDENT");
        voucher.setHardExpiresAt(endOf(tx.getPersonAccessUntil()));
        if (tx.getClientMac() != null && !tx.getClientMac().isBlank()) {
            String normalized = tx.getClientMac().trim().toUpperCase(Locale.ROOT).replace('-', ':');
            if (normalized.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) {
                voucher.setClaimedMac(normalized);
            }
        }
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
            voucher.setDelivery(HOLD_IT);
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

    private Voucher livePaidStudent(Long tenantId, String personId) {
        OffsetDateTime now = clock.now();
        return vouchers.findLiveForPerson(tenantId, personId, "STUDENT").stream()
                .filter(v -> !SALE_STUDENT_FREE.equals(v.getSaleType()))
                .filter(v -> (v.getExpiresAt() == null || v.getExpiresAt().isAfter(now)) && (v.getHardExpiresAt() == null || v.getHardExpiresAt().isAfter(now)))
                .min((a, b) -> Boolean.compare(!"active".equals(a.getStatus()), !"active".equals(b.getStatus())))
                .orElse(null);
    }

    private Voucher liveFreeStudent(Long tenantId, String personId) {
        OffsetDateTime now = clock.now();
        return vouchers.findLiveForPerson(tenantId, personId, "STUDENT").stream()
                .filter(v -> SALE_STUDENT_FREE.equals(v.getSaleType()))
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
        voucher.setClaimedMac(mac);
        return vouchers.save(voucher);
    }

    private Voucher issueFreeStudent(Tenant tenant, Router router, String mac, String personId, OffsetDateTime hardLimit) {
        PackagePlan plan = systemPlan(tenant, STUDENT_FREE_PLAN);
        String code = voucherService.generate(tenant.getId(), new GenerateVouchersRequest(plan.getId(), 1), mac, router.getWgIp()).getFirst().code();
        Voucher voucher = vouchers.findByCode(code).orElseThrow();
        voucher.setSaleType(SALE_STUDENT_FREE);
        voucher.setPersonId(personId);
        voucher.setSchoolCategory("STUDENT");
        voucher.setHardExpiresAt(hardLimit);
        voucher.setClaimedMac(mac);
        return vouchers.save(voucher);
    }

    /** Rebuilds the voucher's RADIUS entries for this device and router, ending any session elsewhere. */
    private void reprovision(Voucher voucher, Router router, String mac, OffsetDateTime hardLimit, Long remainingBytes) {
        if (Boolean.TRUE.equals(voucher.getSchoolHold()) || voucher.getClaimedMac() != null && !voucher.getClaimedMac().equalsIgnoreCase(mac)) {
            expiration.disconnectActiveSessions(voucher.getCode());
        }
        voucher.setHardExpiresAt(hardLimit);
        if ("STAFF".equals(voucher.getSchoolCategory()) || SALE_STUDENT_FREE.equals(voucher.getSaleType())) {
            voucher.setExpiresAt(hardLimit);
        }
        voucher.setSchoolHold(false);
        voucher.setDelivery("MANUAL");
        voucher.setClaimedMac(mac);
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
        if (remainingBytes != null && remainingBytes > 0) writeTotalLimit(code, remainingBytes);
        String addressList = addressListFor(voucher);
        if (addressList != null) {
            radReply.save(RadReply.builder().username(code).attribute("Mikrotik-Address-List").op(":=").value(addressList).build());
        }
    }

    private static String addressListFor(Voucher voucher) {
        if ("STAFF".equals(voucher.getSchoolCategory())) {
            return com.wifibilling.service.RouterScriptGenerator.STAFF_FREE_ADDRESS_LIST;
        }
        if (SALE_STUDENT_FREE.equals(voucher.getSaleType())) {
            return com.wifibilling.service.RouterScriptGenerator.STUDENT_FREE_ADDRESS_LIST;
        }
        return null;
    }

    private Long checkStudentFreeDailyCap(Tenant tenant, String personId, Voucher live) {
        Integer capMb = tenant.getSchoolStudentDailyFreeMb();
        if (capMb == null || capMb <= 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Free daily student study data is not configured.");
        }
        long capBytes = capMb.longValue() * 1024L * 1024L;
        long used = studentFreeUsedTodayBytes(tenant.getId(), personId, live != null ? live.getCode() : null);
        if (used >= capBytes) {
            if (live != null) holdAndDisconnect(live);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You have used all of your " + capMb + " MB free daily study data for today. Purchase a package below for full internet, or wait until tomorrow.");
        }
        return capBytes - used;
    }

    long studentFreeUsedTodayBytes(Long tenantId, String personId, String fallbackCode) {
        var codes = new java.util.LinkedHashSet<String>();
        if (personId != null) {
            vouchers.findAllByTenantIdAndPersonIdAndSchoolCategory(tenantId, personId, "STUDENT").stream()
                    .filter(v -> SALE_STUDENT_FREE.equals(v.getSaleType()))
                    .forEach(v -> codes.add(v.getCode()));
        }
        if (fallbackCode != null) codes.add(fallbackCode);
        if (codes.isEmpty()) return 0L;
        return radAcct.sumOctetsForUsernamesSince(codes, clock.startOf(clock.today()));
    }

    private Long checkStaffDailyCap(Tenant tenant, String personId, Voucher live) {
        Integer capMb = tenant.getSchoolStaffDailyDataCapMb();
        if (capMb == null || capMb <= 0) return null;
        long capBytes = capMb.longValue() * 1024L * 1024L;
        long used = staffUsedTodayBytes(tenant.getId(), personId, live != null ? live.getCode() : null);
        if (used >= capBytes) {
            if (live != null) holdAndDisconnect(live);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You have reached your daily free data limit (" + capMb + " MB). Your free access resets tomorrow.");
        }
        return capBytes - used;
    }

    private Long checkVisitorDataCap(Tenant tenant, String passId, Voucher live) {
        Integer capMb = tenant.getSchoolVisitorDataCapMb();
        if (capMb == null || capMb <= 0) return null;
        long capBytes = capMb.longValue() * 1024L * 1024L;
        long used = visitorPassUsedBytes(tenant.getId(), passId, live != null ? live.getCode() : null);
        if (used >= capBytes) {
            if (live != null) holdAndDisconnect(live);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This visitor pass has reached its data limit (" + capMb + " MB).");
        }
        return capBytes - used;
    }

    long staffUsedTodayBytes(Long tenantId, String personId, String fallbackCode) {
        var codes = new java.util.LinkedHashSet<String>();
        if (personId != null) {
            vouchers.findAllByTenantIdAndPersonIdAndSchoolCategory(tenantId, personId, "STAFF")
                    .forEach(v -> codes.add(v.getCode()));
        }
        if (fallbackCode != null) codes.add(fallbackCode);
        if (codes.isEmpty()) return 0L;
        return radAcct.sumOctetsForUsernamesSince(codes, clock.startOf(clock.today()));
    }

    long visitorPassUsedBytes(Long tenantId, String passId, String fallbackCode) {
        var codes = new java.util.LinkedHashSet<String>();
        if (passId != null) {
            vouchers.findAllByTenantIdAndVisitorPassId(tenantId, passId)
                    .forEach(v -> codes.add(v.getCode()));
        }
        if (fallbackCode != null) codes.add(fallbackCode);
        if (codes.isEmpty()) return 0L;
        return radAcct.sumOctetsForUsernames(codes);
    }

    private void holdAndDisconnect(Voucher voucher) {
        radCheck.deleteAllByUsername(voucher.getCode());
        radReply.deleteAllByUsername(voucher.getCode());
        voucher.setSchoolHold(true);
        if (!HOLD_IT.equals(voucher.getDelivery())) {
            voucher.setDelivery(HOLD_CAP);
        }
        vouchers.save(voucher);
        expiration.disconnectActiveSessions(voucher.getCode());
    }

    private void writeTotalLimit(String username, long remainingBytes) {
        if (remainingBytes >= (1L << 32)) {
            radReply.save(RadReply.builder().username(username).attribute("Mikrotik-Total-Limit-Gigawords").op(":=").value(String.valueOf(remainingBytes >>> 32)).build());
        }
        radReply.save(RadReply.builder().username(username).attribute("Mikrotik-Total-Limit").op(":=").value(String.valueOf(remainingBytes & 0xFFFF_FFFFL)).build());
    }

    private void syncReplyLimits(String username, String rateLimit, Long remainingBytes) {
        radReply.deleteAllByUsernameAndAttribute(username, "Mikrotik-Rate-Limit");
        radReply.deleteAllByUsernameAndAttribute(username, "Mikrotik-Total-Limit");
        radReply.deleteAllByUsernameAndAttribute(username, "Mikrotik-Total-Limit-Gigawords");
        if (rateLimit != null && !rateLimit.isBlank()) {
            radReply.save(RadReply.builder().username(username).attribute("Mikrotik-Rate-Limit").op(":=").value(rateLimit).build());
        }
        if (remainingBytes != null && remainingBytes > 0) {
            writeTotalLimit(username, remainingBytes);
        }
    }

    /**
     * Applies the owner's current speed limits, data caps, and enable switches
     * to live staff, visitor, and free student vouchers, and optionally disconnects
     * active free sessions so MikroTik immediately enforces the updated RADIUS attributes.
     */
    public void applyFreeAccessRules(Tenant tenant, boolean disconnectActive) {
        transaction.executeWithoutResult(status -> {
            systemPlan(tenant, STAFF_PLAN);
            systemPlan(tenant, VISITOR_PLAN);
            systemPlan(tenant, STUDENT_FREE_PLAN);
            List<Voucher> liveFree = vouchers.findAllByTenantIdAndSchoolCategoryInAndStatusIn(
                    tenant.getId(), List.of("STAFF", "VISITOR", "STUDENT"), List.of("unused", "active"));
            if (liveFree == null || liveFree.isEmpty()) return;
            for (Voucher v : liveFree) {
                String code = v.getCode();
                if ("STUDENT".equals(v.getSchoolCategory())) {
                    if (!SALE_STUDENT_FREE.equals(v.getSaleType())) continue;
                    if (!tenant.isSchoolStudentFreeEnabled()) {
                        holdAndDisconnect(v);
                        continue;
                    }
                    int capMb = tenant.getSchoolStudentDailyFreeMb();
                    long capBytes = capMb * 1024L * 1024L;
                    long used = studentFreeUsedTodayBytes(tenant.getId(), v.getPersonId(), code);
                    if (used >= capBytes) {
                        holdAndDisconnect(v);
                        continue;
                    }
                    long remaining = capBytes - used;
                    if (Boolean.TRUE.equals(v.getSchoolHold()) && HOLD_CAP.equals(v.getDelivery()) && v.getClaimedMac() != null) {
                        List<Router> tenantRouters = routers.findAllByTenantId(tenant.getId());
                        if (!tenantRouters.isEmpty()) {
                            OffsetDateTime hard = v.getHardExpiresAt() != null ? v.getHardExpiresAt() : clock.now().plusDays(1);
                            reprovision(v, tenantRouters.getFirst(), v.getClaimedMac(), hard, remaining);
                            if (disconnectActive) expiration.disconnectActiveSessions(code);
                            continue;
                        }
                    }
                    if (!Boolean.TRUE.equals(v.getSchoolHold())) {
                        syncReplyLimits(code, tenant.getSchoolStudentFreeRateLimit(), remaining);
                        if (disconnectActive) expiration.disconnectActiveSessions(code);
                    }
                    continue;
                }
                if ("STAFF".equals(v.getSchoolCategory())) {
                    if (!tenant.isSchoolStaffEnabled()) {
                        holdAndDisconnect(v);
                        continue;
                    }
                    Integer capMb = tenant.getSchoolStaffDailyDataCapMb();
                    Long remaining = null;
                    if (capMb != null && capMb > 0) {
                        long capBytes = capMb.longValue() * 1024L * 1024L;
                        long used = staffUsedTodayBytes(tenant.getId(), v.getPersonId(), code);
                        if (used >= capBytes) {
                            holdAndDisconnect(v);
                            continue;
                        }
                        remaining = capBytes - used;
                    }
                    if (Boolean.TRUE.equals(v.getSchoolHold()) && HOLD_CAP.equals(v.getDelivery()) && v.getClaimedMac() != null) {
                        List<Router> tenantRouters = routers.findAllByTenantId(tenant.getId());
                        if (!tenantRouters.isEmpty()) {
                            OffsetDateTime hard = v.getHardExpiresAt() != null ? v.getHardExpiresAt() : clock.now().plusDays(1);
                            reprovision(v, tenantRouters.getFirst(), v.getClaimedMac(), hard, remaining);
                            if (disconnectActive) expiration.disconnectActiveSessions(code);
                            continue;
                        }
                    }
                    if (!Boolean.TRUE.equals(v.getSchoolHold())) {
                        syncReplyLimits(code, tenant.getSchoolStaffRateLimit(), remaining);
                        if (disconnectActive) expiration.disconnectActiveSessions(code);
                    }
                } else if ("VISITOR".equals(v.getSchoolCategory())) {
                    if (Boolean.FALSE.equals(tenant.getSchoolVisitorsEnabled())) {
                        holdAndDisconnect(v);
                        continue;
                    }
                    Integer capMb = tenant.getSchoolVisitorDataCapMb();
                    Long remaining = null;
                    if (capMb != null && capMb > 0) {
                        long capBytes = capMb.longValue() * 1024L * 1024L;
                        long used = visitorPassUsedBytes(tenant.getId(), v.getVisitorPassId(), code);
                        if (used >= capBytes) {
                            holdAndDisconnect(v);
                            continue;
                        }
                        remaining = capBytes - used;
                    }
                    if (Boolean.TRUE.equals(v.getSchoolHold()) && HOLD_CAP.equals(v.getDelivery()) && v.getClaimedMac() != null) {
                        List<Router> tenantRouters = routers.findAllByTenantId(tenant.getId());
                        if (!tenantRouters.isEmpty()) {
                            OffsetDateTime hard = v.getHardExpiresAt() != null ? v.getHardExpiresAt() : clock.now().plusDays(1);
                            reprovision(v, tenantRouters.getFirst(), v.getClaimedMac(), hard, remaining);
                            if (disconnectActive) expiration.disconnectActiveSessions(code);
                            continue;
                        }
                    }
                    if (!Boolean.TRUE.equals(v.getSchoolHold())) {
                        syncReplyLimits(code, tenant.getSchoolVisitorRateLimit(), remaining);
                        if (disconnectActive) expiration.disconnectActiveSessions(code);
                    }
                }
            }
        });
    }

    /** The hidden free package behind staff, visitor, or student daily free access, created on first use with the owner's speed limit. */
    PackagePlan systemPlan(Tenant tenant, String name) {
        boolean staff = STAFF_PLAN.equals(name);
        boolean studentFree = STUDENT_FREE_PLAN.equals(name);
        String rate = staff ? tenant.getSchoolStaffRateLimit()
                : studentFree ? tenant.getSchoolStudentFreeRateLimit()
                : tenant.getSchoolVisitorRateLimit();
        String desc = staff ? "Free staff access for their active school period"
                : studentFree ? "Free student daily study data on approved sites"
                : "Free visitor access until the pass expires";
        PackagePlan plan = plans.findFirstByTenantIdAndAvailabilityAndName(tenant.getId(), SYSTEM_AVAILABILITY, name)
                .orElseGet(() -> PackagePlan.builder().tenant(tenant).name(name).price(BigDecimal.ZERO).sharedUsers(1)
                        .availability(SYSTEM_AVAILABILITY).featured(false).displayOrder(0)
                        .description(desc)
                        .sessionTimeout((staff || studentFree) ? 0 : 172_800).rateLimit("").build());
        if ((staff || studentFree) && plan.getSessionTimeout() != null && plan.getSessionTimeout() > 0) {
            plan.setSessionTimeout(0);
        }
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
