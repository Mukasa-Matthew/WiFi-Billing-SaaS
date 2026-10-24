package com.wifibilling.service;

import com.wifibilling.dto.GenerateVouchersRequest;
import com.wifibilling.dto.VoucherResponse;
import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.Voucher;
import com.wifibilling.radius.RadCheck;
import com.wifibilling.radius.RadReply;
import com.wifibilling.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Voucher lifecycle. Each voucher code is both the RADIUS username and password
 * (MikroTik hotspot "voucher" style login). Creating a voucher writes:
 * <ul>
 *   <li>radcheck: Cleartext-Password, Simultaneous-Use</li>
 *   <li>radreply: Mikrotik-Rate-Limit, Session-Timeout</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class VoucherService {

    public static final String STATUS_UNUSED = "unused";
    public static final String STATUS_REVOKED = "revoked";

    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // no 0/O/1/I
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final VoucherRepository voucherRepository;
    private final TenantRepository tenantRepository;
    private final PackagePlanRepository packagePlanRepository;
    private final RadCheckRepository radCheckRepository;
    private final RadReplyRepository radReplyRepository;
    private final VoucherExpirationWorker expirationWorker;
    private final CustomerRepository customerRepository;

    @Transactional
    public List<VoucherResponse> generate(Long tenantId, GenerateVouchersRequest req) {
        return generate(tenantId, req, null);
    }

    /**
     * Generates vouchers; when {@code mac} is provided each code is pinned to
     * that hotspot client via a Calling-Station-Id check item — the credential
     * only authenticates from the device that made the purchase.
     */
    @Transactional
    public List<VoucherResponse> generate(Long tenantId, GenerateVouchersRequest req, String mac) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        PackagePlan plan = packagePlanRepository.findByIdAndTenantId(req.packageId(), tenant.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found for tenant"));

        String batchReference = req.batchReference() == null || req.batchReference().isBlank()
                ? "BATCH-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase() : req.batchReference().trim();
        Set<String> batch = new HashSet<>();
        List<VoucherResponse> created = new ArrayList<>(req.count());
        for (int i = 0; i < req.count(); i++) {
            String code = uniqueCode(batch, req);
            Voucher voucher = voucherRepository.save(Voucher.builder()
                    .tenant(tenant)
                    .packagePlan(plan)
                    .code(code)
                    .status(STATUS_UNUSED)
                    .batchReference(batchReference).saleType(Boolean.TRUE.equals(req.giveaway()) ? "GIVEAWAY" : "INVENTORY").delivery("MANUAL").archived(false)
                    .build());
            writeRadiusAttributes(code, plan, mac);
            created.add(toResponse(voucher));
        }
        return created;
    }

    @Transactional(readOnly = true)
    public List<VoucherResponse> list(Long tenantId, String status) {
        List<Voucher> vouchers = (status == null || status.isBlank())
                ? voucherRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId)
                : voucherRepository.findAllByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status);
        return vouchers.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public VoucherResponse get(Long tenantId, String code) {
        Voucher voucher = findByCodeAndTenant(code, tenantId);
        return toResponse(voucher);
    }

    /**
     * Revokes the voucher: removes RADIUS credentials and sends immediate
     * RFC 5176 Disconnect-Request to any active session on the router.
     */
    @Transactional
    public VoucherResponse revoke(Long tenantId, String code) {
        Voucher voucher = findByCodeAndTenant(code, tenantId);
        radCheckRepository.deleteAllByUsername(voucher.getCode());
        radReplyRepository.deleteAllByUsername(voucher.getCode());
        voucher.setStatus(STATUS_REVOKED);
        voucherRepository.save(voucher);

        // Disconnect active session immediately
        expirationWorker.disconnectActiveSessions(voucher.getCode());

        return toResponse(voucher);
    }

    @Transactional
    public void recordOnlineSale(Long tenantId, String code) {
        Voucher v = findByCodeAndTenant(code, tenantId); v.setSaleType("ONLINE"); voucherRepository.save(v);
    }

    @Transactional
    public void assign(Long tenantId, List<String> codes, Long customerId) {
        var customer = customerRepository.findByIdAndTenantId(customerId,tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Customer not found"));
        for (String code: codes) { var voucher=findByCodeAndTenant(code,tenantId);voucher.setCustomer(customer);voucherRepository.save(voucher); }
    }

    @Transactional
    public void archive(Long tenantId, String code) {
        var v=findByCodeAndTenant(code,tenantId);
        if (!List.of("expired","revoked").contains(v.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Only expired or revoked vouchers can be archived");
        v.setArchived(true);voucherRepository.save(v);
    }

    private void writeRadiusAttributes(String code, PackagePlan plan, String mac) {
        radCheckRepository.save(RadCheck.builder()
                .username(code).attribute("Cleartext-Password").op(":=").value(code).build());
        if (mac != null) {
            radCheckRepository.save(RadCheck.builder()
                    .username(code).attribute("Calling-Station-Id").op("==").value(mac).build());
        }
        if (plan.getSharedUsers() != null && plan.getSharedUsers() > 0) {
            radCheckRepository.save(RadCheck.builder()
                    .username(code).attribute("Simultaneous-Use").op(":=")
                    .value(String.valueOf(plan.getSharedUsers())).build());
        }
        if (plan.getRateLimit() != null && !plan.getRateLimit().isBlank()) {
            radReplyRepository.save(RadReply.builder()
                    .username(code).attribute("Mikrotik-Rate-Limit").op(":=").value(plan.getRateLimit()).build());
        }
        if (plan.getSessionTimeout() != null && plan.getSessionTimeout() > 0) {
            radReplyRepository.save(RadReply.builder()
                    .username(code).attribute("Session-Timeout").op(":=")
                    .value(String.valueOf(plan.getSessionTimeout())).build());
        }
    }

    private Voucher findByCodeAndTenant(String code, Long tenantId) {
        String normalized = code == null ? "" : code.trim().toUpperCase();
        Voucher voucher = voucherRepository.findByCode(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Voucher not found"));
        if (!voucher.getTenant().getId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Voucher not found");
        }
        return voucher;
    }

    private String uniqueCode(Set<String> batch, GenerateVouchersRequest req) {
        String prefix = req.prefix() == null ? "" : req.prefix().toUpperCase();
        int length = req.codeLength() == null ? CODE_LENGTH : req.codeLength();
        String alphabet = "NUMERIC".equals(req.codeFormat()) ? "23456789" : "LETTERS".equals(req.codeFormat()) ? "ABCDEFGHJKLMNPQRSTUVWXYZ" : CODE_ALPHABET;
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(prefix);
            for (int i = 0; i < length - prefix.length(); i++) {
                sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
            }
            String code = sb.toString();
            if (batch.add(code) && !voucherRepository.existsByCode(code) && !radCheckRepository.existsByUsername(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique voucher code");
    }

    private VoucherResponse toResponse(Voucher v) {
        PackagePlan p = v.getPackagePlan();
        return new VoucherResponse(v.getId(), v.getCode(), v.getStatus(),
                p.getId(), p.getName(), p.getPrice(),
                v.getClaimedMac(), v.getFirstLogin(), v.getExpiresAt(), v.getCreatedAt(),
                v.getCustomer() == null ? null : v.getCustomer().getId(),
                v.getCustomer() == null ? null : v.getCustomer().getName(),
                v.getCustomer() == null ? null : v.getCustomer().getPhone(),
                v.getBatchReference(), v.getSaleType() == null ? "INVENTORY" : v.getSaleType(),
                v.getDelivery() == null ? "MANUAL" : v.getDelivery(), Boolean.TRUE.equals(v.getArchived()));
    }
}
