package com.wifibilling.repository;

import com.wifibilling.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
