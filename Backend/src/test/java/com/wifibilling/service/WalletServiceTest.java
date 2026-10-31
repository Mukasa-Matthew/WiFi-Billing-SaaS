package com.wifibilling.service;

import com.wifibilling.model.Tenant;
import com.wifibilling.model.WalletAccount;
import com.wifibilling.model.WalletLedgerEntry;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.repository.WalletAccountRepository;
import com.wifibilling.repository.WalletLedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock private WalletAccountRepository wallets;
    @Mock private WalletLedgerEntryRepository ledger;
    @Mock private TenantRepository tenants;

    private WalletService service;
    private WalletAccount wallet;

    @BeforeEach
    void setUp() {
        service = new WalletService(wallets, ledger, tenants);
        Tenant tenant = Tenant.builder().id(1L).name("T").build();
        wallet = WalletAccount.builder().id(5L).tenant(tenant).availableBalance(10_000L).build();
        lenient().when(wallets.findByTenantIdForUpdate(1L)).thenReturn(Optional.of(wallet));
        lenient().when(wallets.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(ledger.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void collectionCreditIncreasesAvailable() {
        assertTrue(service.creditCollection(1L, 9_700, "COL:PAY-1", 50L, "test"));
        assertEquals(19_700L, wallet.getAvailableBalance());
        assertEquals(9_700L, wallet.getTotalCollected());
    }

    @Test
    void duplicateCollectionCreditIsIgnored() {
        when(ledger.existsByWalletIdAndTypeAndExternalRef(5L, WalletLedgerEntry.COLLECTION_CREDIT, "COL:PAY-1"))
                .thenReturn(true);
        assertFalse(service.creditCollection(1L, 9_700, "COL:PAY-1", 50L, "test"));
        assertEquals(10_000L, wallet.getAvailableBalance());
        verify(ledger, never()).save(any());
    }

    @Test
    void reserveMovesAvailableToReserved() {
        service.reserveForWithdrawal(1L, 8_000, "RES:W1", 7L);
        assertEquals(2_000L, wallet.getAvailableBalance());
        assertEquals(8_000L, wallet.getReservedBalance());
    }

    @Test
    void reserveRejectsInsufficientBalance() {
        var ex = assertThrows(ResponseStatusException.class,
                () -> service.reserveForWithdrawal(1L, 50_000, "RES:W2", 7L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(10_000L, wallet.getAvailableBalance());
    }

    @Test
    void finalizeWithdrawalDebitsReserved() {
        service.reserveForWithdrawal(1L, 8_000, "RES:W1", 7L);
        assertTrue(service.finalizeWithdrawal(1L, 8_000, "PAY:W1", 7L));
        assertEquals(0L, wallet.getReservedBalance());
        assertEquals(8_000L, wallet.getTotalWithdrawn());
        assertEquals(2_000L, wallet.getAvailableBalance());
    }

    @Test
    void failedPayoutReleasesReservation() {
        service.reserveForWithdrawal(1L, 8_000, "RES:W1", 7L);
        assertTrue(service.releaseWithdrawal(1L, 8_000, "REL:W1", 7L));
        assertEquals(10_000L, wallet.getAvailableBalance());
        assertEquals(0L, wallet.getReservedBalance());
    }

    @Test
    void debitAdjustmentCannotExceedBalance() {
        assertThrows(ResponseStatusException.class,
                () -> service.adjust(1L, -50_000, "ADJ:1", "test"));
        assertEquals(10_000L, wallet.getAvailableBalance());
    }

    @Test
    void externalWithdrawalDebitsAvailableAndCountsAsWithdrawn() {
        assertTrue(service.recordExternalWithdrawal(1L, 7_000, "EXT:1", "MarzPay dashboard"));
        assertEquals(3_000L, wallet.getAvailableBalance());
        assertEquals(7_000L, wallet.getTotalWithdrawn());
        verify(ledger).save(argThat(e -> WalletLedgerEntry.EXTERNAL_WITHDRAWAL.equals(e.getType())
                && e.getAmount() == 7_000L && e.getAvailableAfter() == 3_000L));
    }

    @Test
    void externalWithdrawalCannotExceedAvailable() {
        var ex = assertThrows(ResponseStatusException.class,
                () -> service.recordExternalWithdrawal(1L, 10_001, "EXT:2", null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(10_000L, wallet.getAvailableBalance());
        verify(ledger, never()).save(any());
    }

    @Test
    void feeAboveEstimateMovesDifferenceIntoReserveWithoutThrowing() {
        wallet.setAvailableBalance(47_180L);
        service.reserveForWithdrawal(1L, 46_500, "RES:W9", 9L);
        service.adjustReservation(1L, 880, "FEEADJ:W9", 9L);
        assertEquals(-200L, wallet.getAvailableBalance());
        assertEquals(47_380L, wallet.getReservedBalance());
        service.finalizeWithdrawal(1L, 47_380, "PAY:W9", 9L);
        assertEquals(0L, wallet.getReservedBalance());
        assertEquals(47_380L, wallet.getTotalWithdrawn());
    }

    @Test
    void feeBelowEstimateReleasesDifferenceWithoutStrandingReserve() {
        service.reserveForWithdrawal(1L, 5_500, "RES:W10", 10L);
        service.adjustReservation(1L, -350, "FEEADJ:W10", 10L);
        assertEquals(4_850L, wallet.getAvailableBalance());
        assertEquals(5_150L, wallet.getReservedBalance());
        service.finalizeWithdrawal(1L, 5_150, "PAY:W10", 10L);
        assertEquals(0L, wallet.getReservedBalance());
    }
}
