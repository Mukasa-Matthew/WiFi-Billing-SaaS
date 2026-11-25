package com.wifibilling.repository;

import com.wifibilling.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from Tenant t where t.id = :id")
    Optional<Tenant> lockForStaffAccess(@org.springframework.data.repository.query.Param("id") Long id);

    Optional<Tenant> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
