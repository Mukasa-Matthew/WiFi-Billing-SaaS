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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
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
    @Mock private WalletService walletService;
    @Mock private PlatformEarningsService platformEarnings;

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
        service.setWalletService(walletService);
        service.setPlatformEarnings(platformEarnings);

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

    private static final String REF = "123e4567-e89b-12d3-a456-426614174000";

    /** What MarzPay's GET /transactions/{reference} returns. */
    private tools.jackson.databind.JsonNode providerSays(String status, long amount) {
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        tools.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
        tools.jackson.databind.node.ObjectNode txn = root.putObject("data").putObject("transaction");
        txn.put("reference", REF);
        txn.put("status", status);
        txn.putObject("amount").put("raw", amount);
        txn.putObject("net_amount").put("raw", amount * 93 / 100);
        txn.put("uuid", "prov-uuid-1");
        return root;
    }

    private void checkoutIsStored(SubscriptionCheckout checkout) {
        lenient().when(checkouts.findByReference(checkout.getReference())).thenReturn(Optional.of(checkout));
        lenient().when(checkouts.findById(checkout.getId())).thenReturn(Optional.of(checkout));
        lenient().when(checkouts.findByReferenceForUpdate(checkout.getReference())).thenReturn(Optional.of(checkout));
        lenient().when(gatewayConfig.credentialsFor(any(), any())).thenReturn(new MarzPayClient.Credentials("k", "s"));
    }

    private tools.jackson.databind.JsonNode callback(String status) {
        tools.jackson.databind.node.ObjectNode payload = new tools.jackson.databind.ObjectMapper().createObjectNode();
        payload.putObject("transaction").put("status", status).put("reference", REF);
        return payload;
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
                .id(101L).tenant(tenant).plan(defaultPlan).reference(REF)
                .amount(50000L).phone("+256700000002")
                .status(SubscriptionCheckout.STATUS_PENDING)
                .build();

        lenient().when(subs.findByTenantId(2L)).thenReturn(Optional.of(sub));
        when(invoices.save(any())).thenAnswer(i -> i.getArgument(0));
        when(payments.save(any())).thenAnswer(i -> i.getArgument(0));
        checkoutIsStored(checkout);
        when(marzPay.getTransaction(any(), eq(REF))).thenReturn(providerSays("completed", 50000L));

        boolean confirmed = service.confirmSubscriptionCheckout(REF, callback("completed"));

        assertTrue(confirmed);
        assertEquals(SubscriptionCheckout.STATUS_SUCCESSFUL, checkout.getStatus());
        assertEquals(Subscription.STATUS_ACTIVE, sub.getStatus());
        // New expiry must be 4 days + 1 month (approx 34-35 days from now, NOT 30 days from now)
        assertTrue(sub.getExpiresAt().isAfter(now.plusDays(33)));
    }

    @Test
    @DisplayName("A Mobile Money renewal adds the provider's net to platform earnings, not the gross")
    void momoRenewalCreditsTheNetTheProviderHolds() {
        Subscription sub = Subscription.builder().id(1L).tenant(tenant).status(Subscription.STATUS_ACTIVE)
                .plan(defaultPlan).expiresAt(OffsetDateTime.now().plusDays(2)).build();
        SubscriptionCheckout checkout = SubscriptionCheckout.builder()
                .id(107L).tenant(tenant).plan(defaultPlan).reference(REF)
                .amount(50000L).phone("+256700000002").status(SubscriptionCheckout.STATUS_PENDING).build();
        lenient().when(subs.findByTenantId(2L)).thenReturn(Optional.of(sub));
        when(invoices.save(any())).thenAnswer(i -> i.getArgument(0));
        when(payments.save(any())).thenAnswer(i -> { SubscriptionPayment p = i.getArgument(0); p.setId(77L); return p; });
        checkoutIsStored(checkout);
        when(marzPay.getTransaction(any(), eq(REF))).thenReturn(providerSays("completed", 50000L));

        service.confirmSubscriptionCheckout(REF, callback("completed"));

        verify(platformEarnings).creditSubscriptionPayment(argThat(p -> p.getId() == 77L), eq("MARZPAY"), eq(46500L));
    }

    @Test
    @DisplayName("Paying from the wallet takes the price, extends the subscription and credits platform earnings in full")
    void walletPaymentExtendsTheSubscription() {
        OffsetDateTime now = OffsetDateTime.now();
        Subscription sub = Subscription.builder().id(1L).tenant(tenant).status(Subscription.STATUS_GRACE_PERIOD)
                .plan(defaultPlan).expiresAt(now.minusDays(1)).build();
        lenient().when(subs.findByTenantId(2L)).thenReturn(Optional.of(sub));
        when(checkouts.findByReference(REF)).thenReturn(Optional.empty());
        when(checkouts.save(any())).thenAnswer(i -> { SubscriptionCheckout c = i.getArgument(0); c.setId(300L); return c; });
        when(walletService.debitForSubscription(eq(2L), eq(50000L), eq("SUB:" + REF), any())).thenReturn(true);
        when(invoices.save(any())).thenAnswer(i -> i.getArgument(0));
        when(payments.save(any())).thenAnswer(i -> { SubscriptionPayment p = i.getArgument(0); p.setId(78L); return p; });

        var res = service.payFromWallet(2L, 1L, REF);

        assertEquals(SubscriptionCheckout.STATUS_SUCCESSFUL, res.status());
        assertEquals(Subscription.STATUS_ACTIVE, sub.getStatus());
        assertTrue(sub.getExpiresAt().isAfter(now.plusDays(27)));
        verify(payments).save(argThat(p -> "WALLET".equals(p.getMethod())));
        verify(platformEarnings).creditSubscriptionPayment(argThat(p -> p.getId() == 78L), eq("MARZPAY"), eq(50000L));
        verifyNoInteractions(marzPay);
    }

    @Test
    @DisplayName("Not enough in the wallet: nothing is paid and the subscription is untouched")
    void walletTooLowChangesNothing() {
        Subscription sub = Subscription.builder().id(1L).tenant(tenant).status(Subscription.STATUS_ACTIVE)
                .plan(defaultPlan).expiresAt(OffsetDateTime.now().plusDays(2)).build();
        lenient().when(subs.findByTenantId(2L)).thenReturn(Optional.of(sub));
        when(checkouts.findByReference(REF)).thenReturn(Optional.empty());
        when(walletService.debitForSubscription(anyLong(), anyLong(), any(), any()))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Your wallet has UGX 1,000"));

        assertThrows(ResponseStatusException.class, () -> service.payFromWallet(2L, 1L, REF));
        verify(subs, never()).save(any());
        verify(checkouts, never()).save(any());
        verify(payments, never()).save(any());
    }

    @Test
    @DisplayName("A double tap with the same key returns the first payment and never charges again")
    void walletPaymentReplayDoesNotChargeTwice() {
        SubscriptionCheckout done = SubscriptionCheckout.builder().id(301L).tenant(tenant).plan(defaultPlan).reference(REF)
                .amount(50000L).phone(SubscriptionService.PAID_FROM_WALLET).status(SubscriptionCheckout.STATUS_SUCCESSFUL).build();
        when(checkouts.findByReference(REF)).thenReturn(Optional.of(done));

        var res = service.payFromWallet(2L, 1L, REF);

        assertEquals(SubscriptionCheckout.STATUS_SUCCESSFUL, res.status());
        verifyNoInteractions(walletService);
        verify(subs, never()).save(any());
    }

    @Test
    @DisplayName("Earlier renewals are added to platform earnings once, at the provider's net")
    void backfillCreditsEarlierRenewalsAtNet() {
        SubscriptionCheckout c = SubscriptionCheckout.builder().id(400L).tenant(tenant).plan(defaultPlan).reference(REF)
                .amount(25000L).phone("+256789781923").status(SubscriptionCheckout.STATUS_SUCCESSFUL).build();
        SubscriptionPayment old = SubscriptionPayment.builder().id(1L).amount(25000L).method("MOBILE_MONEY").externalRef(REF).build();
        when(payments.findAll()).thenReturn(List.of(old));
        when(platformEarnings.hasSubscriptionEarning(1L)).thenReturn(false);
        checkoutIsStored(c);
        when(marzPay.getTransaction(any(), eq(REF))).thenReturn(providerSays("completed", 25000L));

        service.backfillSubscriptionEarnings();
        service.backfillSubscriptionEarnings(); // second run in the same process does nothing

        verify(platformEarnings, times(1)).creditSubscriptionPayment(old, "MARZPAY", 23250L);
    }

    @Test
    @DisplayName("A forged 'successful' callback is ignored unless MarzPay itself confirms the payment")
    void forgedCallbackNeverExtendsTheSubscription() {
        SubscriptionCheckout checkout = SubscriptionCheckout.builder()
                .id(104L).tenant(tenant).plan(defaultPlan).reference(REF)
                .amount(50000L).phone("+256700000002")
                .status(SubscriptionCheckout.STATUS_PENDING)
                .build();
        checkoutIsStored(checkout);
        when(marzPay.getTransaction(any(), eq(REF))).thenReturn(providerSays("pending", 50000L));

        assertTrue(service.confirmSubscriptionCheckout(REF, callback("SUCCESSFUL")));

        assertEquals(SubscriptionCheckout.STATUS_PENDING, checkout.getStatus());
        verify(subs, never()).save(any());
    }

    @Test
    @DisplayName("A payment the provider confirms for less than the plan price is not applied")
    void underpaymentIsNotApplied() {
        SubscriptionCheckout checkout = SubscriptionCheckout.builder()
                .id(105L).tenant(tenant).plan(defaultPlan).reference(REF)
                .amount(50000L).phone("+256700000002")
                .status(SubscriptionCheckout.STATUS_PENDING)
                .build();
        checkoutIsStored(checkout);
        when(marzPay.getTransaction(any(), eq(REF))).thenReturn(providerSays("completed", 500L));

        service.confirmSubscriptionCheckout(REF, callback("completed"));

        assertEquals(SubscriptionCheckout.STATUS_FAILED, checkout.getStatus());
        verify(subs, never()).save(any());
    }

    @Test
    @DisplayName("Checkout sends MarzPay a UUID reference — it rejects anything else")
    void checkoutReferenceIsAUuid() {
        when(checkouts.save(any())).thenAnswer(i -> { SubscriptionCheckout c = i.getArgument(0); c.setId(200L); return c; });
        lenient().when(subs.findByTenantId(2L)).thenReturn(Optional.of(Subscription.builder().id(1L).tenant(tenant)
                .status(Subscription.STATUS_ACTIVE).plan(defaultPlan).expiresAt(OffsetDateTime.now().plusDays(3)).build()));
        when(gatewayConfig.credentialsFor(any(), any())).thenReturn(new MarzPayClient.Credentials("k", "s"));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "publicBaseUrl", "https://example.test");

        var res = service.initiateSubscriptionCheckout(2L, new com.wifibilling.dto.SubscriptionDto.CheckoutRequest(1L, "0789781923"));

        var ref = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(marzPay).collectMoney(any(), eq(50000L), eq("+256789781923"), ref.capture(), any(), any(), any());
        assertEquals(java.util.UUID.fromString(ref.getValue()).toString(), ref.getValue());
        assertEquals(ref.getValue(), res.reference());
        assertEquals(SubscriptionCheckout.STATUS_PENDING, res.status());
    }

    @Test
    @DisplayName("A provider timeout keeps the checkout pending instead of failing a payment that may have gone through")
    void providerTimeoutKeepsCheckoutPending() {
        when(checkouts.save(any())).thenAnswer(i -> { SubscriptionCheckout c = i.getArgument(0); c.setId(201L); return c; });
        lenient().when(subs.findByTenantId(2L)).thenReturn(Optional.of(Subscription.builder().id(1L).tenant(tenant)
                .status(Subscription.STATUS_ACTIVE).plan(defaultPlan).expiresAt(OffsetDateTime.now().plusDays(3)).build()));
        when(gatewayConfig.credentialsFor(any(), any())).thenReturn(new MarzPayClient.Credentials("k", "s"));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "publicBaseUrl", "https://example.test");
        when(marzPay.collectMoney(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new MarzPayClient.MarzPayException(-1, "MarzPay request failed: Read timed out"));

        var res = service.initiateSubscriptionCheckout(2L, new com.wifibilling.dto.SubscriptionDto.CheckoutRequest(1L, "0789781923"));

        assertEquals(SubscriptionCheckout.STATUS_PENDING, res.status());
        verify(checkouts, never()).findById(201L); // never marked failed
    }

    @Test
    @DisplayName("A provider rejection fails the checkout with a message the owner can act on")
    void providerRejectionExplainsWhatToCheck() {
        when(checkouts.save(any())).thenAnswer(i -> { SubscriptionCheckout c = i.getArgument(0); c.setId(202L); return c; });
        lenient().when(subs.findByTenantId(2L)).thenReturn(Optional.of(Subscription.builder().id(1L).tenant(tenant)
                .status(Subscription.STATUS_ACTIVE).plan(defaultPlan).expiresAt(OffsetDateTime.now().plusDays(3)).build()));
        when(gatewayConfig.credentialsFor(any(), any())).thenReturn(new MarzPayClient.Credentials("k", "s"));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "publicBaseUrl", "https://example.test");
        when(marzPay.collectMoney(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new MarzPayClient.MarzPayException(422, "{\"error_code\":\"VALIDATION_ERROR\"}"));

        var ex = assertThrows(ResponseStatusException.class, () ->
                service.initiateSubscriptionCheckout(2L, new com.wifibilling.dto.SubscriptionDto.CheckoutRequest(1L, "0789781923")));

        assertTrue(ex.getReason().contains("+256789781923"));
        verify(checkouts).findById(202L);
    }

    @Test
    @DisplayName("An owner cannot read another hotspot's checkout")
    void checkoutStatusIsTenantScoped() {
        SubscriptionCheckout checkout = SubscriptionCheckout.builder()
                .id(106L).tenant(tenant).plan(defaultPlan).reference(REF)
                .amount(50000L).phone("+256700000002")
                .status(SubscriptionCheckout.STATUS_SUCCESSFUL)
                .build();
        when(checkouts.findByReference(REF)).thenReturn(Optional.of(checkout));

        assertThrows(ResponseStatusException.class, () -> service.checkoutStatus(99L, REF));
        assertEquals(SubscriptionCheckout.STATUS_SUCCESSFUL, service.checkoutStatus(2L, REF).status());
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
                .reference(REF)
                .amount(50000L)
                .status(SubscriptionCheckout.STATUS_PENDING)
                .build();

        checkoutIsStored(checkout);
        lenient().when(subs.findByTenantId(2L)).thenReturn(Optional.of(sub));
        when(marzPay.getTransaction(any(), eq(REF))).thenReturn(providerSays("failed", 50000L));

        boolean handled = service.confirmSubscriptionCheckout(REF, callback("failed"));

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
                .reference(REF)
                .status(SubscriptionCheckout.STATUS_SUCCESSFUL) // already marked successful
                .build();

        checkoutIsStored(checkout);

        boolean confirmed = service.confirmSubscriptionCheckout(REF, callback("completed"));

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
