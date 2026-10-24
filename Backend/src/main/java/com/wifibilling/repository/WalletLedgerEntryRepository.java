package com.wifibilling.repository;

import com.wifibilling.model.WalletLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface WalletLedgerEntryRepository extends JpaRepository<WalletLedgerEntry, Long> {
    List<WalletLedgerEntry> findTop200ByWalletIdOrderByCreatedAtDesc(Long walletId);
    boolean existsByWalletIdAndTypeAndExternalRef(Long walletId, String type, String externalRef);
    Optional<WalletLedgerEntry> findByWalletIdAndTypeAndExternalRef(Long walletId, String type, String externalRef);
}
