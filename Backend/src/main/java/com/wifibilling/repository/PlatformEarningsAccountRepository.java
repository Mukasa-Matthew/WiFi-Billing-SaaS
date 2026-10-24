package com.wifibilling.repository;

import com.wifibilling.model.PlatformEarningsAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface PlatformEarningsAccountRepository extends JpaRepository<PlatformEarningsAccount, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PlatformEarningsAccount p WHERE p.id = 1")
    Optional<PlatformEarningsAccount> findForUpdate();
}
