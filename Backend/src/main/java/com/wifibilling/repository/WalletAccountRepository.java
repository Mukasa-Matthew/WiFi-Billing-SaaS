package com.wifibilling.repository;

import com.wifibilling.model.WalletAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface WalletAccountRepository extends JpaRepository<WalletAccount, Long> {
    Optional<WalletAccount> findByTenantId(Long tenantId);

    /** Pessimistic lock for balance mutations — prevents concurrent double-spend. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletAccount w WHERE w.tenant.id = :tenantId")
    Optional<WalletAccount> findByTenantIdForUpdate(Long tenantId);
}
