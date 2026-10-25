package com.wifibilling.repository;

import com.wifibilling.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findTop50ByTenantIdOrderByCreatedAtDesc(Long tenantId);
    List<AuditLog> findTop100ByOrderByCreatedAtDesc();
}
