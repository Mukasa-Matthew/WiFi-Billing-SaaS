package com.wifibilling.repository;

import com.wifibilling.model.TenantPortalAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface TenantPortalAssetRepository extends JpaRepository<TenantPortalAsset, Long> {
    Optional<TenantPortalAsset> findByTenantIdAndKind(Long tenantId, String kind);

    @Transactional
    void deleteByTenantIdAndKind(Long tenantId, String kind);
}
