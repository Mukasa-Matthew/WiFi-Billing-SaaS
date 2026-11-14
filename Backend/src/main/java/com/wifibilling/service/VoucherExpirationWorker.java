package com.wifibilling.service;

import com.wifibilling.model.Router;
import com.wifibilling.model.Voucher;
import com.wifibilling.radius.RadAcct;
import com.wifibilling.radius.RadCheck;
import com.wifibilling.repository.*;
import com.wifibilling.util.Ipv4Subnet;
import com.wifibilling.util.PostCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Background worker driven by FreeRADIUS accounting data (radacct):
 * 1. Detects newly started sessions for 'unused' vouchers: stamps first_login and expires_at.
 * 2. Checks active vouchers that have surpassed expires_at: marks them expired,
 *    removes RADIUS authorization entries, and dispatches a Disconnect-Request to terminate sessions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherExpirationWorker {

    public static final String STATUS_UNUSED = "unused";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_EXPIRED = "expired";

    private final VoucherRepository voucherRepository;
    private final RadAcctRepository radAcctRepository;
    private final RadCheckRepository radCheckRepository;
    private final RadReplyRepository radReplyRepository;
    private final RouterRepository routerRepository;
    private final RadiusDisconnectService disconnectService;
    private final RouterManagementService managementService;
    private LeaderLockService leaderLockService = new LeaderLockService();
    private ReportingClock clock;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLeaderLockService(LeaderLockService leaderLockService) {
        this.leaderLockService = leaderLockService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClock(ReportingClock clock) {
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT10S")
    @Transactional
    public void processVoucherLifecycle() {
        if (!leaderLockService.executeIfLeader(LeaderLockService.JOB_VOUCHER_EXPIRATION, 120, 15, this::doProcessVoucherLifecycle)) return;
    }

    @Transactional
    public void doProcessVoucherLifecycle() {
        stampFirstLogins();
        reconcileDeviceLocks();
        expireOverdueVouchers();
        enforceSchoolDataCaps();
    }

    /**
     * Regular paid vouchers pin to the buyer's MAC via a Calling-Station-Id
     * check — but phones regenerate that MAC whenever the network is forgotten
     * or randomization rotates it, permanently locking the customer out of a
     * still-valid voucher. The lock only needs to stop SIMULTANEOUS sharing,
     * so: while a session is open the pin tracks whichever MAC holds it, and
     * when no session is open the pin is released so any device (old or new)
     * can claim the voucher next. School vouchers stay pinned — they are
     * identity-bound, and moves go through the school move flow instead.
     */
    private void reconcileDeviceLocks() {
        // Unused paid vouchers are swept too: they were pinned to the buyer's MAC
        // at purchase, and since no session can ever be open on an unused code the
        // pin would otherwise survive a MAC rotation and permanently lock the
        // customer out of a voucher they paid for.
        List<Voucher> regular = voucherRepository.findAllByStatusIn(List.of(STATUS_ACTIVE, STATUS_UNUSED)).stream()
                .filter(v -> v.getSchoolCategory() == null)
                .toList();
        if (regular.isEmpty()) return;
        List<String> codes = regular.stream().map(Voucher::getCode).toList();

        var openMac = new java.util.HashMap<String, String>();
        for (RadAcct session : radAcctRepository
                .findAllByUsernameInAndAcctStopTimeIsNullOrderByAcctStartTimeDesc(codes)) {
            if (session.getCallingStationId() != null) {
                openMac.putIfAbsent(session.getUsername(), session.getCallingStationId());
            }
        }
        var pins = new java.util.HashMap<String, String>();
        for (RadCheck row : radCheckRepository.findAllByUsernameInAndAttribute(codes, "Calling-Station-Id")) {
            pins.putIfAbsent(row.getUsername(), row.getValue());
        }

        for (Voucher voucher : regular) {
            String code = voucher.getCode();
            String mac = openMac.get(code);
            String pin = pins.get(code);
            if (mac != null) {
                if (!mac.equalsIgnoreCase(pin)) {
                    radCheckRepository.deleteAllByUsernameAndAttribute(code, "Calling-Station-Id");
                    radCheckRepository.save(RadCheck.builder().username(code)
                            .attribute("Calling-Station-Id").op("==").value(mac).build());
                }
                if (voucher.getClaimedMac() == null || !voucher.getClaimedMac().equalsIgnoreCase(mac)) {
                    voucher.setClaimedMac(mac);
                    voucherRepository.save(voucher);
                }
            } else if (pin != null) {
                radCheckRepository.deleteAllByUsernameAndAttribute(code, "Calling-Station-Id");
                log.info("Voucher '{}' device lock released: no open session, so a new device can claim it", code);
            }
        }
    }

    /**
     * Inspects unused vouchers to see if radacct recorded a login session.
     */
    private void stampFirstLogins() {
        List<Voucher> unusedVouchers = voucherRepository.findAllByStatus(STATUS_UNUSED);
        if (unusedVouchers.isEmpty()) return;
        // One query for every voucher instead of one per voucher: each
        // round trip costs tens of ms over the Tailscale link, so the
        // per-voucher form kept this transaction open for minutes.
        var firstSessions = new java.util.HashMap<String, RadAcct>();
        for (RadAcct session : radAcctRepository.findFirstSessionsByUsernameIn(
                unusedVouchers.stream().map(Voucher::getCode).toList())) {
            firstSessions.putIfAbsent(session.getUsername(), session);
        }
        for (Voucher voucher : unusedVouchers) {
            RadAcct session = firstSessions.get(voucher.getCode());
            if (session != null) {
                OffsetDateTime loginTime = session.getAcctStartTime() != null ? session.getAcctStartTime() : OffsetDateTime.now();
                voucher.setFirstLogin(loginTime);
                voucher.setStatus(STATUS_ACTIVE);
                if (session.getCallingStationId() != null) {
                    voucher.setClaimedMac(session.getCallingStationId());
                }

                // Calculate expires_at = first_login + sessionTimeout (seconds)
                Integer timeoutSeconds = voucher.getPackagePlan().getSessionTimeout();
                if (timeoutSeconds != null && timeoutSeconds > 0) {
                    voucher.setExpiresAt(loginTime.plusSeconds(timeoutSeconds));
                    // School vouchers never outlast their hard limit (leaving date or visitor pass expiry).
                    if (voucher.getHardExpiresAt() != null) {
                        if (voucher.getHardExpiresAt().isBefore(voucher.getExpiresAt())) voucher.setExpiresAt(voucher.getHardExpiresAt());
                        radCheckRepository.deleteAllByUsernameAndAttribute(voucher.getCode(), "Expiration");
                    }
                    // FreeRADIUS parses Expiration with mktime() in the RADIUS host's
                    // local timezone — never emit UTC or every voucher dies early by
                    // the UTC offset (3h on EAT). Keep this aligned with the host TZ.
                    String expiry = voucher.getExpiresAt().toInstant()
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss",java.util.Locale.ENGLISH));
                    radCheckRepository.save(RadCheck.builder().username(voucher.getCode())
                            .attribute("Expiration").op(":=").value(expiry).build());
                } else if (voucher.getHardExpiresAt() != null) {
                    voucher.setExpiresAt(voucher.getHardExpiresAt());
                    radCheckRepository.deleteAllByUsernameAndAttribute(voucher.getCode(), "Expiration");
                    String expiry = voucher.getHardExpiresAt().toInstant()
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss",java.util.Locale.ENGLISH));
                    radCheckRepository.save(RadCheck.builder().username(voucher.getCode())
                            .attribute("Expiration").op(":=").value(expiry).build());
                }
                voucherRepository.save(voucher);
                log.info("Voucher '{}' activated. First login: {}, expires at: {}", voucher.getCode(), loginTime, voucher.getExpiresAt());
            }
        }
    }

    /**
     * Finds active vouchers that have expired (expires_at < NOW()), revokes authorization,
     * and sends Disconnect-Request to any open sessions.
     */
    private void expireOverdueVouchers() {
        OffsetDateTime now = OffsetDateTime.now();
        var overdueMap = new java.util.LinkedHashMap<Long, Voucher>();
        voucherRepository.findAllByStatusAndExpiresAtBefore(STATUS_ACTIVE, now).forEach(v -> overdueMap.put(v.getId(), v));
        voucherRepository.findAllByStatusAndHardExpiresAtBefore(STATUS_UNUSED, now).forEach(v -> overdueMap.put(v.getId(), v));
        voucherRepository.findAllByStatusAndHardExpiresAtBefore(STATUS_ACTIVE, now).forEach(v -> overdueMap.put(v.getId(), v));
        for (Voucher voucher : overdueMap.values()) {
            voucher.setStatus(STATUS_EXPIRED);
            voucherRepository.save(voucher);

            // Clean up RADIUS check and reply rules so user cannot re-authenticate
            radCheckRepository.deleteAllByUsername(voucher.getCode());
            radReplyRepository.deleteAllByUsername(voucher.getCode());

            // Disconnect any currently live sessions on the router
            disconnectActiveSessions(voucher.getCode());
            log.info("Voucher '{}' expired and revoked.", voucher.getCode());
        }
    }

    /**
     * Enforces school staff daily data caps, visitor pass data caps, and pause
     * switches for active/unused free school vouchers.
     *
     * Caps are shared per person (staff/student) or per visitor pass, and an
     * expired code's usage still counts toward the shared total — so every
     * group is built once from all school voucher codes, then usage is fetched
     * with two grouped aggregate queries instead of two queries per voucher.
     */
    private void enforceSchoolDataCaps() {
        List<Voucher> freeVouchers = voucherRepository.findAllBySchoolCategoryInAndStatusIn(
                List.of("STAFF", "VISITOR", "STUDENT"), List.of(STATUS_UNUSED, STATUS_ACTIVE));
        if (freeVouchers == null || freeVouchers.isEmpty()) return;
        OffsetDateTime startOfToday = clock != null ? clock.startOf(clock.today())
                : java.time.LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay().atOffset(java.time.ZoneOffset.UTC);

        record PersonKey(long tenantId, String category, String personId) {}
        record PassKey(long tenantId, String passId) {}
        java.util.Map<PersonKey, java.util.Set<String>> personCodes = new java.util.HashMap<>();
        java.util.Map<PassKey, java.util.Set<String>> passCodes = new java.util.HashMap<>();
        for (Object[] row : voucherRepository.allSchoolGroupCodes()) {
            String code = (String) row[0];
            long tenantId = ((Number) row[1]).longValue();
            String personId = (String) row[2];
            String passId = (String) row[3];
            String category = (String) row[4];
            String saleType = (String) row[5];
            if (personId != null && ("STAFF".equals(category) || "VISITOR".equals(category)
                    || ("STUDENT".equals(category) && "SCHOOL_STUDENT_FREE".equals(saleType)))) {
                personCodes.computeIfAbsent(new PersonKey(tenantId, category, personId),
                        k -> new java.util.LinkedHashSet<>()).add(code);
            }
            if (passId != null) {
                passCodes.computeIfAbsent(new PassKey(tenantId, passId),
                        k -> new java.util.LinkedHashSet<>()).add(code);
            }
        }

        var dailyUsage = usageByCode(flatten(personCodes.values()), startOfToday);
        var lifetimeUsage = usageByCode(flatten(passCodes.values()), null);

        for (Voucher voucher : freeVouchers) {
            if (Boolean.TRUE.equals(voucher.getSchoolHold())) continue;
            var tenant = voucher.getTenant();
            if (tenant == null || !tenant.isSchool()) continue;
            String category = voucher.getSchoolCategory();
            if ("STUDENT".equals(category)) {
                if (!"SCHOOL_STUDENT_FREE".equals(voucher.getSaleType())) continue;
                if (!tenant.isSchoolStudentFreeEnabled()) {
                    holdSchoolVoucher(voucher, "student free study data disabled");
                    continue;
                }
                Integer capMb = tenant.getSchoolStudentDailyFreeMb();
                if (capMb != null && capMb > 0) {
                    long used = groupUsage(personCodes.get(
                            new PersonKey(tenant.getId(), category, voucher.getPersonId())),
                            voucher.getCode(), dailyUsage);
                    if (used >= capMb.longValue() * 1024L * 1024L) {
                        holdSchoolVoucher(voucher, "student daily free study data cap (" + capMb + " MB) reached");
                    }
                }
            } else if ("STAFF".equals(category)) {
                if (!tenant.isSchoolStaffEnabled()) {
                    holdSchoolVoucher(voucher, "staff WiFi paused");
                    continue;
                }
                Integer capMb = tenant.getSchoolStaffDailyDataCapMb();
                if (capMb != null && capMb > 0) {
                    long used = groupUsage(personCodes.get(
                            new PersonKey(tenant.getId(), category, voucher.getPersonId())),
                            voucher.getCode(), dailyUsage);
                    if (used >= capMb.longValue() * 1024L * 1024L) {
                        holdSchoolVoucher(voucher, "staff daily data cap (" + capMb + " MB) reached");
                    }
                }
            } else if ("VISITOR".equals(category)) {
                if (Boolean.FALSE.equals(tenant.getSchoolVisitorsEnabled())) {
                    holdSchoolVoucher(voucher, "visitor WiFi disabled");
                    continue;
                }
                Integer capMb = tenant.getSchoolVisitorDataCapMb();
                if (capMb != null && capMb > 0) {
                    long used = groupUsage(passCodes.get(
                            new PassKey(tenant.getId(), voucher.getVisitorPassId())),
                            voucher.getCode(), lifetimeUsage);
                    if (used >= capMb.longValue() * 1024L * 1024L) {
                        voucher.setStatus(STATUS_EXPIRED);
                        voucherRepository.save(voucher);
                        radCheckRepository.deleteAllByUsername(voucher.getCode());
                        radReplyRepository.deleteAllByUsername(voucher.getCode());
                        disconnectActiveSessions(voucher.getCode());
                        log.info("Visitor voucher '{}' expired: data cap ({} MB) reached.", voucher.getCode(), capMb);
                    }
                }
            }
        }
    }

    private static java.util.Set<String> flatten(java.util.Collection<java.util.Set<String>> groups) {
        var all = new java.util.HashSet<String>();
        groups.forEach(all::addAll);
        return all;
    }

    /** (username → total octets) for the given codes; null since = all time. */
    private java.util.Map<String, Long> usageByCode(java.util.Set<String> codes, OffsetDateTime since) {
        if (codes.isEmpty()) return java.util.Map.of();
        var rows = since != null
                ? radAcctRepository.sumOctetsByUsernameSince(codes, since)
                : radAcctRepository.sumOctetsByUsername(codes);
        var usage = new java.util.HashMap<String, Long>(rows.size() * 2);
        for (Object[] row : rows) usage.put((String) row[0], ((Number) row[1]).longValue());
        return usage;
    }

    /** Sums the group's codes (plus the voucher's own code) from the usage map. */
    private static long groupUsage(java.util.Set<String> group, String ownCode, java.util.Map<String, Long> usage) {
        long used = usage.getOrDefault(ownCode, 0L);
        if (group != null) {
            for (String code : group) used += usage.getOrDefault(code, 0L);
            if (group.contains(ownCode)) used -= usage.getOrDefault(ownCode, 0L);
        }
        return used;
    }

    private void holdSchoolVoucher(Voucher voucher, String reason) {
        voucher.setSchoolHold(true);
        if (!"IT_HOLD".equals(voucher.getDelivery())) {
            voucher.setDelivery("CAP_HOLD");
        }
        voucherRepository.save(voucher);
        radCheckRepository.deleteAllByUsername(voucher.getCode());
        radReplyRepository.deleteAllByUsername(voucher.getCode());
        disconnectActiveSessions(voucher.getCode());
        log.info("School voucher '{}' held and disconnected: {}", voucher.getCode(), reason);
    }

    /**
     * Disconnects any open sessions in radacct for this username.
     * Deferred past the caller's commit: the RADIUS UDP wait must never run
     * inside a transaction, where it would hold locks while blocked on the wire.
     */
    public void disconnectActiveSessions(String username) {
        PostCommit.run(() -> runDisconnects(username));
    }

    private void runDisconnects(String username) {
        List<RadAcct> activeSessions = radAcctRepository.findAllByUsernameAndAcctStopTimeIsNull(username);
        for (RadAcct session : activeSessions) {
            String nasIp = Ipv4Subnet.stripPrefix(session.getNasIpAddress());
            if (nasIp != null) {
                routerRepository.findByWgIp(nasIp).ifPresent(router -> {
                    // Router polling also delivers the request when AppServer cannot route to the hub's VPN subnet.
                    managementService.enqueueDisconnect(router, username);
                    disconnectService.sendDisconnect(
                            nasIp,
                            RadiusDisconnectService.DEFAULT_COA_PORT,
                            router.getRadiusSecret(),
                            username,
                            session.getFramedIpAddress()
                    );
                });
            }
        }
    }
}
