package com.wifibilling.service;

import com.wifibilling.model.*;
import com.wifibilling.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Tenant wallet ledger. All balance mutations happen under a pessimistic lock on
 * the wallet row, inside the caller's transaction, and each produces an immutable
 * ledger entry with running snapshots — the auditable source of truth.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletAccountRepository walletRepository;
    private final WalletLedgerEntryRepository ledgerRepository;
    private final TenantRepository tenantRepository;

    /** Lock-or-create the tenant's wallet (caller must be transactional). */
    @Transactional(propagation = Propagation.REQUIRED)
    public WalletAccount walletForUpdate(Long tenantId) {
        return walletRepository.findByTenantIdForUpdate(tenantId)
                .orElseGet(() -> {
                    Tenant t = tenantRepository.findById(tenantId)
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
                    try {
                        return walletRepository.saveAndFlush(WalletAccount.builder().tenant(t).build());
                    } catch (Exception race) {
                        // Unique constraint — another tx created it; lock the existing row.
                        return walletRepository.findByTenantIdForUpdate(tenantId).orElseThrow();
                    }
                });
    }

    @Transactional(readOnly = true)
    public WalletAccount wallet(Long tenantId) {
        return walletRepository.findByTenantId(tenantId)
                .orElseGet(() -> WalletAccount.builder()
                        .tenant(tenantRepository.getReferenceById(tenantId)).build());
    }

    /**
     * Credit a settled collection: net → available. Idempotent per externalRef —
     * duplicate delivery (webhook replay, reconciliation) is a no-op.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean creditCollection(Long tenantId, long netAmount, String externalRef,
                                    Long transactionId, String note) {
        WalletAccount w = walletForUpdate(tenantId);
        if (ledgerRepository.existsByWalletIdAndTypeAndExternalRef(w.getId(),
                WalletLedgerEntry.COLLECTION_CREDIT, externalRef)) {
            log.info("Duplicate collection credit ignored: {}", externalRef);
            return false;
        }
        w.setAvailableBalance(w.getAvailableBalance() + netAmount);
        w.setTotalCollected(w.getTotalCollected() + netAmount);
        record(w, WalletLedgerEntry.COLLECTION_CREDIT, netAmount, externalRef, transactionId, null, note);
        return true;
    }

    /** Record the provider fee as an auditable entry (informational — fee already
     *  deducted by MarzPay; we never debit it again from the wallet). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordFee(Long tenantId, long fee, String externalRef, Long transactionId) {
        if (fee <= 0) return;
        WalletAccount w = walletForUpdate(tenantId);
        if (ledgerRepository.existsByWalletIdAndTypeAndExternalRef(w.getId(),
                WalletLedgerEntry.FEE_DEBIT, externalRef)) return;
        w.setTotalFees(w.getTotalFees() + fee);
        record(w, WalletLedgerEntry.FEE_DEBIT, fee, externalRef, transactionId, null, "Gateway collection fee");
    }

    /** Reserve funds for a withdrawal. Moves amount from available to reserved. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void reserveForWithdrawal(Long tenantId, long totalDeduction, String externalRef, Long withdrawalId) {
        WalletAccount w = walletForUpdate(tenantId);
        if (w.getAvailableBalance() < totalDeduction) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Insufficient available balance for this withdrawal");
        }
        w.setAvailableBalance(w.getAvailableBalance() - totalDeduction);
        w.setReservedBalance(w.getReservedBalance() + totalDeduction);
        record(w, WalletLedgerEntry.WITHDRAWAL_RESERVE, totalDeduction, externalRef, null, withdrawalId,
                "Funds reserved for payout");
    }

    /**
     * Settle the gap between the estimated and the provider's actual payout fee by
     * moving it between available and reserved, so finalize later clears exactly
     * totalDeduction. Runs after the money has already left, so it never throws on
     * balance: a fee above available leaves the tenant owing it (negative available).
     * Idempotent per externalRef.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void adjustReservation(Long tenantId, long delta, String externalRef, Long withdrawalId) {
        if (delta == 0) return;
        WalletAccount w = walletForUpdate(tenantId);
        String type = delta > 0 ? WalletLedgerEntry.WITHDRAWAL_RESERVE : WalletLedgerEntry.WITHDRAWAL_RELEASE;
        if (ledgerRepository.existsByWalletIdAndTypeAndExternalRef(w.getId(), type, externalRef)) return;
        if (delta > 0 && w.getAvailableBalance() < delta) {
            log.warn("Payout fee for {} exceeds tenant {} available balance by {}", externalRef, tenantId,
                    delta - w.getAvailableBalance());
        }
        w.setAvailableBalance(w.getAvailableBalance() - delta);
        w.setReservedBalance(w.getReservedBalance() + delta);
        record(w, type, Math.abs(delta), externalRef, null, withdrawalId,
                delta > 0 ? "Payout fee above estimate" : "Payout fee below estimate — difference released");
    }

    /**
     * Settle a fee difference learned after the payout was already finalized (the
     * provider's completion callback beat the dispatch response). The reserve is
     * gone by then, so the difference moves available ↔ withdrawn directly.
     * Never throws on balance; idempotent per externalRef.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void settleCompletedPayoutFee(Long tenantId, long delta, String externalRef, Long withdrawalId) {
        if (delta == 0) return;
        WalletAccount w = walletForUpdate(tenantId);
        String type = delta > 0 ? WalletLedgerEntry.ADJUSTMENT_DEBIT : WalletLedgerEntry.ADJUSTMENT_CREDIT;
        if (ledgerRepository.existsByWalletIdAndTypeAndExternalRef(w.getId(), type, externalRef)) return;
        w.setAvailableBalance(w.getAvailableBalance() - delta);
        w.setTotalWithdrawn(w.getTotalWithdrawn() + delta);
        record(w, type, Math.abs(delta), externalRef, null, withdrawalId,
                delta > 0 ? "Payout fee above estimate" : "Payout fee below estimate — difference returned");
    }

    /** Finalize a completed payout: reserved → gone. Idempotent per externalRef. */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean finalizeWithdrawal(Long tenantId, long totalDeduction, String externalRef, Long withdrawalId) {
        WalletAccount w = walletForUpdate(tenantId);
        if (ledgerRepository.existsByWalletIdAndTypeAndExternalRef(w.getId(),
                WalletLedgerEntry.WITHDRAWAL_DEBIT, externalRef)) return false;
        w.setReservedBalance(Math.max(0, w.getReservedBalance() - totalDeduction));
        w.setTotalWithdrawn(w.getTotalWithdrawn() + totalDeduction);
        record(w, WalletLedgerEntry.WITHDRAWAL_DEBIT, totalDeduction, externalRef, null, withdrawalId,
                "Payout completed");
        return true;
    }

    /** Release a failed/cancelled payout's reservation back to available. Idempotent. */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean releaseWithdrawal(Long tenantId, long totalDeduction, String externalRef, Long withdrawalId) {
        WalletAccount w = walletForUpdate(tenantId);
        if (ledgerRepository.existsByWalletIdAndTypeAndExternalRef(w.getId(),
                WalletLedgerEntry.WITHDRAWAL_RELEASE, externalRef)) return false;
        w.setReservedBalance(Math.max(0, w.getReservedBalance() - totalDeduction));
        w.setAvailableBalance(w.getAvailableBalance() + totalDeduction);
        record(w, WalletLedgerEntry.WITHDRAWAL_RELEASE, totalDeduction, externalRef, null, withdrawalId,
                "Reserved funds released after payout failure");
        return true;
    }

    /** Authorized admin adjustment. Positive amount credits; negative debits. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void adjust(Long tenantId, long amount, String externalRef, String note) {
        WalletAccount w = walletForUpdate(tenantId);
        if (amount >= 0) {
            w.setAvailableBalance(w.getAvailableBalance() + amount);
            record(w, WalletLedgerEntry.ADJUSTMENT_CREDIT, amount, externalRef, null, null, note);
        } else {
            long d = -amount;
            if (w.getAvailableBalance() < d) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Adjustment exceeds available balance");
            }
            w.setAvailableBalance(w.getAvailableBalance() - d);
            record(w, WalletLedgerEntry.ADJUSTMENT_DEBIT, d, externalRef, null, null, note);
        }
    }

    /**
     * Record money already paid out to the tenant outside the app (e.g. straight
     * from the MarzPay dashboard): available → gone, counted as withdrawn.
     * Idempotent per externalRef.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean recordExternalWithdrawal(Long tenantId, long amount, String externalRef, String note) {
        if (amount <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A positive amount is required");
        WalletAccount w = walletForUpdate(tenantId);
        if (ledgerRepository.existsByWalletIdAndTypeAndExternalRef(w.getId(),
                WalletLedgerEntry.EXTERNAL_WITHDRAWAL, externalRef)) return false;
        if (w.getAvailableBalance() < amount) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Amount exceeds the tenant's available balance of UGX " + w.getAvailableBalance());
        }
        w.setAvailableBalance(w.getAvailableBalance() - amount);
        w.setTotalWithdrawn(w.getTotalWithdrawn() + amount);
        record(w, WalletLedgerEntry.EXTERNAL_WITHDRAWAL, amount, externalRef, null, null, note);
        return true;
    }

    @Transactional(readOnly = true)
    public List<WalletLedgerEntry> recentActivity(Long tenantId) {
        return walletRepository.findByTenantId(tenantId)
                .map(w -> ledgerRepository.findTop200ByWalletIdOrderByCreatedAtDesc(w.getId()))
                .orElse(List.of());
    }

    private void record(WalletAccount w, String type, long amount, String externalRef,
                        Long transactionId, Long withdrawalId, String note) {
        walletRepository.save(w);
        ledgerRepository.save(WalletLedgerEntry.builder()
                .wallet(w).type(type).amount(amount)
                .availableAfter(w.getAvailableBalance())
                .pendingAfter(w.getPendingBalance())
                .reservedAfter(w.getReservedBalance())
                .externalRef(externalRef)
                .transactionId(transactionId)
                .withdrawalId(withdrawalId)
                .note(note)
                .build());
    }
}
