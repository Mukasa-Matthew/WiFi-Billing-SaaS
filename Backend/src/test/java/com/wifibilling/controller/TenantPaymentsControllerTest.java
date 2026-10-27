package com.wifibilling.controller;

import com.wifibilling.model.Transaction;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.security.TenantPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantPaymentsControllerTest {

    @Mock private TransactionRepository transactions;
    @InjectMocks private TenantPaymentsController controller;
    private final TenantPrincipal owner = new TenantPrincipal(7L, "owner@example.test", "Owner", "ROLE_ADMIN", false);

    @Test
    void withoutLimitKeepsTheOriginalNewest1000Query() {
        when(transactions.findTop1000ByTenantIdOrderByCreatedAtDesc(7L)).thenReturn(List.of());
        controller.list(owner, null);
        verify(transactions).findTop1000ByTenantIdOrderByCreatedAtDesc(7L);
        verify(transactions, never()).findByTenantIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void limitReadsOnlyThatManyRowsForTheCallersTenant() {
        when(transactions.findByTenantIdOrderByCreatedAtDesc(eq(7L), any())).thenReturn(List.of());
        controller.list(owner, 6);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(transactions).findByTenantIdOrderByCreatedAtDesc(eq(7L), page.capture());
        assertEquals(0, page.getValue().getPageNumber());
        assertEquals(6, page.getValue().getPageSize());
    }

    @Test
    void limitIsClampedToSaneBounds() {
        when(transactions.findByTenantIdOrderByCreatedAtDesc(eq(7L), any())).thenReturn(List.of());
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        controller.list(owner, 50_000);
        controller.list(owner, 0);
        verify(transactions, times(2)).findByTenantIdOrderByCreatedAtDesc(eq(7L), page.capture());
        assertEquals(1000, page.getAllValues().get(0).getPageSize());
        assertEquals(1, page.getAllValues().get(1).getPageSize());
    }

    @Test
    void walkInRowWithoutPackageOrRouterDoesNotFail() {
        Transaction t = Transaction.builder().status("SUCCESSFUL").amount(BigDecimal.TEN).build();
        when(transactions.findByTenantIdOrderByCreatedAtDesc(eq(7L), any())).thenReturn(List.of(t));
        TenantPaymentsController.Payment p = controller.list(owner, 6).get(0);
        assertNull(p.packageName());
        assertNull(p.router());
    }
}
