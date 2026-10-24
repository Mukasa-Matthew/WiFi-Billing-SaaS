package com.wifibilling.service;

import com.wifibilling.model.Tenant;
import com.wifibilling.model.Voucher;
import com.wifibilling.radius.RadAcct;
import com.wifibilling.repository.RadAcctRepository;
import com.wifibilling.repository.VoucherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private RadAcctRepository radAcctRepository;

    @Mock
    private VoucherRepository voucherRepository;

    @Mock
    private VoucherExpirationWorker expirationWorker;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(radAcctRepository, voucherRepository, expirationWorker);
    }

    @Test
    void activeSessions_QueriesOnlyAuthenticatedTenantVoucherCodes() {
        RadAcct session = RadAcct.builder().radAcctId(7L).username("TENANT11").build();
        when(voucherRepository.codesByTenant(11L)).thenReturn(java.util.Set.of("TENANT11"));
        when(radAcctRepository.findAllByUsernameInAndAcctStopTimeIsNullOrderByAcctStartTimeDesc(
                argThat(codes -> codes.size() == 1 && codes.contains("TENANT11"))))
                .thenReturn(List.of(session));

        var result = sessionService.getActiveSessions(11L, "ROLE_ADMIN");

        assertEquals(1, result.size());
        assertEquals("TENANT11", result.getFirst().username());
        verify(radAcctRepository, never()).findAllByAcctStopTimeIsNullOrderByAcctStartTimeDesc();
    }

    @Test
    void disconnect_RejectsVoucherOwnedByAnotherTenant() {
        Tenant otherTenant = Tenant.builder().id(22L).build();
        Voucher voucher = Voucher.builder().code("OTHER22").tenant(otherTenant).build();
        when(voucherRepository.findByCode("OTHER22")).thenReturn(Optional.of(voucher));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> sessionService.disconnectSession(11L, "ROLE_ADMIN", "OTHER22"));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(expirationWorker);
    }
}
