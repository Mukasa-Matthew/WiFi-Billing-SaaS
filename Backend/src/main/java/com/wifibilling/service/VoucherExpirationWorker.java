package com.wifibilling.service;

import com.wifibilling.model.Router;
import com.wifibilling.model.Voucher;
import com.wifibilling.radius.RadAcct;
import com.wifibilling.radius.RadCheck;
import com.wifibilling.repository.*;
import com.wifibilling.util.Ipv4Subnet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

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
        expireOverdueVouchers();
        enforceSchoolDataCaps();
    }

    /**
     * Inspects unused vouchers to see if radacct recorded a login session.
     */
    private void stampFirstLogins() {
        List<Voucher> unusedVouchers = voucherRepository.findAllByStatus(STATUS_UNUSED);
        for (Voucher voucher : unusedVouchers) {
            Optional<RadAcct> sessionOpt = radAcctRepository.findFirstSessionByUsername(voucher.getCode());
            if (sessionOpt.isPresent()) {
                RadAcct session = sessionOpt.get();
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
                    String expiry = voucher.getExpiresAt().withOffsetSameInstant(java.time.ZoneOffset.UTC)
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
        List<Voucher> overdue = new java.util.ArrayList<>(voucherRepository.findAllByStatusAndExpiresAtBefore(STATUS_ACTIVE, now));
        overdue.addAll(voucherRepository.findAllByStatusAndHardExpiresAtBefore(STATUS_UNUSED, now));
        for (Voucher voucher : overdue) {
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
     */
    private void enforceSchoolDataCaps() {
        List<Voucher> freeVouchers = voucherRepository.findAllBySchoolCategoryInAndStatusIn(
                List.of("STAFF", "VISITOR"), List.of(STATUS_UNUSED, STATUS_ACTIVE));
        if (freeVouchers == null || freeVouchers.isEmpty()) return;
        OffsetDateTime startOfToday = clock != null ? clock.startOf(clock.today())
                : java.time.LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
        for (Voucher voucher : freeVouchers) {
            if (Boolean.TRUE.equals(voucher.getSchoolHold())) continue;
            var tenant = voucher.getTenant();
            if (tenant == null || !tenant.isSchool()) continue;
            if ("STAFF".equals(voucher.getSchoolCategory())) {
                if (!tenant.isSchoolStaffEnabled()) {
                    holdSchoolVoucher(voucher, "staff WiFi paused");
                    continue;
                }
                Integer capMb = tenant.getSchoolStaffDailyDataCapMb();
                if (capMb != null && capMb > 0) {
                    long capBytes = capMb.longValue() * 1024L * 1024L;
                    var codes = new java.util.LinkedHashSet<String>();
                    if (voucher.getPersonId() != null) {
                        voucherRepository.findAllByTenantIdAndPersonIdAndSchoolCategory(tenant.getId(), voucher.getPersonId(), "STAFF")
                                .forEach(v -> codes.add(v.getCode()));
                    }
                    codes.add(voucher.getCode());
                    long used = radAcctRepository.sumOctetsForUsernamesSince(codes, startOfToday);
                    if (used >= capBytes) {
                        holdSchoolVoucher(voucher, "staff daily data cap (" + capMb + " MB) reached");
                    }
                }
            } else if ("VISITOR".equals(voucher.getSchoolCategory())) {
                if (Boolean.FALSE.equals(tenant.getSchoolVisitorsEnabled())) {
                    holdSchoolVoucher(voucher, "visitor WiFi disabled");
                    continue;
                }
                Integer capMb = tenant.getSchoolVisitorDataCapMb();
                if (capMb != null && capMb > 0) {
                    long capBytes = capMb.longValue() * 1024L * 1024L;
                    var codes = new java.util.LinkedHashSet<String>();
                    if (voucher.getVisitorPassId() != null) {
                        voucherRepository.findAllByTenantIdAndVisitorPassId(tenant.getId(), voucher.getVisitorPassId())
                                .forEach(v -> codes.add(v.getCode()));
                    }
                    codes.add(voucher.getCode());
                    long used = radAcctRepository.sumOctetsForUsernames(codes);
                    if (used >= capBytes) {
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

    private void holdSchoolVoucher(Voucher voucher, String reason) {
        voucher.setSchoolHold(true);
        voucherRepository.save(voucher);
        radCheckRepository.deleteAllByUsername(voucher.getCode());
        radReplyRepository.deleteAllByUsername(voucher.getCode());
        disconnectActiveSessions(voucher.getCode());
        log.info("School voucher '{}' held and disconnected: {}", voucher.getCode(), reason);
    }

    /**
     * Disconnects any open sessions in radacct for this username.
     */
    public void disconnectActiveSessions(String username) {
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
