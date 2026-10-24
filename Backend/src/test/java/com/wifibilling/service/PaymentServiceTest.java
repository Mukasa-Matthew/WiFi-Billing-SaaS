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
                gatewayClient
        );

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
            var request = new PaymentDto.InitiateRequest("tok_valid123456", 100L, "256771234567", null);
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

        PaymentDto.InitiateRequest req = new PaymentDto.InitiateRequest("tok_valid123456", 100L, "256771234567", null);
        PaymentDto.InitiateResponse response = paymentService.initiatePayment(req);

        assertNotNull(response);
        assertEquals(PaymentService.STATUS_PENDING, response.status());
        assertEquals(new BigDecimal("1000.00"), response.amount());
        assertTrue(response.reference().startsWith("PAY-"));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, atLeastOnce()).save(captor.capture());
        Transaction saved = captor.getValue();
        assertEquals("MTN", saved.getNetwork());
        assertEquals("256771234567", saved.getPhone());
    }

    @Test
    void initiatePayment_ThrowsNotFoundWhenPackageDoesNotBelongToRouterTenant() {
        when(routerRepository.findByToken("tok_valid123456")).thenReturn(Optional.of(router));
        when(packagePlanRepository.findByIdAndTenantId(999L, 1L)).thenReturn(Optional.empty());

        PaymentDto.InitiateRequest req = new PaymentDto.InitiateRequest("tok_valid123456", 999L, "256771234567", null);

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

        var req = new PaymentDto.InitiateRequest("tok_valid123456", 100L, "256771234567", null);
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

        when(transactionRepository.findByReference("PAY-REF123456")).thenReturn(Optional.of(tx));

        VoucherResponse vResp = new VoucherResponse(
                1L, "VOUCH999", "unused", 100L, "1 Hour",
                new BigDecimal("1000.00"), null, null, null, OffsetDateTime.now(), null, null, null, null, "ONLINE", "MANUAL", false
        );
        when(voucherService.generate(eq(1L), any(GenerateVouchersRequest.class))).thenReturn(List.of(vResp));

        PaymentDto.WebhookRequest webhook = new PaymentDto.WebhookRequest("PAY-REF123456", "SUCCESSFUL", "MOMO-EXT-1", null);
        paymentService.processWebhook(webhook);

        assertEquals(PaymentService.STATUS_SUCCESSFUL, tx.getStatus());
        assertEquals("VOUCH999", tx.getVoucherCode());
        assertEquals("MOMO-EXT-1", tx.getExternalReference());
        assertNotNull(tx.getCompletedAt());

        verify(voucherService).generate(eq(1L), argThat(r -> r.packageId().equals(100L) && r.count().equals(1)));
        verify(transactionRepository).save(tx);
    }
}
