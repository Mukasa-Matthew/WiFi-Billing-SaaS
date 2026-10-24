package com.wifibilling.service;

import com.wifibilling.model.*;
import com.wifibilling.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Tenant SaaS subscription lifecycle. Deliberately simple:
 *  - one subscription row per tenant, anniversary-based monthly billing
 *  - one invoice per billing period (unique constraint = no duplicates)
 *  - payments recorded by a super admin after verifying receipt
 * Computed payment state is derived from invoices — never stored, so logins,
 * restarts, or edits can never drift the schedule.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    public static final String KEY_DEFAULT_FEE = "subscription.default_fee";
    public static final long FALLBACK_DEFAULT_FEE = 25_000L;

    private final SubscriptionRepository subscriptions;
    private final SubscriptionInvoiceRepository invoices;
    private final SubscriptionPaymentRepository payments;
    private final PlatformSettingRepository settings;
    private final TenantRepository tenants;

    // ---------- display status ----------

    /** SUSPENDED/CANCELLED pass through; ACTIVE subs get a computed payment state. */
    public String displayStatus(Subscription sub, LocalDate today) {
        if (!Subscription.ACTIVE.equals(sub.getStatus())) return sub.getStatus();
        List<SubscriptionInvoice> unpaid = invoices.findAllByTenantIdAndStatus(
                sub.getTenant().getId(), SubscriptionInvoice.UNPAID);
        for (SubscriptionInvoice inv : unpaid) {
            long paid = payments.sumByInvoiceId(inv.getId());
            if (paid < inv.getAmountDue() && today.isAfter(inv.getPeriodEnd())) return "PAST_DUE";
        }
        for (SubscriptionInvoice inv : unpaid) {
            if (today.isAfter(inv.getPeriodEnd().minusDays(7))) return "PAYMENT_DUE";
        }
        return Subscription.ACTIVE;
    }

    // ---------- admin actions ----------

    @Transactional
    public Subscription createOrUpdate(Long tenantId, Long monthlyFee, LocalDate startDate) {
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        LocalDate start = startDate != null ? startDate : LocalDate.now();
        long fee = monthlyFee != null && monthlyFee > 0 ? monthlyFee : defaultFee();

        return subscriptions.findByTenantId(tenantId).map(existing -> {
            existing.setMonthlyFee(fee);
            if (!Subscription.ACTIVE.equals(existing.getStatus())) {
                existing.setStatus(Subscription.ACTIVE);
            }
            return subscriptions.save(existing);
        }).orElseGet(() -> {
            Subscription sub = Subscription.builder()
                    .tenant(tenant)
                    .monthlyFee(fee)
                    .anchorDay(start.getDayOfMonth())
                    .startDate(start)
                    .nextRenewalDate(renewalAfter(start, start.getDayOfMonth()))
                    .status(Subscription.ACTIVE)
                    .build();
            sub = subscriptions.save(sub);
            issueInvoice(sub, start, sub.getNextRenewalDate());
            return sub;
        });
    }

    @Transactional
    public Subscription updateFee(Long subscriptionId, long monthlyFee) {
        if (monthlyFee <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fee must be positive");
        Subscription sub = get(subscriptionId);
        sub.setMonthlyFee(monthlyFee); // applies to the NEXT issued invoice; history untouched
        return subscriptions.save(sub);
    }

    @Transactional
    public Subscription suspend(Long subscriptionId) {
        Subscription sub = get(subscriptionId);
        sub.setStatus(Subscription.SUSPENDED);
        return subscriptions.save(sub);
    }

    @Transactional
    public Subscription reactivate(Long subscriptionId) {
        Subscription sub = get(subscriptionId);
        if (Subscription.CANCELLED.equals(sub.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cancelled subscriptions cannot be reactivated — create a new one");
        }
        sub.setStatus(Subscription.ACTIVE);
        return subscriptions.save(sub);
    }

    @Transactional
    public Subscription cancel(Long subscriptionId) {
        Subscription sub = get(subscriptionId);
        sub.setStatus(Subscription.CANCELLED);
        return subscriptions.save(sub);
    }

    /**
     * Records a verified payment against the oldest unpaid invoice (or a specific
     * one). Idempotent via the unique recordRef the caller retries can supply.
     */
    @Transactional
    public SubscriptionPayment recordPayment(Long subscriptionId, Long invoiceId, long amount,
                                             String method, String externalRef, String recordRef,
                                             Long adminId, String note) {
        if (amount <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be positive");
        if (recordRef != null) {
            var existing = payments.findByRecordRef(recordRef);
            if (existing.isPresent()) return existing.get();
        }
        Subscription sub = get(subscriptionId);
        SubscriptionInvoice inv = invoiceId != null
                ? invoices.findById(invoiceId).filter(i -> i.getSubscription().getId().equals(sub.getId()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"))
                : invoices.findAllBySubscriptionIdOrderByPeriodStartDesc(sub.getId()).stream()
                    .filter(i -> SubscriptionInvoice.UNPAID.equals(i.getStatus()))
                    .reduce((a, b) -> a.getPeriodStart().isBefore(b.getPeriodStart()) ? a : b)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No unpaid invoice"));

        SubscriptionPayment p = SubscriptionPayment.builder()
                .subscription(sub).invoice(inv).tenant(sub.getTenant())
                .amount(amount).paidAt(OffsetDateTime.now())
                .method(method == null || method.isBlank() ? "CASH" : method.trim().toUpperCase())
                .externalRef(externalRef == null || externalRef.isBlank() ? null : externalRef.trim())
                .recordRef(recordRef != null ? recordRef : UUID.randomUUID().toString())
                .recordedBy(adminId).note(note)
                .build();
        p = payments.save(p);

        if (payments.sumByInvoiceId(inv.getId()) >= inv.getAmountDue()) {
            inv.setStatus(SubscriptionInvoice.PAID);
            inv.setPaidAt(OffsetDateTime.now());
            invoices.save(inv);
        }
        return p;
    }

    // ---------- billing sweep ----------

    /**
     * Advances ACTIVE subscriptions whose current period ended: issues the next
     * period's invoice and bumps nextRenewalDate. Idempotent — the unique
     * (subscription, period_start) constraint makes re-runs safe. Called lazily
     * on reads AND by a daily job so nothing depends on server uptime.
     */
    @Transactional
    public void ensureCurrent(Subscription sub, LocalDate today) {
        if (!Subscription.ACTIVE.equals(sub.getStatus())) return;
        while (!sub.getNextRenewalDate().isAfter(today)) {
            LocalDate periodStart = sub.getNextRenewalDate();
            LocalDate periodEnd = sub.renewalAfter(periodStart);
            issueInvoice(sub, periodStart, periodEnd);
            sub.setNextRenewalDate(periodEnd);
            subscriptions.save(sub);
        }
    }

    @Scheduled(cron = "0 30 3 * * *") // daily 03:30
    @Transactional
    public void dailySweep() {
        subscriptions.findAllByStatus(Subscription.ACTIVE)
                .forEach(s -> ensureCurrent(s, LocalDate.now()));
    }

    private void issueInvoice(Subscription sub, LocalDate start, LocalDate end) {
        try {
            invoices.saveAndFlush(SubscriptionInvoice.builder()
                    .subscription(sub).tenant(sub.getTenant())
                    .periodStart(start).periodEnd(end)
                    .amountDue(sub.getMonthlyFee()) // frozen at issue
                    .status(SubscriptionInvoice.UNPAID)
                    .build());
        } catch (DataIntegrityViolationException alreadyIssued) {
            // Unique (subscription, period_start) — another run issued it; fine.
        }
    }

    // ---------- reads ----------

    public Subscription get(Long id) {
        return subscriptions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found"));
    }

    public Subscription getForTenant(Long tenantId) {
        return subscriptions.findByTenantId(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No subscription for this tenant"));
    }

    public List<Subscription> all() { return subscriptions.findAll(); }

    public List<SubscriptionInvoice> billingHistory(Long subscriptionId) {
        return invoices.findAllBySubscriptionIdOrderByPeriodStartDesc(subscriptionId);
    }

    public List<SubscriptionPayment> paymentHistory(Long subscriptionId) {
        return payments.findAllBySubscriptionIdOrderByPaidAtDesc(subscriptionId);
    }

    public long outstandingFor(Long subscriptionId) {
        return billingHistory(subscriptionId).stream()
                .filter(i -> SubscriptionInvoice.UNPAID.equals(i.getStatus()))
                .mapToLong(i -> i.getAmountDue() - payments.sumByInvoiceId(i.getId()))
                .sum();
    }

    public long defaultFee() {
        return settings.findById(KEY_DEFAULT_FEE)
                .map(s -> Long.parseLong(s.getValue())).orElse(FALLBACK_DEFAULT_FEE);
    }

    @Transactional
    public long setDefaultFee(long fee) {
        if (fee <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fee must be positive");
        settings.save(PlatformSetting.builder().key(KEY_DEFAULT_FEE).value(String.valueOf(fee)).build());
        return fee;
    }

    private static LocalDate renewalAfter(LocalDate from, int anchorDay) {
        LocalDate next = from.plusMonths(1);
        return LocalDate.of(next.getYear(), next.getMonth(), Math.min(anchorDay, next.lengthOfMonth()));
    }
}
