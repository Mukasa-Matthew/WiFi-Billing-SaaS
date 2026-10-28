package com.wifibilling.service;

import com.wifibilling.model.Customer;
import com.wifibilling.model.Voucher;
import com.wifibilling.repository.RadAcctRepository;
import com.wifibilling.util.AccessState;
import com.wifibilling.repository.CustomerRepository;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Keeps the customer directory in step with sales: every paying phone number
 * becomes (or matches) one customer, and the voucher it bought is linked to
 * them. Recording runs after the payment commits, in its own transaction, so
 * a problem here can never undo a confirmed payment; the startup backfill
 * catches anything missed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customers;
    private final TenantRepository tenants;
    private final VoucherRepository vouchers;
    private final TransactionRepository transactions;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;
    private final LeaderLockService leaderLockService;
    private final RadAcctRepository accounting;

    /**
     * One customer-directory row. {@code status}: active (access in use),
     * ready (paid, not used yet), expired, suspended (voucher revoked), or new
     * (no voucher yet). Session fields describe the current voucher: the one
     * in use, else one waiting to be used, else the latest.
     */
    public record Row(Long id, int number, String name, String phone, String notes, OffsetDateTime createdAt,
                      String status, String voucher, String packageName, OffsetDateTime sessionStart,
                      OffsetDateTime sessionEnd, boolean online, long dataBytes, OffsetDateTime lastOnline,
                      int purchases, BigDecimal totalSpent) {}

    @Transactional(readOnly = true)
    public List<Row> list(Long tenantId) {
        OffsetDateTime now = OffsetDateTime.now();
        List<Customer> all = customers.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        // Stable per-business numbering (C-0001 ...) in the order customers were recorded.
        Map<Long, Integer> numbers = new HashMap<>();
        all.stream().map(Customer::getId).sorted().forEach(id -> numbers.put(id, numbers.size() + 1));

        Map<Long, List<Voucher>> byCustomer = new HashMap<>();
        for (Voucher v : vouchers.findAllByTenantIdAndCustomerIsNotNullOrderByCreatedAtDesc(tenantId)) {
            byCustomer.computeIfAbsent(v.getCustomer().getId(), k -> new ArrayList<>()).add(v);
        }
        record Usage(long data, OffsetDateTime lastSeen, long live, OffsetDateTime firstStart) {}
        Map<String, Usage> usage = new HashMap<>();
        for (Object[] r : accounting.customerVoucherUsage(tenantId, now.minusMinutes(6))) {
            usage.put((String) r[0], new Usage(((Number) r[1]).longValue(), AccessState.toUtc(r[2]),
                    ((Number) r[3]).longValue(), AccessState.toUtc(r[4])));
        }
        Map<Long, BigDecimal> spent = new HashMap<>();
        for (Object[] r : transactions.spendByCustomer(tenantId)) {
            spent.put(((Number) r[0]).longValue(), r[1] instanceof BigDecimal b ? b : new BigDecimal(r[1].toString()));
        }

        List<Row> rows = new ArrayList<>();
        for (Customer c : all) {
            List<Voucher> vs = byCustomer.getOrDefault(c.getId(), List.of());
            Voucher current = null; String currentStatus = null;
            OffsetDateTime start = null, end = null, lastOnline = null;
            long data = 0; boolean online = false;
            for (Voucher v : vs) {
                Usage u = usage.get(v.getCode());
                OffsetDateTime first = v.getFirstLogin() != null ? v.getFirstLogin() : u == null ? null : u.firstStart();
                OffsetDateTime expiry = AccessState.expiry(v, first);
                String st = AccessState.status(v, first, expiry, now);
                int rank = rank(st), currentRank = current == null ? Integer.MAX_VALUE : rank(currentStatus);
                // Vouchers arrive newest first, so ties keep the newest.
                if (rank < currentRank) { current = v; currentStatus = st; start = first; end = expiry; }
                if (u != null) {
                    data += u.data();
                    online |= u.live() > 0;
                    if (u.lastSeen() != null && (lastOnline == null || u.lastSeen().isAfter(lastOnline))) lastOnline = u.lastSeen();
                }
            }
            String status = current == null ? "new"
                    : switch (currentStatus) {
                        case "active" -> "active";
                        case "unused" -> "ready";
                        case "revoked" -> "suspended";
                        default -> "expired";
                    };
            rows.add(new Row(c.getId(), numbers.get(c.getId()), c.getName(), c.getPhone(), c.getNotes(), c.getCreatedAt(),
                    status, current == null ? null : current.getCode(),
                    current == null || current.getPackagePlan() == null ? null : current.getPackagePlan().getName(),
                    start, end, online, data, lastOnline, vs.size(), spent.getOrDefault(c.getId(), BigDecimal.ZERO)));
        }
        return rows;
    }

    public record VoucherEntry(String code, String packageName, String status, OffsetDateTime createdAt,
                               OffsetDateTime sessionStart, OffsetDateTime sessionEnd, long dataBytes, boolean online) {}
    public record PaymentEntry(String reference, BigDecimal amount, String method, String voucher, OffsetDateTime createdAt) {}
    public record History(List<VoucherEntry> vouchers, List<PaymentEntry> payments) {}

    /** Every voucher a customer holds (newest first) with its usage, and their successful payments. */
    @Transactional(readOnly = true)
    public Optional<History> history(Long tenantId, Long customerId) {
        if (customers.findByIdAndTenantId(customerId, tenantId).isEmpty()) return Optional.empty();
        OffsetDateTime now = OffsetDateTime.now();
        List<Voucher> vs = vouchers.findAllByTenantIdAndCustomerIdOrderByCreatedAtDesc(tenantId, customerId);
        Set<String> codes = new HashSet<>();
        vs.forEach(v -> codes.add(v.getCode()));
        Map<String, Object[]> usage = new HashMap<>();
        if (!codes.isEmpty()) {
            for (Object[] r : accounting.customerVoucherUsage(tenantId, now.minusMinutes(6))) {
                if (codes.contains((String) r[0])) usage.put((String) r[0], r);
            }
        }
        List<VoucherEntry> voucherEntries = vs.stream().map(v -> {
            Object[] u = usage.get(v.getCode());
            OffsetDateTime first = v.getFirstLogin() != null ? v.getFirstLogin() : u == null ? null : AccessState.toUtc(u[4]);
            OffsetDateTime expiry = AccessState.expiry(v, first);
            return new VoucherEntry(v.getCode(), v.getPackagePlan() == null ? null : v.getPackagePlan().getName(),
                    AccessState.status(v, first, expiry, now), v.getCreatedAt(), first, expiry,
                    u == null ? 0 : ((Number) u[1]).longValue(), u != null && ((Number) u[3]).longValue() > 0);
        }).toList();
        List<PaymentEntry> payments = codes.isEmpty() ? List.of()
                : transactions.findAllByTenantIdAndVoucherCodeInAndStatusOrderByCreatedAtDesc(tenantId, codes, "SUCCESSFUL").stream()
                        .map(t -> new PaymentEntry(t.getReference(), t.getAmount(), t.getNetwork(), t.getVoucherCode(), t.getCreatedAt()))
                        .toList();
        return Optional.of(new History(voucherEntries, payments));
    }

    /** Which voucher describes the customer now: in use, then waiting, then anything (newest). */
    private static int rank(String status) {
        return "active".equals(status) ? 0 : "unused".equals(status) ? 1 : 2;
    }

    /** Records the buyer once the surrounding transaction commits (or now, if there is none). */
    public void recordBuyerAfterCommit(Long tenantId, String phone, String voucherCode, OffsetDateTime boughtAt) {
        if (localPhone(phone) == null) return;
        Runnable task = () -> {
            try {
                recordBuyer(tenantId, phone, voucherCode, boughtAt);
            } catch (RuntimeException e) {
                log.warn("Could not record customer for voucher {}: {}", voucherCode, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { task.run(); }
            });
        } else {
            task.run();
        }
    }

    /**
     * Finds or creates the tenant's customer for this phone and links the
     * voucher to them if it has no customer yet. Idempotent; concurrent calls
     * for the same phone are serialised with a transaction-scoped advisory lock.
     *
     * @return the customer, or empty when the phone isn't a usable number
     */
    public Optional<Customer> recordBuyer(Long tenantId, String phone, String voucherCode, OffsetDateTime boughtAt) {
        String local = localPhone(phone);
        if (local == null) return Optional.empty();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return Optional.ofNullable(tx.execute(status -> {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "customer:" + tenantId + ":" + local);
            Customer customer = customers.findFirstByTenantIdAndPhoneInOrderByIdAsc(tenantId, phoneVariants(local))
                    .orElseGet(() -> customers.save(Customer.builder()
                            .tenant(tenants.getReferenceById(tenantId))
                            // No name comes with a mobile money payment; the owner can add one.
                            .name(local)
                            .phone(local)
                            .createdAt(boughtAt != null ? boughtAt : OffsetDateTime.now())
                            .build()));
            if (voucherCode != null) {
                vouchers.findByCodeAndTenantId(voucherCode, tenantId).ifPresent(v -> {
                    if (v.getCustomer() == null) {
                        v.setCustomer(customer);
                        vouchers.save(v);
                    }
                });
            }
            return customer;
        }));
    }

    /**
     * One-off catch-up for sales made before buyers were recorded (and any
     * recording that failed). Only paid vouchers without a customer are read,
     * so after the first run this is a single cheap query.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        leaderLockService.executeIfLeader(LeaderLockService.JOB_CUSTOMER_BACKFILL, 600, 60, () -> {
            int linked = 0;
            for (Object[] row : transactions.paidVouchersWithoutCustomer()) {
                try {
                    if (recordBuyer(((Number) row[0]).longValue(), (String) row[1], (String) row[2], AccessState.toUtc(row[3])).isPresent()) linked++;
                } catch (RuntimeException e) {
                    log.warn("Customer backfill skipped voucher {}: {}", row[2], e.getMessage());
                }
            }
            if (linked > 0) log.info("Customer backfill linked {} paid vouchers to customers", linked);
        });
    }

    /** Ugandan numbers in local form (0772270145); null for MANUAL and other non-numbers. */
    static String localPhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("256") && digits.length() == 12) return "0" + digits.substring(3);
        if (digits.startsWith("0") && digits.length() == 10) return digits;
        if (digits.length() == 9 && digits.startsWith("7")) return "0" + digits;
        return null;
    }

    /** Every stored spelling of a local number, so manually entered customers match too. */
    static Set<String> phoneVariants(String local) {
        String rest = local.substring(1);
        Set<String> out = new LinkedHashSet<>();
        out.add(local);
        out.add("256" + rest);
        out.add("+256" + rest);
        out.add("+256 " + rest.substring(0, 3) + " " + rest.substring(3, 6) + " " + rest.substring(6));
        out.add(local.substring(0, 4) + " " + rest.substring(3, 6) + " " + rest.substring(6));
        return out;
    }

}
