package com.wifibilling.service;

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

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private WithdrawalService service;

    @BeforeEach
    void setUp() {
        service = new WithdrawalService(withdrawals, walletService, gatewayConfig, marzPay, props, tenantRepository);
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
}
