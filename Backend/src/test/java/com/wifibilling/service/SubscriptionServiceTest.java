package com.wifibilling.service;

import com.wifibilling.model.*;
import com.wifibilling.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock private SubscriptionRepository subs;
    @Mock private SubscriptionInvoiceRepository invoices;
    @Mock private SubscriptionPaymentRepository payments;
    @Mock private PlatformSettingRepository settings;
    @Mock private TenantRepository tenants;
    @Mock private PlatformEarningsService platformEarnings;

    private SubscriptionService service;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        service = new SubscriptionService(subs, invoices, payments, settings, tenants, platformEarnings);
        tenant = Tenant.builder().id(1L).name("Trent Hotspot").build();
        lenient().when(tenants.findById(1L)).thenReturn(Optional.of(tenant));
        lenient().when(subs.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(invoices.saveAndFlush(any())).thenAnswer(i -> {
            SubscriptionInvoice inv = i.getArgument(0);
            if (inv.getId() == null) inv.setId(100L);
            return inv;
        });
        lenient().when(payments.save(any())).thenAnswer(i -> {
            SubscriptionPayment p = i.getArgument(0);
            if (p.getId() == null) p.setId(200L);
            return p;
        });
    }

    private Subscription newSub(LocalDate start) {
        Subscription s = Subscription.builder()
                .id(10L).tenant(tenant).monthlyFee(25_000L)
                .anchorDay(start.getDayOfMonth()).startDate(start)
                .nextRenewalDate(start.plusMonths(1).withDayOfMonth(
                        Math.min(start.getDayOfMonth(), start.plusMonths(1).lengthOfMonth())))
                .status(Subscription.ACTIVE).build();
        return s;
    }

    @Test
    void createAssignsDefaultFeeAndFirstInvoice() {
        when(subs.findByTenantId(1L)).thenReturn(Optional.empty());
        Subscription s = service.createOrUpdate(1L, null, LocalDate.of(2026, 10, 24));
        assertEquals(25_000L, s.getMonthlyFee());
        assertEquals(LocalDate.of(2026, 10, 24), s.getStartDate());
        assertEquals(LocalDate.of(2026, 11, 24), s.getNextRenewalDate());
        verify(invoices).saveAndFlush(argThat(i ->
                i.getPeriodStart().equals(LocalDate.of(2026, 10, 24))
                        && i.getPeriodEnd().equals(LocalDate.of(2026, 11, 24))
                        && i.getAmountDue() == 25_000L));
    }

    @Test
    void customFeeOverridesDefault() {
        when(subs.findByTenantId(1L)).thenReturn(Optional.empty());
        Subscription s = service.createOrUpdate(1L, 35_000L, LocalDate.of(2026, 10, 24));
        assertEquals(35_000L, s.getMonthlyFee());
    }

    @Test
    void jan31AnchorsCorrectlyAcrossShortMonths() {
        Subscription s = newSub(LocalDate.of(2026, 1, 31));
        LocalDate feb = s.renewalAfter(LocalDate.of(2026, 1, 31));
        assertEquals(LocalDate.of(2026, 2, 28), feb);          // clamped to Feb
        LocalDate mar = s.renewalAfter(feb);
        assertEquals(LocalDate.of(2026, 3, 31), mar);          // anchor preserved
        // leap year: 2028 Feb has 29 days
        Subscription leap = newSub(LocalDate.of(2028, 1, 31));
        assertEquals(LocalDate.of(2028, 2, 29), leap.renewalAfter(LocalDate.of(2028, 1, 31)));
    }

    @Test
    void ensureCurrentIssuesNextInvoiceOncePerPeriod() {
        Subscription s = newSub(LocalDate.of(2026, 10, 24));
        LocalDate today = LocalDate.of(2026, 11, 25); // one period ended
        service.ensureCurrent(s, today);
        assertEquals(LocalDate.of(2026, 12, 24), s.getNextRenewalDate());
        verify(invoices).saveAndFlush(argThat(i ->
                i.getPeriodStart().equals(LocalDate.of(2026, 11, 24))
                        && i.getPeriodEnd().equals(LocalDate.of(2026, 12, 24))));

        // Re-running must not create another invoice for the same period
        service.ensureCurrent(s, today);
        verify(invoices, times(1)).saveAndFlush(any());
    }

    @Test
    void paymentMarksInvoicePaidWhenCovered() {
        Subscription s = newSub(LocalDate.of(2026, 10, 24));
        SubscriptionInvoice inv = SubscriptionInvoice.builder()
                .id(100L).subscription(s).tenant(tenant)
                .periodStart(LocalDate.of(2026, 10, 24))
                .periodEnd(LocalDate.of(2026, 11, 24))
                .amountDue(25_000L).status(SubscriptionInvoice.UNPAID).build();
        when(subs.findById(10L)).thenReturn(Optional.of(s));
        when(invoices.findAllBySubscriptionIdOrderByPeriodStartDesc(10L)).thenReturn(List.of(inv));
        when(payments.sumByInvoiceId(100L)).thenReturn(25_000L);

        var p = service.recordPayment(10L, null, 25_000L, "CASH", null, null, 9L, null);
        assertNotNull(p);
        assertEquals(SubscriptionInvoice.PAID, inv.getStatus());
        assertNotNull(inv.getPaidAt());
    }

    @Test
    void recordRefMakesPaymentIdempotent() {
        Subscription s = newSub(LocalDate.of(2026, 10, 24));
        SubscriptionPayment existing = SubscriptionPayment.builder()
                .id(55L).subscription(s).recordRef("REC-1").amount(25_000L).build();
        when(payments.findByRecordRef("REC-1")).thenReturn(Optional.of(existing));
        var p = service.recordPayment(10L, null, 25_000L, "CASH", null, "REC-1", 9L, null);
        assertEquals(55L, p.getId());
        verify(payments, never()).save(any());
    }

    @Test
    void feeChangeDoesNotRewriteIssuedInvoices() {
        Subscription s = newSub(LocalDate.of(2026, 10, 24));
        SubscriptionInvoice issued = SubscriptionInvoice.builder()
                .id(100L).subscription(s).tenant(tenant).amountDue(25_000L)
                .periodStart(LocalDate.of(2026, 10, 24)).periodEnd(LocalDate.of(2026, 11, 24))
                .status(SubscriptionInvoice.UNPAID).build();
        when(subs.findById(10L)).thenReturn(Optional.of(s));
        service.updateFee(10L, 35_000L);
        assertEquals(35_000L, s.getMonthlyFee());
        assertEquals(25_000L, issued.getAmountDue());           // history untouched
        assertEquals(LocalDate.of(2026, 11, 24), s.getNextRenewalDate()); // schedule unchanged
    }

    @Test
    void pastDueDetection() {
        Subscription s = newSub(LocalDate.of(2026, 10, 24));
        SubscriptionInvoice inv = SubscriptionInvoice.builder()
                .id(100L).subscription(s).tenant(tenant).amountDue(25_000L)
                .periodStart(LocalDate.of(2026, 10, 24)).periodEnd(LocalDate.of(2026, 11, 24))
                .status(SubscriptionInvoice.UNPAID).build();
        when(invoices.findAllByTenantIdAndStatus(1L, SubscriptionInvoice.UNPAID)).thenReturn(List.of(inv));
        when(payments.sumByInvoiceId(100L)).thenReturn(0L);

        assertEquals("PAST_DUE", service.displayStatus(s, LocalDate.of(2026, 11, 25)));
        assertEquals("PAYMENT_DUE", service.displayStatus(s, LocalDate.of(2026, 11, 20)));
        assertEquals("ACTIVE", service.displayStatus(s, LocalDate.of(2026, 11, 10)));
    }

    @Test
    void suspendReactivateAndCancelRules() {
        Subscription s = newSub(LocalDate.of(2026, 10, 24));
        when(subs.findById(10L)).thenReturn(Optional.of(s));

        service.suspend(10L);
        assertEquals(Subscription.SUSPENDED, s.getStatus());
        service.reactivate(10L);
        assertEquals(Subscription.ACTIVE, s.getStatus());
        service.cancel(10L);
        var ex = assertThrows(ResponseStatusException.class, () -> service.reactivate(10L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void suspendedSubscriptionDoesNotBill() {
        Subscription s = newSub(LocalDate.of(2026, 10, 24));
        s.setStatus(Subscription.SUSPENDED);
        service.ensureCurrent(s, LocalDate.of(2026, 12, 25));
        assertEquals(LocalDate.of(2026, 11, 24), s.getNextRenewalDate()); // unchanged
        verify(invoices, never()).saveAndFlush(any());
    }
}
