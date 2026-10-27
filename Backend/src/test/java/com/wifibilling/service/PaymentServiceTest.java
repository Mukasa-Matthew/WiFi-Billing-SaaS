package com.wifibilling.service;

import com.wifibilling.dto.GenerateVouchersRequest;
import com.wifibilling.dto.PaymentDto;
import com.wifibilling.dto.VoucherResponse;
import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Router;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.Transaction;
import com.wifibilling.repository.PackagePlanRepository;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.service.payment.PaymentGatewayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private RouterRepository routerRepository;

    @Mock
    private PackagePlanRepository packagePlanRepository;

    @Mock
    private VoucherService voucherService;

    @Mock
    private PaymentGatewayClient gatewayClient;

    @Mock
    private GatewayConfigService gatewayConfig;

    @Mock
    private com.wifibilling.service.payment.MarzPayClient marzPay;

    @Mock
    private WalletService walletService;

    @Mock
    private com.wifibilling.config.PlatformProperties platformProperties;

    @Mock
    private SubscriptionService subscriptionService;

    private PaymentService paymentService;

    private Tenant tenant;
    private Router router;
    private PackagePlan packagePlan;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                transactionRepository,
                routerRepository,
                packagePlanRepository,
                voucherService,
                gatewayClient,
                gatewayConfig,
                marzPay,
                walletService,
                platformProperties,
                subscriptionService
        );
        lenient().when(gatewayConfig.modeFor(any(Tenant.class))).thenReturn("PLATFORM");
        lenient().when(gatewayConfig.platformConfigured()).thenReturn(false);
        lenient().when(subscriptionService.canTenantAcceptCustomerPayments(any())).thenReturn(true);

        tenant = Tenant.builder().id(1L).name("Test Tenant").email("test@example.com").build();
        router = Router.builder().id(10L).token("tok_valid123456").tenant(tenant).name("Test Router").build();
        packagePlan = PackagePlan.builder().id(100L).tenant(tenant).name("1 Hour").price(new BigDecimal("1000.00")).sessionTimeout(3600).build();
    }

    @Test
    void hiddenAndDisabledPlansRejectNewPayments() {
        when(routerRepository.findByToken("tok_valid123456")).thenReturn(Optional.of(router));
        when(packagePlanRepository.findByIdAndTenantId(100L, 1L)).thenReturn(Optional.of(packagePlan));
        for (String availability : java.util.List.of("HIDDEN", "DISABLED")) {
            packagePlan.setAvailability(availability);
            var request = new PaymentDto.InitiateRequest("tok_valid123456", 100L, "256771234567", null, null, null);
            assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                    () -> paymentService.initiatePayment(request)).getStatusCode());
        }
        verifyNoInteractions(gatewayClient);
    }

    @Test
    void initiatePayment_Success() {
        when(routerRepository.findByToken("tok_valid123456")).thenReturn(Optional.of(router));
        when(packagePlanRepository.findByIdAndTenantId(100L, 1L)).thenReturn(Optional.of(packagePlan));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));
        when(gatewayClient.requestPayment(any(Transaction.class))).thenReturn("EXT-REF-123");

        PaymentDto.InitiateRequest req = new PaymentDto.InitiateRequest("tok_valid123456", 100L, "256771234567", null, null, null);
        PaymentDto.InitiateResponse response = paymentService.initiatePayment(req);

        assertNotNull(response);
        assertEquals(PaymentService.STATUS_PENDING, response.status());
        assertEquals(new BigDecimal("1000.00"), response.amount());
        assertTrue(response.reference().startsWith("PAY-"));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, atLeastOnce()).save(captor.capture());
        Transaction saved = captor.getValue();
        assertEquals("MTN", saved.getNetwork());
        assertEquals("+256771234567", saved.getPhone());
    }

    @Test
    void initiatePayment_ThrowsNotFoundWhenPackageDoesNotBelongToRouterTenant() {
        when(routerRepository.findByToken("tok_valid123456")).thenReturn(Optional.of(router));
        when(packagePlanRepository.findByIdAndTenantId(999L, 1L)).thenReturn(Optional.empty());

        PaymentDto.InitiateRequest req = new PaymentDto.InitiateRequest("tok_valid123456", 999L, "256771234567", null, null, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> {
            paymentService.initiatePayment(req);
        });
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void initiatePaymentRejectsPackageRestrictedToAnotherRouter() {
        Router otherRouter = Router.builder().id(11L).tenant(tenant).build();
        packagePlan.setRouter(otherRouter);
        when(routerRepository.findByToken("tok_valid123456")).thenReturn(Optional.of(router));
        when(packagePlanRepository.findByIdAndTenantId(100L, 1L)).thenReturn(Optional.of(packagePlan));

        var req = new PaymentDto.InitiateRequest("tok_valid123456", 100L, "256771234567", null, null, null);
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> paymentService.initiatePayment(req)).getStatusCode());
        verifyNoInteractions(transactionRepository, gatewayClient);
    }

    @Test
    void processWebhook_SuccessfulPaymentGeneratesVoucher() {
        Transaction tx = Transaction.builder()
                .id(50L)
                .tenant(tenant)
                .packagePlan(packagePlan)
                .router(router)
                .reference("PAY-REF123456")
                .phone("256771234567")
                .amount(new BigDecimal("1000.00"))
                .status(PaymentService.STATUS_PENDING)
                .build();

        when(transactionRepository.findByReferenceForUpdate("PAY-REF123456")).thenReturn(Optional.of(tx));

        VoucherResponse vResp = new VoucherResponse(
                1L, "VOUCH999", "unused", 100L, "1 Hour",
                new BigDecimal("1000.00"), null, null, null, OffsetDateTime.now(), null, null, null, null, "ONLINE", "MANUAL", false
        );
        when(voucherService.generate(eq(1L), any(GenerateVouchersRequest.class), isNull(), isNull())).thenReturn(List.of(vResp));

        PaymentDto.WebhookRequest webhook = new PaymentDto.WebhookRequest("PAY-REF123456", "SUCCESSFUL", "MOMO-EXT-1", null);
        paymentService.processWebhook(webhook);

        assertEquals(PaymentService.STATUS_SUCCESSFUL, tx.getStatus());
        assertEquals("VOUCH999", tx.getVoucherCode());
        assertEquals("MOMO-EXT-1", tx.getExternalReference());
        assertNotNull(tx.getCompletedAt());

        verify(voucherService).generate(eq(1L), argThat(r -> r.packageId().equals(100L) && r.count().equals(1)), isNull(), isNull());
        verify(transactionRepository).save(tx);
    }

    private Transaction pendingCheckoutTx(String checkoutRef) {
        return Transaction.builder()
                .id(60L).tenant(tenant).packagePlan(packagePlan).router(router)
                .reference("PAY-CHK1").checkoutRef(checkoutRef)
                .phone("+256771234567").amount(new BigDecimal("1000.00"))
                .network("MTN").status(PaymentService.STATUS_PENDING)
                .gatewayMode("PLATFORM")
                .createdAt(OffsetDateTime.now())
                .build();
    }

    @Test
    void duplicateCheckoutReturnsSameTransactionAndNeverChargesAgain() {
        Transaction tx = pendingCheckoutTx("chk-abc");
        when(transactionRepository.findByCheckoutRefForUpdate("chk-abc")).thenReturn(Optional.of(tx));

        var req = new PaymentDto.InitiateRequest("tok_valid123456", 100L, "256771234567", null, "chk-abc", null);
        var res = paymentService.initiatePayment(req);

        assertEquals("PAY-CHK1", res.reference());
        assertEquals(PaymentService.STATUS_PENDING, res.status());
        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(gatewayClient);
        verifyNoInteractions(marzPay);
    }

    @Test
    void resumeOnSuccessfulCheckoutReportsSuccessWithoutRecharging() {
        Transaction tx = pendingCheckoutTx("chk-done");
        tx.setStatus(PaymentService.STATUS_SUCCESSFUL);
        tx.setVoucherCode("CODE1234");
        when(transactionRepository.findByCheckoutRefForUpdate("chk-done")).thenReturn(Optional.of(tx));

        var res = paymentService.initiatePayment(
                new PaymentDto.InitiateRequest("tok_valid123456", 100L, "256771234567", null, "chk-done", null));

        assertEquals(PaymentService.STATUS_SUCCESSFUL, res.status());
        verifyNoInteractions(gatewayClient, marzPay);
    }

    @Test
    void checkoutResumeWithDifferentDetailsIsRejected() {
        Transaction tx = pendingCheckoutTx("chk-x");
        when(transactionRepository.findByCheckoutRefForUpdate("chk-x")).thenReturn(Optional.of(tx));

        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () ->
                paymentService.initiatePayment(new PaymentDto.InitiateRequest(
                        "tok_valid123456", 200L, "256771234567", null, "chk-x", null))).getStatusCode());
    }

    @Test
    void marzPayDispatchFailureStaysPendingAndKeepsProviderReference() {
        when(gatewayConfig.platformConfigured()).thenReturn(true); // real MarzPay path
        when(gatewayConfig.credentialsFor(any(), any())).thenReturn(
                new com.wifibilling.service.payment.MarzPayClient.Credentials("k", "s"));
        when(gatewayConfig.countryFor(any(), any())).thenReturn("UG");
        when(platformProperties.getPublicBaseUrl()).thenReturn("https://pay.example.com");
        when(routerRepository.findByToken("tok_valid123456")).thenReturn(Optional.of(router));
        when(packagePlanRepository.findByIdAndTenantId(100L, 1L)).thenReturn(Optional.of(packagePlan));
        // Dispatch is claimed atomically then sent after commit.
        final Transaction[] holder = new Transaction[1];
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> {
            holder[0] = i.getArgument(0);
            return holder[0];
        });
        when(transactionRepository.findById(any())).thenAnswer(i -> Optional.ofNullable(holder[0]));
        when(transactionRepository.findByIdFetchRefs(any())).thenAnswer(i -> Optional.ofNullable(holder[0]));
        when(transactionRepository.claimDispatch(any())).thenReturn(1);
        when(marzPay.collectMoney(any(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("connect timed out"));

        var res = paymentService.initiatePayment(
                new PaymentDto.InitiateRequest("tok_valid123456", 100L, "256771234567", null, "chk-net", null));

        assertEquals(PaymentService.STATUS_PENDING, res.status());
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, atLeastOnce()).save(captor.capture());
        Transaction last = captor.getValue();
        assertEquals(PaymentService.STATUS_PENDING, last.getStatus());
        assertNotNull(last.getExternalReference());
        assertTrue(last.getNotes().contains("uncertain"));
    }

    @Test
    void duplicateWebhookDoesNotRegenerateVoucher() {
        Transaction tx = Transaction.builder()
                .id(50L).tenant(tenant).packagePlan(packagePlan).router(router)
                .reference("PAY-DUP").phone("256771234567")
                .amount(new BigDecimal("1000.00"))
                .status(PaymentService.STATUS_SUCCESSFUL).voucherCode("CODE777")
                .build();
        when(transactionRepository.findByReferenceForUpdate("PAY-DUP")).thenReturn(Optional.of(tx));

        paymentService.processWebhook(new PaymentDto.WebhookRequest("PAY-DUP", "SUCCESSFUL", "X", null));

        verify(voucherService, never()).generate(any(), any(), any(), any());
        verify(walletService, never()).creditCollection(any(), anyLong(), any(), any(), any());
    }

    @Test
    void macIsNormalizedAndStoredOnTransaction() {
        when(routerRepository.findByToken("tok_valid123456")).thenReturn(Optional.of(router));
        when(packagePlanRepository.findByIdAndTenantId(100L, 1L)).thenReturn(Optional.of(packagePlan));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));
        when(gatewayClient.requestPayment(any(Transaction.class))).thenReturn("EXT-1");

        paymentService.initiatePayment(new PaymentDto.InitiateRequest(
                "tok_valid123456", 100L, "256771234567", null, null, "aa-bb-cc-dd-ee-ff"));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, atLeastOnce()).save(captor.capture());
        assertEquals("AA:BB:CC:DD:EE:FF", captor.getValue().getClientMac());
    }

    @Test
    void customerFailureMessageExplainsKnownProviderReasons() {
        assertTrue(PaymentService.customerFailureMessage(
                "Provider status: failed — WiFi Minute Internet - MTN Payment Requested - Failed: LOW_BALANCE_OR_PAYEE_LIMIT_REACHED_OR_NOT_ALLOWED")
                .contains("balance is too low"));
        assertTrue(PaymentService.customerFailureMessage(
                "Provider rejected collection: MarzPay 422: The minimum amount is 500 UGX").contains("minimum"));
        assertTrue(PaymentService.customerFailureMessage(
                "Provider rejected collection: MarzPay 422: invalid request").contains("could not be started"));
        assertTrue(PaymentService.customerFailureMessage("Provider status: cancelled").contains("cancelled"));
        assertTrue(PaymentService.customerFailureMessage("Provider status: expired").contains("expired"));
        // No established reason -> null so the portal keeps its generic message.
        assertNull(PaymentService.customerFailureMessage("Provider status: failed"));
        assertNull(PaymentService.customerFailureMessage(null));
    }

    @Test
    void concurrentDuplicateWebhookDeliveriesAreStrictlyIdempotent() {
        Transaction tx = new Transaction();
        tx.setId(999L);
        tx.setReference("PAY-CONCURRENT-1");
        tx.setStatus("PENDING");
        tx.setAmount(new java.math.BigDecimal("2000.00"));
        tx.setTenant(tenant);
        tx.setPackagePlan(packagePlan);
        tx.setRouter(router);

        when(transactionRepository.findByReferenceForUpdate("PAY-CONCURRENT-1")).thenReturn(Optional.of(tx));
        VoucherResponse vResp = new VoucherResponse(
                10L, "QB-CONCUR", "unused", 100L, "1 Hour",
                new BigDecimal("2000.00"), null, null, null, OffsetDateTime.now(), null, null, null, null, "ONLINE", "MANUAL", false
        );
        when(voucherService.generate(any(), any(), any(), any())).thenReturn(List.of(vResp));

        // First callback: processes and transitions tx from PENDING to SUCCESSFUL
        paymentService.processWebhook(new PaymentDto.WebhookRequest(
                "PAY-CONCURRENT-1", "SUCCESSFUL", "EXT-1", null));

        assertEquals("SUCCESSFUL", tx.getStatus());
        assertEquals("QB-CONCUR", tx.getVoucherCode());
        verify(voucherService, times(1)).generate(any(), any(), any(), any());

        // Second concurrent/replayed callback: tx is already SUCCESSFUL; must be skipped idempotently
        paymentService.processWebhook(new PaymentDto.WebhookRequest(
                "PAY-CONCURRENT-1", "SUCCESSFUL", "EXT-1", null));

        // Proves that duplicate voucher generation is strictly impossible
        verify(voucherService, times(1)).generate(any(), any(), any(), any());
        assertEquals("SUCCESSFUL", tx.getStatus());
    }
}
