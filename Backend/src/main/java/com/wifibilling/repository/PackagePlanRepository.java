package com.wifibilling.repository;

import com.wifibilling.model.PackagePlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PackagePlanRepository extends JpaRepository<PackagePlan, Long> {

    List<PackagePlan> findAllByTenantIdOrderByPriceAsc(Long tenantId);

    /** Tenant-scoped lookup so one tenant can never reference another tenant's package by ID. */
    Optional<PackagePlan> findByIdAndTenantId(Long id, Long tenantId);
}
