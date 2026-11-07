package com.wifibilling.service;

import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Transaction;
import com.wifibilling.model.Voucher;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.repository.VoucherRepository;
import com.wifibilling.util.AccessState;
import com.wifibilling.util.PhoneMatch;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * "Already bought? Find your voucher" on the captive portal. Anyone can type any phone
 * number, so a usable code is only shown to the device that paid for it. Anyone else
 * proves the purchase with the last digits of the MTN/Airtel Transaction ID from the
 * payment SMS; wrong guesses lock that number out for a while.
 */
@Service
@RequiredArgsConstructor
public class VoucherLookupService {

    static final int MAX_RESULTS = 20;
    static final int PROOF_DIGITS = 4;
    static final int MAX_WRONG_PROOFS = 5;
    static final Duration LOCKOUT = Duration.ofHours(1);

    private final TransactionRepository transactions;
    private final VoucherRepository vouchers;

    private record Failures(int count, Instant since) {}
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    /** The number's vouchers, newest first; codes only where this device paid for them. */
    @Transactional(readOnly = true)
    public Map<String, Object> find(Long tenantId, String phone, String mac) {
        PhoneMatch match = PhoneMatch.of(phone).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the phone number you paid with."));
        String device = normalizeMac(mac);
        List<Transaction> paid = transactions.findAllByTenantIdAndPhoneInAndStatusOrderByCreatedAtDesc(tenantId, match.exact(), "SUCCESSFUL")
                .stream().filter(t -> t.getVoucherCode() != null && !t.getVoucherCode().isBlank()).limit(MAX_RESULTS).toList();
        Map<String, Voucher> byCode = paid.isEmpty() ? Map.of()
                : vouchers.findAllByCodeIn(paid.stream().map(t -> code(t)).collect(Collectors.toSet())).stream()
                        .collect(Collectors.toMap(v -> v.getCode().toUpperCase(Locale.ROOT), Function.identity(), (a, b) -> a));
        OffsetDateTime now = OffsetDateTime.now();
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> items = new ArrayList<>();
        String firstOwnActive = "";
        for (Transaction tx : paid) {
            String code = code(tx);
            if (!seen.add(code)) continue;
            Voucher v = byCode.get(code);
            String status = status(v, now);
            boolean usable = "active".equals(status) || "unused".equals(status);
            boolean ownDevice = device != null && (device.equals(normalizeMac(tx.getClientMac()))
                    || (v != null && device.equals(normalizeMac(v.getClaimedMac()))));
            boolean reveal = usable && ownDevice;
            if (reveal && firstOwnActive.isEmpty()) firstOwnActive = code;
            PackagePlan plan = tx.getPackagePlan() != null ? tx.getPackagePlan() : v != null ? v.getPackagePlan() : null;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", tx.getId());
            item.put("code", reveal ? code : null);
            item.put("packageName", plan != null ? plan.getName() : "WiFi Package");
            item.put("price", tx.getAmount() != null ? tx.getAmount() : plan != null ? plan.getPrice() : null);
            item.put("durationSeconds", plan != null ? plan.getSessionTimeout() : null);
            item.put("status", usable ? status : "expired");
            item.put("isActive", usable);
            item.put("locked", usable && !reveal);
            item.put("canUnlock", usable && !reveal && proofDigits(tx) != null);
            item.put("firstLogin", v != null && v.getFirstLogin() != null ? v.getFirstLogin().toString() : null);
            OffsetDateTime expiry = v == null ? null : AccessState.expiry(v, v.getFirstLogin());
            item.put("expiresAt", expiry != null ? expiry.toString() : null);
            OffsetDateTime created = v != null && v.getCreatedAt() != null ? v.getCreatedAt() : tx.getCreatedAt();
            item.put("createdAt", created != null ? created.toString() : null);
            items.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("voucherCode", firstOwnActive);
        response.put("vouchers", items);
        return response;
    }

    /** Reveals one locked voucher when the caller knows the end of its MoMo Transaction ID. */
    @Transactional(readOnly = true)
    public String unlock(Long tenantId, String phone, Long transactionId, String proof) {
        PhoneMatch match = PhoneMatch.of(phone).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the phone number you paid with."));
        String key = tenantId + ":" + match.display();
        Instant now = Instant.now();
        Failures f = failures.get(key);
        if (f != null && f.count() >= MAX_WRONG_PROOFS && f.since().plus(LOCKOUT).isAfter(now)) {
            long minutes = Math.max(1, Duration.between(now, f.since().plus(LOCKOUT)).toMinutes());
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many wrong tries. Try again in " + minutes + " minutes, or ask the WiFi attendant.");
        }
        String given = proof == null ? "" : proof.replaceAll("[^0-9]", "");
        Optional<Transaction> tx = transactionId == null ? Optional.empty() : transactions.findById(transactionId)
                .filter(t -> t.getTenant() != null && tenantId.equals(t.getTenant().getId()))
                .filter(t -> "SUCCESSFUL".equals(t.getStatus()) && match.matches(t.getPhone()))
                .filter(t -> t.getVoucherCode() != null && !t.getVoucherCode().isBlank());
        String expected = tx.map(VoucherLookupService::proofDigits).orElse(null);
        if (tx.isPresent() && expected == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This payment can't be confirmed here. Please ask the WiFi attendant for your code.");
        }
        if (expected == null || given.length() != PROOF_DIGITS || !expected.equals(given)) {
            failures.compute(key, (k, old) -> old == null || old.since().plus(LOCKOUT).isBefore(now)
                    ? new Failures(1, now) : new Failures(old.count() + 1, old.since()));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That doesn't match the Transaction ID in your payment SMS.");
        }
        String code = code(tx.get());
        String status = status(vouchers.findByCode(code).orElse(null), OffsetDateTime.now());
        if (!"active".equals(status) && !"unused".equals(status)) {
            throw new ResponseStatusException(HttpStatus.GONE, "This voucher has expired.");
        }
        failures.remove(key);
        return code;
    }

    /** The last digits of the provider's Transaction ID, or null when we never received one. */
    private static String proofDigits(Transaction t) {
        String digits = t.getProviderTransactionId() == null ? "" : t.getProviderTransactionId().replaceAll("[^0-9]", "");
        return digits.length() < PROOF_DIGITS ? null : digits.substring(digits.length() - PROOF_DIGITS);
    }

    private static String code(Transaction t) {
        return t.getVoucherCode().trim().toUpperCase(Locale.ROOT);
    }

    /** "unused", "active", "expired" or "revoked"; a paid voucher not found is treated as unused. */
    private static String status(Voucher v, OffsetDateTime now) {
        if (v == null) return "unused";
        if (Boolean.TRUE.equals(v.getSchoolHold())) return "revoked";
        return AccessState.status(v, v.getFirstLogin(), AccessState.expiry(v, v.getFirstLogin()), now);
    }

    static String normalizeMac(String mac) {
        if (mac == null || mac.isBlank()) return null;
        String clean = mac.trim().toUpperCase(Locale.ROOT).replace('-', ':');
        return clean.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}") ? clean : null;
    }
}
