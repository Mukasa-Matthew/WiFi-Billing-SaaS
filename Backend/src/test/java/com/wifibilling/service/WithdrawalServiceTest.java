package com.wifibilling.service;

import com.wifibilling.model.PlatformEarningsAccount;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.TenantGatewayConfig;
import com.wifibilling.model.WithdrawalRequest;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.repository.WithdrawalRequestRepository;
import com.wifibilling.service.payment.MarzPayClient;
import com.wifibilling.config.PlatformProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceTest {

    @Mock
    private WithdrawalRequestRepository withdrawals;
    @Mock
    private WalletService walletService;
    @Mock
    private GatewayConfigService gatewayConfig;
    @Mock
    private MarzPayClient marzPay;
    @Mock
    private PlatformProperties props;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private PlatformEarningsService platformEarnings;

    private WithdrawalService service;

    @BeforeEach
    void setUp() {
        service = new WithdrawalService(withdrawals, walletService, gatewayConfig, marzPay, props, tenantRepository, platformEarnings);
    }

    @Test
    void adminList_withEmptyStatuses_queriesAllDesc() {
        WithdrawalRequest w = WithdrawalRequest.builder().id(1L).status(WithdrawalRequest.COMPLETED).build();
        when(withdrawals.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(w));

        List<WithdrawalRequest> res = service.adminList(Collections.emptyList());
        assertEquals(1, res.size());
        verify(withdrawals).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void adminList_withStatuses_queriesStatusIn() {
        WithdrawalRequest w = WithdrawalRequest.builder().id(2L).status(WithdrawalRequest.REQUESTED).build();
        List<String> statuses = List.of(WithdrawalRequest.REQUESTED);
        when(withdrawals.findAllByStatusIn(statuses)).thenReturn(List.of(w));

        List<WithdrawalRequest> res = service.adminList(statuses);
        assertEquals(1, res.size());
        verify(withdrawals).findAllByStatusIn(statuses);
    }

    @Test
    void request_isBlockedWhileProviderBalanceDisagreesWithBooks() {
        PlatformEarningsAccount account = new PlatformEarningsAccount();
        account.setReconcileStatus(PlatformEarningsAccount.RECON_DISCREPANCY);
        when(platformEarnings.account()).thenReturn(account);
        when(withdrawals.countByTenantIdAndStatusIn(eq(5L), anyList())).thenReturn(0L);
        ReflectionTestUtils.setField(service, "minAmount", 1_000L);
        ReflectionTestUtils.setField(service, "maxAmount", 5_000_000L);
        ReflectionTestUtils.setField(service, "maxOpenPerTenant", 1L);

        var ex = assertThrows(ResponseStatusException.class, () -> service.request(5L, 20_000, "0772000000"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
        verifyNoInteractions(walletService, marzPay);
        verify(withdrawals, never()).save(any());
    }

    /** Replays 31 Oct: 46,000 from 47,180 with a provider fee above the reserve. The
     *  payout has already gone out, so the request must commit, not roll back. */
    @Test
    void feeAboveEstimateAfterPayoutNeverRollsBackTheRequest() throws Exception {
        ReflectionTestUtils.setField(service, "minAmount", 1_000L);
        ReflectionTestUtils.setField(service, "maxAmount", 5_000_000L);
        ReflectionTestUtils.setField(service, "maxOpenPerTenant", 1L);
        ReflectionTestUtils.setField(service, "feePercent", 3L);
        ReflectionTestUtils.setField(service, "requireApproval", false);
        ReflectionTestUtils.setField(service, "livePayoutsEnabled", true);
        when(platformEarnings.account()).thenReturn(new PlatformEarningsAccount());
        when(withdrawals.countByTenantIdAndStatusIn(eq(5L), anyList())).thenReturn(0L);
        Tenant tenant = Tenant.builder().id(5L).name("Calton").build();
        when(tenantRepository.findById(5L)).thenReturn(Optional.of(tenant));
        when(withdrawals.save(any())).thenAnswer(i -> {
            WithdrawalRequest w = i.getArgument(0);
            if (w.getId() == null) w.setId(9L);
            return w;
        });
        when(gatewayConfig.credentialsFor(any(), eq(TenantGatewayConfig.MODE_PLATFORM)))
                .thenReturn(new MarzPayClient.Credentials("k", "s"));
        when(props.getPublicBaseUrl()).thenReturn("https://api.example");
        when(marzPay.sendMoney(any(), eq(46_000L), any(), any(), any(), any(), any())).thenReturn(new ObjectMapper()
                .readTree("{\"data\":{\"withdrawal\":{\"charge\":{\"raw\":1500}}}}"));

        WithdrawalRequest w = service.request(5L, 46_000, "0755032436");

        assertEquals(WithdrawalRequest.PROCESSING, w.getStatus());
        assertEquals(1_500L, w.getFee());
        assertEquals(47_500L, w.getTotalDeduction());
        verify(walletService).reserveForWithdrawal(5L, 47_380L, "RES:" + w.getPayoutRef(), 9L);
        verify(walletService).adjustReservation(5L, 120L, "FEEADJ:" + w.getPayoutRef(), 9L);
        verify(walletService, never()).adjust(anyLong(), anyLong(), any(), any());
    }

    @Test
    void feeEstimateCoversThreePercentProviderCharge() {
        ReflectionTestUtils.setField(service, "feePercent", 3L);
        assertEquals(1_380L, service.estimateFee(46_000));
        assertEquals(660L, service.estimateFee(22_000));
        assertEquals(500L, service.estimateFee(2_000));
    }
}
