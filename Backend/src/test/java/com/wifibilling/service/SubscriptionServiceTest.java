package com.wifibilling.service;

import com.wifibilling.model.*;
import com.wifibilling.repository.*;
import com.wifibilling.service.payment.MarzPayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SubscriptionServiceTest {

    @Mock private SubscriptionRepository subs;
    @Mock private SubscriptionPlanRepository plans;
    @Mock private SubscriptionInvoiceRepository invoices;
    @Mock private SubscriptionPaymentRepository payments;
    @Mock private SubscriptionCheckoutRepository checkouts;
    @Mock private SubscriptionNotificationRepository notifications;
    @Mock private SubscriptionPeriodHistoryRepository periodHistoryRepository;
    @Mock private PlatformSettingRepository settings;
    @Mock private TenantRepository tenants;
    @Mock private MarzPayClient marzPay;
    @Mock private GatewayConfigService gatewayConfig;
    @Mock private EmailService emailService;
    @Mock private EmailTemplates emailTemplates;

    private SubscriptionService service;
    private Tenant tenant;
    private SubscriptionPlan defaultPlan;

    @BeforeEach
    void setUp() {
        service = new SubscriptionService(
                subs, plans, invoices, payments, checkouts,
                notifications, periodHistoryRepository, tenants, settings, marzPay,
                gatewayConfig, emailService, emailTemplates
        );
        org.springframework.test.util.ReflectionTestUtils.setField(service, "subscriptionEnforcementEnabled", true);

        tenant = Tenant.builder()
                .id(2L)
                .name("Trent Hotspot")
                .email("trent@example.com")
                .phone("+256700000002")
                .role("ROLE_ADMIN")
                .status("ACTIVE")
                .createdAt(OffsetDateTime.now().minusDays(30))
                .build();

        defaultPlan = SubscriptionPlan.builder()
                .id(1L)
                .name("Monthly Basic")
                .price(50000L)
                .durationUnit("MONTHS")
                .durationValue(1)
                .active(true)
                .displayOrder(1)
                .build();

        lenient().when(tenants.findById(2L)).thenReturn(Optional.of(tenant));
        lenient().when(subs.save(any(Subscription.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(plans.findAllByActiveTrueOrderByDisplayOrderAscPriceAsc()).thenReturn(List.of(defaultPlan));
        lenient().when(plans.findById(1L)).thenReturn(Optional.of(defaultPlan));
    }

    @Test
    @DisplayName("New tenant registration receives a 7-day trial starting from now (not retroactive)")
    void testGrantTrialIfEligible_NewTenant() {
        when(subs.findByTenantId(2L)).thenReturn(Optional.empty());

        Subscription sub = service.grantTrialIfEligible(tenant);

        assertNotNull(sub);
        assertEquals(Subscription.STATUS_TRIALING, sub.getStatus());
        assertTrue(sub.getTrialGranted());
        assertNotNull(sub.getTrialStartsAt());
        assertNotNull(sub.getTrialEndsAt());
        assertNotNull(sub.getGraceUntil());

        // Trial must be 7 days from now, NOT based on tenant createdAt (which was 30 days ago)
        long durationDays = java.time.Duration.between(sub.getTrialStartsAt(), sub.getTrialEndsAt()).toDays();
        assertEquals(7, durationDays);

        // Grace period must be 2 days beyond expiry
        long graceDays = java.time.Duration.between(sub.getTrialEndsAt(), sub.getGraceUntil()).toDays();
        assertEquals(2, graceDays);
    }

    @Test
    @DisplayName("Trial grant is idempotent: existing tenant with subscription is not granted another trial")
    void testGrantTrialIfEligible_Idempotent() {
        Subscription existing = Subscription.builder()
                .id(10L)
                .tenant(tenant)
                .trialGranted(true)
                .status(Subscription.STATUS_ACTIVE)
                .build();

        when(subs.findByTenantId(2L)).thenReturn(Optional.of(existing));

        Subscription result = service.grantTrialIfEligible(tenant);
        assertSame(existing, result);
        verify(subs, never()).save(any());
    }

    @Test
    @DisplayName("Customer payments are permitted during active trial")
    void testCustomerPaymentsPermittedDuringTrial() {
        Subscription sub = Subscription.builder()
                .tenant(tenant)
                .status(Subscription.STATUS_TRIALING)
                .trialGranted(true)
                .expiresAt(OffsetDateTime.now().plusDays(5))
                .graceUntil(OffsetDateTime.now().plusDays(7))
                .build();

        lenient().when(subs.findByTenantId(2L)).thenReturn(Optional.of(sub));

        assertTrue(service.canTenantAcceptCustomerPayments(2L));
        assertTrue(service.isTenantManagementAllowed(2L));
    }

    @Test
    @DisplayName("Customer payments AND management actions are blocked immediately once trial/subscription expires (during grace period)")
    void testCustomerPaymentsAndManagementBlockedDuringGracePeriod() {
        OffsetDateTime now = OffsetDateTime.now();
        Subscription sub = Subscription.builder()
                .tenant(tenant)
                .status(Subscription.STATUS_TRIALING)
                .trialGranted(true)
                .expiresAt(now.minusHours(2)) // Expired 2 hours ago
                .graceUntil(now.plusDays(2))   // Within 2-day grace window
                .build();

        lenient().when(subs.findByTenantId(2L)).thenReturn(Optional.of(sub));

        assertFalse(service.canTenantAcceptCustomerPayments(2L), "Customer payments MUST be blocked when subscription expires");
        assertFalse(service.isTenantManagementAllowed(2L), "Tenant management operations MUST be restricted during grace period");
        assertThrows(ResponseStatusException.class, () -> service.assertTenantManagementAllowed(2L));
    }

    @Test
    @DisplayName("Early renewal extends remaining days without penalty")
    void testEarlyRenewalPreservesRemainingTime() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime currentExpiresAt = now.plusDays(4); // 4 days remaining

        Subscription sub = Subscription.builder()
                .id(1L)
                .tenant(tenant)
                .status(Subscription.STATUS_TRIALING)
                .plan(defaultPlan)
                .monthlyFee(50000L)
                .expiresAt(currentExpiresAt)
                .graceUntil(currentExpiresAt.plusDays(2))
                .build();

        SubscriptionCheckout checkout = SubscriptionCheckout.builder()
                .id(101L)
                .tenant(tenant)
                .plan(defaultPlan)
                .reference("SUB-12345")
                .amount(50000L)
                .phone("+256700000002")
                .status(SubscriptionCheckout.STATUS_PENDING)
                .build();

        lenient().when(subs.findByTenantId(2L)).thenReturn(Optional.of(sub));
        when(invoices.save(any())).thenAnswer(i -> i.getArgument(0));
        when(payments.save(any())).thenAnswer(i -> i.getArgument(0));
        when(checkouts.findByReference("SUB-12345")).thenReturn(Optional.of(checkout));

        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        tools.jackson.databind.node.ObjectNode payload = mapper.createObjectNode();
        tools.jackson.databind.node.ObjectNode txNode = payload.putObject("transaction");
        txNode.put("status", "SUCCESSFUL");
        txNode.put("amount", 50000L);

        boolean confirmed = service.confirmSubscriptionCheckout("SUB-12345", payload);

        assertTrue(confirmed);
        assertEquals(SubscriptionCheckout.STATUS_SUCCESSFUL, checkout.getStatus());
        assertEquals(Subscription.STATUS_ACTIVE, sub.getStatus());
        // New expiry must be 4 days + 1 month (approx 34-35 days from now, NOT 30 days from now)
        assertTrue(sub.getExpiresAt().isAfter(now.plusDays(33)));
    }

    @Test
    @DisplayName("Reminders trigger notifications idempotently")
    void testRemindersIdempotent() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plusDays(5).minusHours(1); // 4.9 days left
        Subscription sub = Subscription.builder()
                .id(1L)
                .tenant(tenant)
                .status(Subscription.STATUS_TRIALING)
                .monthlyFee(50000L)
                .expiresAt(expiresAt)
                .graceUntil(expiresAt.plusDays(2))
                .build();

        when(notifications.existsBySubscriptionIdAndNotificationTypeAndBillingCycleKey(any(), any(), any())).thenReturn(false);

        service.checkAndDispatchReminders(sub, now);

        verify(notifications, times(1)).save(any(SubscriptionNotification.class));
    }

    @Test
    @DisplayName("Failed or pending webhook callbacks never activate subscription")
    void testFailedOrPendingWebhookNeverActivates() {
        Subscription sub = Subscription.builder()
                .id(1L)
                .tenant(tenant)
                .status(Subscription.STATUS_SUSPENDED)
                .expiresAt(OffsetDateTime.now().minusDays(1))
                .build();

        SubscriptionCheckout checkout = SubscriptionCheckout.builder()
                .id(102L)
                .tenant(tenant)
                .plan(defaultPlan)
                .reference("SUB-FAIL-1")
                .status(SubscriptionCheckout.STATUS_PENDING)
                .build();

        when(checkouts.findByReference("SUB-FAIL-1")).thenReturn(Optional.of(checkout));
        lenient().when(subs.findByTenantId(2L)).thenReturn(Optional.of(sub));

        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        tools.jackson.databind.node.ObjectNode payload = mapper.createObjectNode();
        tools.jackson.databind.node.ObjectNode txNode = payload.putObject("transaction");
        txNode.put("status", "FAILED");

        boolean handled = service.confirmSubscriptionCheckout("SUB-FAIL-1", payload);

        assertTrue(handled);
        assertEquals(SubscriptionCheckout.STATUS_FAILED, checkout.getStatus());
        assertEquals(Subscription.STATUS_SUSPENDED, sub.getStatus(), "Subscription status must remain EXPIRED");
    }

    @Test
    @DisplayName("Duplicate successful webhooks do not double-extend subscription")
    void testDuplicateWebhookIdempotent() {
        Subscription sub = Subscription.builder()
                .id(1L)
                .tenant(tenant)
                .status(Subscription.STATUS_ACTIVE)
                .expiresAt(OffsetDateTime.now().plusDays(30))
                .build();

        SubscriptionCheckout checkout = SubscriptionCheckout.builder()
                .id(103L)
                .tenant(tenant)
                .plan(defaultPlan)
                .reference("SUB-DUP-1")
                .status(SubscriptionCheckout.STATUS_SUCCESSFUL) // already marked successful
                .build();

        when(checkouts.findByReference("SUB-DUP-1")).thenReturn(Optional.of(checkout));

        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        tools.jackson.databind.node.ObjectNode payload = mapper.createObjectNode();
        tools.jackson.databind.node.ObjectNode txNode = payload.putObject("transaction");
        txNode.put("status", "SUCCESSFUL");

        boolean confirmed = service.confirmSubscriptionCheckout("SUB-DUP-1", payload);

        assertTrue(confirmed, "Duplicate webhook should acknowledge 200 without reprocessing");
        verify(subs, never()).save(any());
        verify(periodHistoryRepository, never()).save(any());
    }

    @Test
    void whenEnforcementDisabled_existingTenantsCanManageAndAcceptPaymentsEvenAfterExpiry() {
        // When enforcement is disabled (default production state)
        org.springframework.test.util.ReflectionTestUtils.setField(service, "subscriptionEnforcementEnabled", false);

        // Then tenant can still manage operations and accept payments regardless of expiration
        assertTrue(service.canTenantAcceptCustomerPayments(2L), "Tenant MUST be allowed to accept payments when enforcement is disabled");
        assertTrue(service.isTenantManagementAllowed(2L), "Tenant MUST be allowed to manage routers/vouchers when enforcement is disabled");
        assertDoesNotThrow(() -> service.assertTenantManagementAllowed(2L));
    }

    @Test
    void whenEnforcementDisabled_dailyReminderSweepIsSkippedCompletely() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "subscriptionEnforcementEnabled", false);

        service.dailyReminderSweep();

        // When enforcement is disabled, background reminder sweeps must never touch tenant subscriptions or send notifications
        verify(subs, never()).findAll();
        verify(notifications, never()).save(any());
        verify(emailService, never()).send(any(), any(), any());
    }

    @Test
    void whenEnforcementDisabled_doDailyReminderSweepDoesNotDispatchReminders() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "subscriptionEnforcementEnabled", false);

        service.doDailyReminderSweep();

        verify(subs, never()).findAll();
        verify(notifications, never()).save(any());
        verify(emailService, never()).send(any(), any(), any());
    }
}
