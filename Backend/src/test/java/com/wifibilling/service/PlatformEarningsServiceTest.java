package com.wifibilling.service;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.model.*;
import com.wifibilling.repository.*;
import com.wifibilling.service.payment.MarzPayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlatformEarningsServiceTest {

    @Mock private PlatformEarningsAccountRepository accounts;
    @Mock private PlatformLedgerEntryRepository ledger;
    @Mock private PlatformPayoutRepository payouts;
    @Mock private ReconciliationLogRepository reconLogs;
    @Mock private WalletAccountRepository tenantWallets;
    @Mock private MarzPayClient marzPay;
    @Mock private GatewayConfigService gatewayConfig;
    @Mock private PlatformProperties props;

    private PlatformEarningsService service;
    private PlatformEarningsAccount account;

    @BeforeEach
    void setUp() {
        service = new PlatformEarningsService(accounts, ledger, payouts, reconLogs,
                tenantWallets, marzPay, gatewayConfig, props);
        account = PlatformEarningsAccount.builder()
                .settledEarnings(80_000L).build();
        lenient().when(accounts.findForUpdate()).thenReturn(Optional.of(account));
        lenient().when(accounts.findById(PlatformEarningsAccount.ID)).thenReturn(Optional.of(account));
        lenient().when(accounts.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(accounts.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(ledger.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(reconLogs.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(payouts.save(any())).thenAnswer(i -> {
            PlatformPayout p = i.getArgument(0);
            if (p.getId() == null) p.setId(7L);
            return p;
        });
        lenient().when(tenantWallets.findAll()).thenReturn(List.of());
        // Payouts enabled by default in tests; disable per-test as needed.
        ReflectionTestUtils.setField(service, "payoutsEnabled", true);
        ReflectionTestUtils.setField(service, "tolerance", 1_000L);
        ReflectionTestUtils.setField(service, "safetyBuffer", 5_000L);
        ReflectionTestUtils.setField(service, "maxStalenessMinutes", 10L);
        ReflectionTestUtils.setField(service, "maxPayout", 5_000_000L);
    }

    private SubscriptionPayment subPayment(long amount) {
        SubscriptionPayment p = new SubscriptionPayment();
        p.setId(42L);
        p.setAmount(amount);
        return p;
    }

    // ---------- earnings credits ----------

    @Test
    void marzPayChannelCreditsSettledEarnings() {
        assertTrue(service.creditSubscriptionPayment(subPayment(25_000L), "MARZPAY"));
        assertEquals(105_000L, account.getSettledEarnings());
        assertEquals(0L, account.getExternalEarnings());
    }

    @Test
    void externalChannelDoesNotCreateMarzPayFunds() {
        assertTrue(service.creditSubscriptionPayment(subPayment(25_000L), "EXTERNAL"));
        assertEquals(80_000L, account.getSettledEarnings());       // unchanged
        assertEquals(25_000L, account.getExternalEarnings());
        assertEquals(25_000L, account.getTotalEarned());           // still tracked
    }

    @Test
    void duplicateCreditIsIgnored() {
        when(ledger.existsByTypeAndExternalRef(PlatformLedgerEntry.EARNING_MARZPAY, "SUBPAY:42"))
                .thenReturn(true);
        assertFalse(service.creditSubscriptionPayment(subPayment(25_000L), "MARZPAY"));
        assertEquals(80_000L, account.getSettledEarnings());
    }

    // ---------- reconciliation ----------

    @Test
    void reconcileCleanWhenBalancesMatch() throws Exception {
        WalletAccount tenantWallet = WalletAccount.builder()
                .availableBalance(50_000L).reservedBalance(10_000L).build();
        when(tenantWallets.findAll()).thenReturn(List.of(tenantWallet));
        when(gatewayConfig.platformConfigured()).thenReturn(true);
        var creds = new MarzPayClient.Credentials("k", "s");
        when(gatewayConfig.credentialsFor(null, TenantGatewayConfig.MODE_PLATFORM)).thenReturn(creds);
        // provider = 80k earnings + 60k tenant liability = 140k
        String json = "{\"status\":\"success\",\"data\":{\"account\":{\"available_balance\":{\"raw\":140000}}}}";
        when(marzPay.getBalance(creds, "UG")).thenReturn(new ObjectMapper().readTree(json));

        var log = service.reconcile();
        assertEquals(ReconciliationLog.CLEAN, log.getStatus());
        assertEquals(140_000L, log.getProviderBalance());
        assertEquals(PlatformEarningsAccount.RECON_CLEAN, account.getReconcileStatus());
        assertEquals(140_000L, account.getLastProviderBalance());
    }

    @Test
    void externalWithdrawalDetectedAsProviderShortfall() throws Exception {
        when(gatewayConfig.platformConfigured()).thenReturn(true);
        var creds = new MarzPayClient.Credentials("k", "s");
        when(gatewayConfig.credentialsFor(null, TenantGatewayConfig.MODE_PLATFORM)).thenReturn(creds);
        // provider 50k but expected 80k → 30k vanished externally
        String json = "{\"status\":\"success\",\"data\":{\"account\":{\"available_balance\":{\"raw\":50000}}}}";
        when(marzPay.getBalance(creds, "UG")).thenReturn(new ObjectMapper().readTree(json));

        var log = service.reconcile();
        assertEquals(ReconciliationLog.EXTERNAL_DEBIT, log.getStatus());
        assertEquals(-30_000L, log.getDifference());
        assertEquals(PlatformEarningsAccount.RECON_DISCREPANCY, account.getReconcileStatus());
        assertTrue(account.getReconcileNote().contains("below expected"));
    }

    // ---------- safe withdrawable ----------

    @Test
    void safeWithdrawableProtectsTenantLiabilities() {
        WalletAccount tenantWallet = WalletAccount.builder()
                .availableBalance(60_000L).reservedBalance(0L).build();
        when(tenantWallets.findAll()).thenReturn(List.of(tenantWallet));
        account.setSettledEarnings(100_000L);
        account.setLastProviderBalance(120_000L); // 120k actual; 60k is tenant's
        // headroom = 120k - 60k - 0 - 5k buffer = 55k; settled = 100k → safe = 55k
        assertEquals(55_000L, service.safeWithdrawable());
    }

    @Test
    void safeWithdrawableZeroWithoutProviderBalance() {
        account.setLastProviderBalance(null);
        assertEquals(0L, service.safeWithdrawable());
    }

    // ---------- payouts ----------

    @Test
    void payoutBlockedWhenProviderBalanceMissing() {
        account.setLastProviderBalance(null);
        account.setReconcileStatus(PlatformEarningsAccount.RECON_CLEAN);
        var ex = assertThrows(ResponseStatusException.class,
                () -> service.requestPayout(10_000, "0771234567", 1L));
        // reconcile() with no creds → UNVERIFIED → balance unavailable
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
    }

    @Test
    void payoutBlockedOnDiscrepancy() {
        account.setLastProviderBalance(80_000L);
        account.setLastSyncedAt(OffsetDateTime.now());
        account.setReconcileStatus(PlatformEarningsAccount.RECON_DISCREPANCY);
        var ex = assertThrows(ResponseStatusException.class,
                () -> service.requestPayout(10_000, "0771234567", 1L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void payoutAboveSafeAmountRejected() {
        account.setSettledEarnings(50_000L);
        account.setLastProviderBalance(55_000L);
        account.setLastSyncedAt(OffsetDateTime.now());
        account.setReconcileStatus(PlatformEarningsAccount.RECON_CLEAN);
        // safe = min(50k, 55k - 0 - 0 - 5k) = 50k; ask 50k + fee → exceeds
        var ex = assertThrows(ResponseStatusException.class,
                () -> service.requestPayout(50_000, "0771234567", 1L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void successfulPayoutReservesThenCompletes() {
        account.setSettledEarnings(80_000L);
        account.setLastProviderBalance(85_000L);
        account.setLastSyncedAt(OffsetDateTime.now());
        account.setReconcileStatus(PlatformEarningsAccount.RECON_CLEAN);
        when(gatewayConfig.credentialsFor(null, TenantGatewayConfig.MODE_PLATFORM))
                .thenReturn(new MarzPayClient.Credentials("k", "s"));
        when(props.getPublicBaseUrl()).thenReturn("https://x.test");
        try {
            when(marzPay.sendMoney(any(), eq(20_000L), eq("+256771234567"), any(), eq("UG"), any(), any()))
                    .thenReturn(new ObjectMapper().readTree(
                            "{\"status\":\"success\",\"data\":{\"transaction\":{\"uuid\":\"mzu\",\"status\":\"pending\"},\"withdrawal\":{\"charge\":{\"raw\":500}}}}"));
        } catch (Exception e) { fail(e); }

        var p = service.requestPayout(20_000, "0771234567", 1L);
        assertEquals(PlatformPayout.PROCESSING, p.getStatus());
        // fee estimate 500 already matches → total 20500 reserved
        assertEquals(20_500L, account.getReservedEarnings());
        assertEquals(59_500L, account.getSettledEarnings());

        // Provider completes it
        when(payouts.findByPayoutRef(p.getPayoutRef())).thenReturn(Optional.of(p));
        try {
            var payload = new ObjectMapper().readTree(
                    "{\"event_type\":\"disbursement.completed\",\"transaction\":{\"provider_reference\":\"" + p.getPayoutRef() + "\",\"status\":\"completed\",\"uuid\":\"mzu\"}}");
            assertTrue(service.processPayoutCallback(payload));
        } catch (Exception e) { fail(e); }
        assertEquals(PlatformPayout.COMPLETED, p.getStatus());
        assertEquals(0L, account.getReservedEarnings());
        assertEquals(20_500L, account.getTotalWithdrawn());
    }

    @Test
    void failedPayoutReleasesReservation() {
        account.setSettledEarnings(60_000L);
        account.setReservedEarnings(20_500L);
        PlatformPayout p = PlatformPayout.builder()
                .id(7L).amount(20_000L).fee(500L).totalDeduction(20_500L)
                .phone("+256771234567").payoutRef("ref-1").status(PlatformPayout.PROCESSING).build();
        when(payouts.findByPayoutRef("ref-1")).thenReturn(Optional.of(p));
        try {
            var payload = new ObjectMapper().readTree(
                    "{\"event_type\":\"disbursement.failed\",\"transaction\":{\"provider_reference\":\"ref-1\",\"status\":\"failed\"}}");
            assertTrue(service.processPayoutCallback(payload));
        } catch (Exception e) { fail(e); }
        assertEquals(PlatformPayout.FAILED, p.getStatus());
        assertEquals(80_500L, account.getSettledEarnings()); // 60k + 20.5k released
        assertEquals(0L, account.getReservedEarnings());
    }

    @Test
    void callbackForUnknownRefReturnsFalse() throws Exception {
        when(payouts.findByPayoutRef("nope")).thenReturn(Optional.empty());
        var payload = new ObjectMapper().readTree(
                "{\"transaction\":{\"provider_reference\":\"nope\",\"status\":\"completed\"}}");
        assertFalse(service.processPayoutCallback(payload));
    }
}
