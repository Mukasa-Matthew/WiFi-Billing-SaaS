package com.wifibilling.service;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.model.PlatformEarningsAccount;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.TenantGatewayConfig;
import com.wifibilling.model.WalletAccount;
import com.wifibilling.model.WithdrawalRequest;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.repository.WithdrawalRequestRepository;
import com.wifibilling.service.payment.MarzPayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private WithdrawalRequestRepository withdrawals;
    @Mock private WalletService walletService;
    @Mock private GatewayConfigService gatewayConfig;
    @Mock private MarzPayClient marzPay;
    @Mock private PlatformProperties props;
    @Mock private TenantRepository tenantRepository;
    @Mock private PlatformEarningsService platformEarnings;
    @Mock private PlatformTransactionManager txManager;

    private WithdrawalService service;
    private PlatformEarningsAccount reconciliation;
    /** The single withdrawal row the repository fake holds. */
    private WithdrawalRequest stored;

    @BeforeEach
    void setUp() {
        service = new WithdrawalService(withdrawals, walletService, gatewayConfig, marzPay, props,
                tenantRepository, platformEarnings, txManager);
        ReflectionTestUtils.setField(service, "minAmount", 1_000L);
        ReflectionTestUtils.setField(service, "maxAmount", 5_000_000L);
        ReflectionTestUtils.setField(service, "maxOpenPerTenant", 1L);
        ReflectionTestUtils.setField(service, "feeReservePercent", 10L);
        ReflectionTestUtils.setField(service, "feeReserveMin", 1_000L);
        ReflectionTestUtils.setField(service, "requireApproval", false);
        ReflectionTestUtils.setField(service, "livePayoutsEnabled", true);
        ReflectionTestUtils.setField(service, "recheckAfterSeconds", 120L);

        reconciliation = new PlatformEarningsAccount();
        reconciliation.setReconcileStatus(PlatformEarningsAccount.RECON_CLEAN);
        lenient().when(platformEarnings.account()).thenReturn(reconciliation);

        Tenant tenant = Tenant.builder().id(5L).name("T").build();
        WalletAccount wallet = WalletAccount.builder().id(3L).tenant(tenant).availableBalance(47_180L).build();
        lenient().when(walletService.walletForUpdate(5L)).thenReturn(wallet);
        lenient().when(tenantRepository.findById(5L)).thenReturn(Optional.of(tenant));
        lenient().when(gatewayConfig.credentialsFor(any(), eq(TenantGatewayConfig.MODE_PLATFORM)))
                .thenReturn(new MarzPayClient.Credentials("k", "s"));
        lenient().when(gatewayConfig.platformConfigured()).thenReturn(true);
        lenient().when(props.getPublicBaseUrl()).thenReturn("https://api.example");

        lenient().when(withdrawals.save(any())).thenAnswer(i -> {
            WithdrawalRequest w = i.getArgument(0);
            if (w.getId() == null) w.setId(9L);
            stored = w;
            return w;
        });
        lenient().when(withdrawals.findById(9L)).thenAnswer(i -> Optional.ofNullable(stored));
        lenient().when(withdrawals.findByIdForUpdate(9L)).thenAnswer(i -> Optional.ofNullable(stored));
        lenient().when(withdrawals.findByPayoutRefForUpdate(any())).thenAnswer(i ->
                Optional.ofNullable(stored).filter(w -> w.getPayoutRef().equals(i.getArgument(0))));
        // Mirrors the conditional UPDATE: only an unsent, open withdrawal can be claimed.
        lenient().when(withdrawals.claimDispatch(eq(9L), any())).thenAnswer(i -> {
            if (stored == null || stored.getDispatchedAt() != null
                    || !List.of(WithdrawalRequest.REQUESTED, WithdrawalRequest.PENDING_APPROVAL).contains(stored.getStatus())) {
                return 0;
            }
            stored.setStatus(WithdrawalRequest.PROCESSING);
            stored.setDispatchedAt(OffsetDateTime.now());
            return 1;
        });
    }

    private void providerCharges(long fee) throws Exception {
        when(marzPay.sendMoney(any(), anyLong(), any(), any(), any(), any(), any())).thenReturn(JSON.readTree(
                "{\"data\":{\"transaction\":{\"uuid\":\"p-1\"},\"withdrawal\":{\"charge\":{\"raw\":" + fee + "}}}}"));
    }

    private WithdrawalRequest sent(String status) {
        return WithdrawalRequest.builder().id(9L).amount(20_000L).fee(600L).totalDeduction(20_600L)
                .tenant(Tenant.builder().id(5L).build()).phone("+256772000000").payoutRef("r-9")
                .status(status).dispatchedAt(OffsetDateTime.now().minusMinutes(10)).build();
    }

    // ---------- balance check ----------

    @Test
    void amountPlusFeeAboveBalanceIsRejectedWithAClearMessage() {
        var ex = assertThrows(ResponseStatusException.class, () -> service.request(5L, 46_000, "0755032436", null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Insufficient balance: UGX 46,000 plus up to UGX 4,600 held for the gateway fee comes to UGX 50,600, "
                + "but your available balance is UGX 47,180. The most you can withdraw right now is UGX 42,890.",
                ex.getReason());
        verify(withdrawals, never()).save(any());
        verifyNoInteractions(marzPay);
    }

    @Test
    void maxWithdrawableLeavesRoomForTheFeeReserve() {
        assertEquals(42_890L, service.maxWithdrawable(47_180));
        assertTrue(42_890L + service.feeReserve(42_890) <= 47_180L);
        assertTrue(42_891L + service.feeReserve(42_891) > 47_180L);
        assertEquals(0L, service.maxWithdrawable(400));
        assertEquals(0L, service.maxWithdrawable(-200));
    }

    @Test
    void feeReserveCoversEveryChargeSeenSoFar() {
        // Observed MarzPay payout fees: 3% (10,000 / 22,000 / 46,000) and 900 on 9,000.
        assertTrue(service.feeReserve(9_000) >= 900);
        assertTrue(service.feeReserve(46_000) >= 1_380);
        assertEquals(1_000L, service.feeReserve(2_000));
        assertEquals(4_600L, service.feeReserve(46_000));
    }

    @Test
    void requestIsBlockedWhileProviderBalanceDisagreesWithBooks() {
        reconciliation.setReconcileStatus(PlatformEarningsAccount.RECON_DISCREPANCY);

        var ex = assertThrows(ResponseStatusException.class, () -> service.request(5L, 20_000, "0772000000", null));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
        verify(withdrawals, never()).save(any());
        verifyNoInteractions(marzPay);
    }

    // ---------- idempotency ----------

    @Test
    void sameIdempotencyKeyReturnsTheOriginalAndNeverPaysTwice() throws Exception {
        providerCharges(1_200);
        WithdrawalRequest first = service.request(5L, 40_000, "0755032436", "key-123456");
        when(withdrawals.findByTenantIdAndIdempotencyKey(5L, "key-123456")).thenReturn(Optional.of(stored));

        WithdrawalRequest again = service.request(5L, 40_000, "0755032436", "key-123456");

        assertSame(first, again);
        verify(walletService, times(1)).reserveForWithdrawal(anyLong(), anyLong(), any(), any());
        verify(marzPay, times(1)).sendMoney(any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void reusedIdempotencyKeyWithDifferentAmountIsRejected() {
        WithdrawalRequest earlier = sent(WithdrawalRequest.COMPLETED);
        when(withdrawals.findByTenantIdAndIdempotencyKey(5L, "key-123456")).thenReturn(Optional.of(earlier));

        var ex = assertThrows(ResponseStatusException.class, () -> service.request(5L, 30_000, "0772000000", "key-123456"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(withdrawals, never()).save(any());
    }

    @Test
    void replayIsReturnedEvenWhilePayoutsArePaused() {
        WithdrawalRequest earlier = sent(WithdrawalRequest.COMPLETED);
        stored = earlier;
        when(withdrawals.findByTenantIdAndIdempotencyKey(5L, "key-123456")).thenReturn(Optional.of(earlier));
        reconciliation.setReconcileStatus(PlatformEarningsAccount.RECON_DISCREPANCY);

        assertSame(earlier, service.request(5L, 20_000, "0772000000", "key-123456"));
        verifyNoInteractions(marzPay);
    }

    @Test
    void malformedIdempotencyKeyIsRejected() {
        var ex = assertThrows(ResponseStatusException.class, () -> service.request(5L, 20_000, "0772000000", "bad key!"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    // ---------- dispatch ----------

    @Test
    void unusedFeeReserveIsReturnedOnceTheRealFeeIsKnown() throws Exception {
        providerCharges(1_200);

        WithdrawalRequest w = service.request(5L, 40_000, "0755032436", null);

        assertEquals(WithdrawalRequest.PROCESSING, w.getStatus());
        assertNotNull(w.getDispatchedAt());
        assertEquals(1_200L, w.getFee());
        assertEquals(41_200L, w.getTotalDeduction());
        verify(walletService).reserveForWithdrawal(5L, 44_000L, "RES:" + w.getPayoutRef(), 9L);
        verify(walletService).adjustReservation(5L, -2_800L, "FEEADJ:" + w.getPayoutRef(), 9L);
        verify(platformEarnings, never()).absorbPayoutFeeShortfall(anyLong(), any(), any());
    }

    /** A fee beyond the reserve and the tenant's balance: the platform pays the rest, never the tenant's debt. */
    @Test
    void feeBeyondReserveAndBalanceIsAbsorbedByThePlatform() throws Exception {
        providerCharges(5_000);
        when(walletService.adjustReservation(eq(5L), eq(1_000L), any(), eq(9L))).thenReturn(600L);

        WithdrawalRequest w = service.request(5L, 40_000, "0755032436", null);

        assertEquals(5_000L, w.getFee());
        assertEquals(44_400L, w.getTotalDeduction());
        verify(platformEarnings).absorbPayoutFeeShortfall(eq(600L), eq("FEEADJ:" + w.getPayoutRef()), any());
    }

    @Test
    void unknownDispatchFailureKeepsTheCommittedWithdrawalForReconciliation() {
        when(marzPay.sendMoney(any(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("socket reset after write"));

        WithdrawalRequest w = assertDoesNotThrow(() -> service.request(5L, 20_000, "0772000000", null));

        assertEquals(WithdrawalRequest.PROCESSING, w.getStatus());
        assertTrue(w.getFailureReason().startsWith("Dispatch outcome unknown"));
        verify(walletService).reserveForWithdrawal(eq(5L), eq(22_000L), any(), eq(9L));
        verify(walletService, never()).releaseWithdrawal(anyLong(), anyLong(), any(), any());
    }

    @Test
    void alreadyClaimedWithdrawalIsNeverSentAgain() {
        stored = sent(WithdrawalRequest.PROCESSING);

        service.dispatch(9L);

        verifyNoInteractions(marzPay);
    }

    @Test
    void completionCallbackBeforeDispatchResponseStillSettlesTheFee() throws Exception {
        // MarzPay's "completed" callback lands while sendMoney is still returning.
        when(marzPay.sendMoney(any(), anyLong(), any(), any(), any(), any(), any())).thenAnswer(i -> {
            service.processDisbursementCallback(JSON.readTree(
                    "{\"transaction\":{\"provider_reference\":\"" + stored.getPayoutRef() + "\",\"status\":\"completed\"}}"));
            return JSON.readTree("{\"data\":{\"withdrawal\":{\"charge\":{\"raw\":1200}}}}");
        });

        WithdrawalRequest w = service.request(5L, 40_000, "0755032436", null);

        assertEquals(WithdrawalRequest.COMPLETED, w.getStatus());
        verify(walletService).finalizeWithdrawal(5L, 44_000L, "PAY:" + w.getPayoutRef(), 9L);
        verify(walletService).settleCompletedPayoutFee(5L, -2_800L, "FEEADJ:" + w.getPayoutRef(), 9L);
        verify(walletService, never()).adjustReservation(anyLong(), anyLong(), any(), any());
        assertEquals(41_200L, w.getTotalDeduction());
    }

    // ---------- provider outcome ----------

    @Test
    void callbackForAWithdrawalThatWasNeverSentIsIgnored() throws Exception {
        stored = sent(WithdrawalRequest.PENDING_APPROVAL);
        stored.setDispatchedAt(null);

        service.processDisbursementCallback(JSON.readTree(
                "{\"transaction\":{\"provider_reference\":\"r-9\",\"status\":\"completed\"}}"));

        assertEquals(WithdrawalRequest.PENDING_APPROVAL, stored.getStatus());
        verifyNoInteractions(walletService);
    }

    @Test
    void duplicateCompletionCallbackIsANoOp() throws Exception {
        stored = sent(WithdrawalRequest.PROCESSING);
        var payload = JSON.readTree("{\"transaction\":{\"provider_reference\":\"r-9\",\"status\":\"completed\"}}");

        service.processDisbursementCallback(payload);
        service.processDisbursementCallback(payload);

        verify(walletService, times(1)).finalizeWithdrawal(5L, 20_600L, "PAY:r-9", 9L);
    }

    @Test
    void recheckCompletesAStuckPayoutFromTheProviderRecord() throws Exception {
        stored = sent(WithdrawalRequest.PROCESSING);
        when(withdrawals.findTop50ByStatusAndDispatchedAtBeforeOrderByDispatchedAtAsc(eq(WithdrawalRequest.PROCESSING), any()))
                .thenReturn(List.of(stored));
        when(marzPay.getTransaction(any(), eq("r-9"))).thenReturn(JSON.readTree(
                "{\"data\":{\"transaction\":{\"status\":\"completed\",\"uuid\":\"p-9\"}}}"));

        service.recheckStuckPayouts();

        assertEquals(WithdrawalRequest.COMPLETED, stored.getStatus());
        assertEquals("p-9", stored.getProviderUuid());
        verify(walletService).finalizeWithdrawal(5L, 20_600L, "PAY:r-9", 9L);
    }

    @Test
    void recheckNeverReleasesFundsWhenTheProviderHasNoRecord() {
        stored = sent(WithdrawalRequest.PROCESSING);
        when(withdrawals.findTop50ByStatusAndDispatchedAtBeforeOrderByDispatchedAtAsc(eq(WithdrawalRequest.PROCESSING), any()))
                .thenReturn(List.of(stored));
        when(marzPay.getTransaction(any(), eq("r-9"))).thenThrow(new MarzPayClient.MarzPayException(404, "TRANSACTION_NOT_FOUND"));

        service.recheckStuckPayouts();

        assertEquals(WithdrawalRequest.PROCESSING, stored.getStatus());
        assertTrue(stored.getFailureReason().contains("manual review"));
        verify(walletService, never()).releaseWithdrawal(anyLong(), anyLong(), any(), any());
    }

    // ---------- admin listing ----------

    @Test
    void adminList_withEmptyStatuses_queriesAllDesc() {
        WithdrawalRequest w = WithdrawalRequest.builder().id(1L).status(WithdrawalRequest.COMPLETED).build();
        when(withdrawals.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(w));

        assertEquals(1, service.adminList(Collections.emptyList()).size());
        verify(withdrawals).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void adminList_withStatuses_queriesStatusIn() {
        WithdrawalRequest w = WithdrawalRequest.builder().id(2L).status(WithdrawalRequest.REQUESTED).build();
        List<String> statuses = List.of(WithdrawalRequest.REQUESTED);
        when(withdrawals.findAllByStatusIn(statuses)).thenReturn(List.of(w));

        assertEquals(1, service.adminList(statuses).size());
        verify(withdrawals).findAllByStatusIn(statuses);
    }
}
