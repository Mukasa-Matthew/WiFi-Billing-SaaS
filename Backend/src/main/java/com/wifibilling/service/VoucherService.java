package com.wifibilling.service;

import com.wifibilling.dto.GenerateVouchersRequest;
import com.wifibilling.dto.VoucherResponse;
import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.Voucher;
import com.wifibilling.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.*;

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
    private final com.wifibilling.repository.RouterRepository routerRepository;
    private final com.wifibilling.repository.TransactionRepository transactionRepository;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public List<VoucherResponse> generate(Long tenantId, GenerateVouchersRequest req) {
        return generate(tenantId, req, null, null);
    }

    /**
     * Generates vouchers; when {@code mac} is provided each code is pinned to
     * that hotspot client via a Calling-Station-Id check item — the credential
     * only authenticates from the device that made the purchase. When
     * {@code nasIp} is provided a NAS-IP-Address check pins it to that router,
     * preventing replay on another tenant's hotspot.
     */
    @Transactional
    public List<VoucherResponse> generate(Long tenantId, GenerateVouchersRequest req, String mac, String nasIp) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        PackagePlan plan = packagePlanRepository.findByIdAndTenantId(req.packageId(), tenant.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found for tenant"));

        String batchReference = req.batchReference() == null || req.batchReference().isBlank()
                ? "BATCH-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase() : req.batchReference().trim();

        int targetCount = req.count();
        List<String> generatedCodes = generateUniqueCodes(targetCount, req);
        OffsetDateTime now = OffsetDateTime.now();
        Timestamp nowTs = Timestamp.from(now.toInstant());
        String saleType = Boolean.TRUE.equals(req.giveaway()) ? "GIVEAWAY" : "INVENTORY";

        // 1. Bulk insert vouchers using JDBC Batch with generated keys
        String insertVoucherSql = """
            INSERT INTO vouchers (code, status, batch_reference, sale_type, delivery, archived, package_id, tenant_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        List<Long> generatedIds = new ArrayList<>(targetCount);
        jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement(insertVoucherSql, new String[]{"id"})) {
                for (String code : generatedCodes) {
                    ps.setString(1, code);
                    ps.setString(2, STATUS_UNUSED);
                    ps.setString(3, batchReference);
                    ps.setString(4, saleType);
                    ps.setString(5, "MANUAL");
                    ps.setBoolean(6, false);
                    ps.setLong(7, plan.getId());
                    ps.setLong(8, tenant.getId());
                    ps.setTimestamp(9, nowTs);
                    ps.addBatch();
                }
                ps.executeBatch();
                try (var rs = ps.getGeneratedKeys()) {
                    while (rs.next()) {
                        generatedIds.add(rs.getLong(1));
                    }
                }
            }
            return null;
        });

        // 2. Prepare RADIUS check items
        record RadiusItem(String username, String attribute, String op, String value) {}
        List<RadiusItem> checks = new ArrayList<>(targetCount * 3);
        List<RadiusItem> replies = new ArrayList<>(targetCount * 2);

        String rateLimit = (plan.getRateLimit() != null && !plan.getRateLimit().isBlank())
                ? com.wifibilling.controller.PackagePlanController.sanitizeRateLimit(plan.getRateLimit()) : null;

        for (String code : generatedCodes) {
            checks.add(new RadiusItem(code, "Cleartext-Password", ":=", code));
            if (mac != null) {
                checks.add(new RadiusItem(code, "Calling-Station-Id", "==", mac));
            }
            if (nasIp != null) {
                checks.add(new RadiusItem(code, "NAS-IP-Address", "==", nasIp));
            }
            if (plan.getSharedUsers() != null && plan.getSharedUsers() > 0) {
                checks.add(new RadiusItem(code, "Simultaneous-Use", ":=", String.valueOf(plan.getSharedUsers())));
            }

            if (rateLimit != null) {
                replies.add(new RadiusItem(code, "Mikrotik-Rate-Limit", ":=", rateLimit));
            }
            if (plan.getSessionTimeout() != null && plan.getSessionTimeout() > 0) {
                replies.add(new RadiusItem(code, "Session-Timeout", ":=", String.valueOf(plan.getSessionTimeout())));
            }
        }

        // 3. Bulk insert radcheck
        if (!checks.isEmpty()) {
            String insertCheckSql = "INSERT INTO radcheck (username, attribute, op, value) VALUES (?, ?, ?, ?)";
            jdbcTemplate.batchUpdate(insertCheckSql, checks, 500, (ps, item) -> {
                ps.setString(1, item.username());
                ps.setString(2, item.attribute());
                ps.setString(3, item.op());
                ps.setString(4, item.value());
            });
        }

        // 4. Bulk insert radreply
        if (!replies.isEmpty()) {
            String insertReplySql = "INSERT INTO radreply (username, attribute, op, value) VALUES (?, ?, ?, ?)";
            jdbcTemplate.batchUpdate(insertReplySql, replies, 500, (ps, item) -> {
                ps.setString(1, item.username());
                ps.setString(2, item.attribute());
                ps.setString(3, item.op());
                ps.setString(4, item.value());
            });
        }

        // 5. Construct response list
        List<VoucherResponse> response = new ArrayList<>(targetCount);
        for (int i = 0; i < targetCount; i++) {
            Long id = (i < generatedIds.size()) ? generatedIds.get(i) : null;
            String code = generatedCodes.get(i);
            response.add(new VoucherResponse(
                    id, code, STATUS_UNUSED,
                    plan.getId(), plan.getName(), plan.getPrice(),
                    null, null, null, now,
                    null, null, null,
                    batchReference, saleType, "MANUAL", false
            ));
        }

        return response;
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

    /**
     * Records a walk-in (cash/MoMo-at-counter) sale of an unused inventory
     * voucher: one SUCCESSFUL transaction and saleType WALK_IN, under a row
     * lock so the same voucher can't be sold twice. The transaction carries
     * the voucher's customer phone when it has one.
     */
    @Transactional
    public void sellWalkIn(Long tenantId, String code, Long routerId, java.math.BigDecimal amount, String method, String reference, String notes) {
        var voucher = voucherRepository.lockForSale(code, tenantId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Voucher not found"));
        if (!"unused".equals(voucher.getStatus()) || (voucher.getSaleType() != null && !"INVENTORY".equals(voucher.getSaleType())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Voucher is already sold, used, or a giveaway");
        var router = routerRepository.findByIdAndTenantId(routerId, tenantId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found"));
        transactionRepository.save(com.wifibilling.model.Transaction.builder().tenant(voucher.getTenant()).router(router).packagePlan(voucher.getPackagePlan())
                .reference("SALE-" + java.util.UUID.randomUUID()).phone(voucher.getCustomer() == null || voucher.getCustomer().getPhone() == null ? "MANUAL" : voucher.getCustomer().getPhone())
                .amount(amount).network(method).externalReference(reference).notes(notes).voucherCode(code).status("SUCCESSFUL").completedAt(OffsetDateTime.now()).build());
        voucher.setSaleType("WALK_IN");
        voucherRepository.save(voucher);
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

    private Voucher findByCodeAndTenant(String code, Long tenantId) {
        String normalized = code == null ? "" : code.trim().toUpperCase();
        Voucher voucher = voucherRepository.findByCode(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Voucher not found"));
        if (!voucher.getTenant().getId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Voucher not found");
        }
        return voucher;
    }

    private List<String> generateUniqueCodes(int count, GenerateVouchersRequest req) {
        String prefix = req.prefix() == null ? "" : req.prefix().toUpperCase();
        int length = req.codeLength() == null ? CODE_LENGTH : req.codeLength();
        String alphabet = "NUMERIC".equals(req.codeFormat()) ? "23456789" : "LETTERS".equals(req.codeFormat()) ? "ABCDEFGHJKLMNPQRSTUVWXYZ" : CODE_ALPHABET;
        
        Set<String> uniqueSet = new LinkedHashSet<>(count * 2);
        int maxAttempts = count * 50;
        int attempts = 0;

        while (uniqueSet.size() < count && attempts++ < maxAttempts) {
            StringBuilder sb = new StringBuilder(prefix);
            for (int i = 0; i < length - prefix.length(); i++) {
                sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
            }
            uniqueSet.add(sb.toString());
        }

        if (uniqueSet.size() < count) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Could not generate enough unique voucher codes. Try increasing code length.");
        }

        // Filter out any codes that already exist in database
        List<String> candidates = new ArrayList<>(uniqueSet);
        Set<String> existing = new HashSet<>();
        // Query database in chunks of 500
        for (int i = 0; i < candidates.size(); i += 500) {
            List<String> sub = candidates.subList(i, Math.min(i + 500, candidates.size()));
            String inSql = String.join(",", Collections.nCopies(sub.size(), "?"));
            List<String> found = jdbcTemplate.query(
                    "SELECT code FROM vouchers WHERE code IN (" + inSql + ") UNION SELECT username FROM radcheck WHERE username IN (" + inSql + ")",
                    (rs, rowNum) -> rs.getString(1),
                    concatArgs(sub, sub)
            );
            existing.addAll(found);
        }

        candidates.removeAll(existing);

        // If collisions eliminated any, replenish them
        while (candidates.size() < count && attempts++ < maxAttempts * 2) {
            StringBuilder sb = new StringBuilder(prefix);
            for (int i = 0; i < length - prefix.length(); i++) {
                sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
            }
            String code = sb.toString();
            if (!uniqueSet.contains(code) && !existing.contains(code)) {
                if (!voucherRepository.existsByCode(code) && !radCheckRepository.existsByUsername(code)) {
                    candidates.add(code);
                    uniqueSet.add(code);
                }
            }
        }

        if (candidates.size() < count) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Could not generate enough unique voucher codes. Try increasing code length.");
        }

        return candidates.subList(0, count);
    }

    private static Object[] concatArgs(List<String> a, List<String> b) {
        Object[] res = new Object[a.size() + b.size()];
        int idx = 0;
        for (String s : a) res[idx++] = s;
        for (String s : b) res[idx++] = s;
        return res;
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
