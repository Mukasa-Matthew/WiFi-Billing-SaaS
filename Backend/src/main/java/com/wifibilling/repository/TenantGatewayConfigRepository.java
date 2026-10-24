package com.wifibilling.repository;

import com.wifibilling.model.TenantGatewayConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TenantGatewayConfigRepository extends JpaRepository<TenantGatewayConfig, Long> {
    Optional<TenantGatewayConfig> findByTenantId(Long tenantId);
}
