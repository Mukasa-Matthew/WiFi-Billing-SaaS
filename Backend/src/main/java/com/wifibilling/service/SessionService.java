package com.wifibilling.service;

import com.wifibilling.dto.ActiveSessionDto;
import com.wifibilling.model.Voucher;
import com.wifibilling.radius.RadAcct;
import com.wifibilling.repository.RadAcctRepository;
import com.wifibilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final RadAcctRepository radAcctRepository;
    private final VoucherRepository voucherRepository;
    private final VoucherExpirationWorker expirationWorker;

    @Transactional(readOnly = true)
    public List<ActiveSessionDto> getActiveSessions(Long tenantId, String role) {
        List<RadAcct> sessions;
        if ("ROLE_SUPERADMIN".equals(role)) {
            sessions = radAcctRepository.findAllByAcctStopTimeIsNullOrderByAcctStartTimeDesc();
        } else {
            Set<String> tenantVouchers = getTenantVoucherCodes(tenantId);
            if (tenantVouchers.isEmpty()) {
                return Collections.emptyList();
            }
            sessions = radAcctRepository.findAllByUsernameInAndAcctStopTimeIsNullOrderByAcctStartTimeDesc(tenantVouchers);
        }
        return sessions.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<ActiveSessionDto> getSessionHistory(Long tenantId, String role) {
        List<RadAcct> sessions;
        if ("ROLE_SUPERADMIN".equals(role)) {
            sessions = radAcctRepository.findTop100ByOrderByAcctStartTimeDesc();
        } else {
            Set<String> tenantVouchers = getTenantVoucherCodes(tenantId);
            if (tenantVouchers.isEmpty()) {
                return Collections.emptyList();
            }
            sessions = radAcctRepository.findTop100ByUsernameInOrderByAcctStartTimeDesc(tenantVouchers);
        }
        return sessions.stream().map(this::toDto).toList();
    }

    public boolean disconnectSession(Long tenantId, String role, String username) {
        if (!"ROLE_SUPERADMIN".equals(role)) {
            Voucher voucher = voucherRepository.findByCode(username)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Voucher session not found"));
            if (!voucher.getTenant().getId().equals(tenantId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to session");
            }
        }
        log.info("Tenant {} ({}) requested disconnect for user {}", tenantId, role, username);
        expirationWorker.disconnectActiveSessions(username);
        return true;
    }

    private Set<String> getTenantVoucherCodes(Long tenantId) {
        return voucherRepository.codesByTenant(tenantId);
    }

    private ActiveSessionDto toDto(RadAcct a) {
        long in = a.getAcctInputOctets() != null ? a.getAcctInputOctets() : 0L;
        long out = a.getAcctOutputOctets() != null ? a.getAcctOutputOctets() : 0L;
        boolean isActive = a.getAcctStopTime() == null;

        return ActiveSessionDto.builder()
                .radAcctId(a.getRadAcctId())
                .username(a.getUsername())
                .framedIpAddress(a.getFramedIpAddress())
                .callingStationId(a.getCallingStationId())
                .nasIpAddress(a.getNasIpAddress())
                .acctStartTime(a.getAcctStartTime())
                .acctStopTime(a.getAcctStopTime())
                .acctSessionTime(a.getAcctSessionTime())
                .acctInputOctets(in)
                .acctOutputOctets(out)
                .totalOctets(in + out)
                .status(isActive ? "ACTIVE" : "TERMINATED")
                .acctTerminateCause(a.getAcctTerminateCause())
                .build();
    }
}
