package com.wifibilling.repository;

import com.wifibilling.model.PlatformLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PlatformLedgerEntryRepository extends JpaRepository<PlatformLedgerEntry, Long> {
    List<PlatformLedgerEntry> findTop200ByOrderByCreatedAtDesc();
    boolean existsByTypeAndExternalRef(String type, String externalRef);
}
