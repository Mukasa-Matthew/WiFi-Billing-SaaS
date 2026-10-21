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

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT10S")
    @Transactional
    public void processVoucherLifecycle() {
        stampFirstLogins();
        expireOverdueVouchers();
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
        List<Voucher> overdue = voucherRepository.findAllByStatusAndExpiresAtBefore(STATUS_ACTIVE, now);
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
     * Disconnects any open sessions in radacct for this username.
     */
    public void disconnectActiveSessions(String username) {
        List<RadAcct> activeSessions = radAcctRepository.findAllByUsernameAndAcctStopTimeIsNull(username);
        for (RadAcct session : activeSessions) {
            String nasIp = Ipv4Subnet.stripPrefix(session.getNasIpAddress());
            if (nasIp != null) {
                routerRepository.findByWgIp(nasIp).ifPresent(router -> {
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
