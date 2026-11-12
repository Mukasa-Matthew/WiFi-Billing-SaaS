package com.wifibilling.repository;

import com.wifibilling.model.PlatformLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PlatformLedgerEntryRepository extends JpaRepository<PlatformLedgerEntry, Long> {
    List<PlatformLedgerEntry> findTop200ByOrderByCreatedAtDesc();
    boolean existsByTypeAndExternalRef(String type, String externalRef);

    @org.springframework.data.jpa.repository.Query("select e.type, coalesce(sum(e.amount), 0) from PlatformLedgerEntry e group by e.type")
    java.util.List<Object[]> sumByType();
}
