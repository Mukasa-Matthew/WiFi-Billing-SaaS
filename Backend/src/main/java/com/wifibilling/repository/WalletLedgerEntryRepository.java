package com.wifibilling.repository;

import com.wifibilling.model.WalletLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface WalletLedgerEntryRepository extends JpaRepository<WalletLedgerEntry, Long> {
    List<WalletLedgerEntry> findTop200ByWalletIdOrderByCreatedAtDesc(Long walletId);

    /** Entries created in [from, to), a page at a time. */
    org.springframework.data.domain.Page<WalletLedgerEntry> findByWalletIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Long walletId, java.time.OffsetDateTime from, java.time.OffsetDateTime to,
            org.springframework.data.domain.Pageable pageable);

    /** The last entry before an instant: its availableAfter is the opening balance at that instant. */
    Optional<WalletLedgerEntry> findFirstByWalletIdAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
            Long walletId, java.time.OffsetDateTime before);

    /** availableAfter of each entry in [from, to), oldest first — enough to total money in and out. */
    @org.springframework.data.jpa.repository.Query("""
            select e.availableAfter from WalletLedgerEntry e
            where e.wallet.id = :walletId and e.createdAt >= :from and e.createdAt < :to
            order by e.createdAt asc, e.id asc""")
    List<Long> availableAfterInRange(Long walletId, java.time.OffsetDateTime from, java.time.OffsetDateTime to);
    boolean existsByWalletIdAndTypeAndExternalRef(Long walletId, String type, String externalRef);
    Optional<WalletLedgerEntry> findByWalletIdAndTypeAndExternalRef(Long walletId, String type, String externalRef);
}
