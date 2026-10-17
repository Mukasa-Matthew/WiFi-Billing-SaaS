package world.edubridge.wifibilling.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import world.edubridge.wifibilling.domain.AuditLog;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findTop50ByTenantIdOrderByCreatedAtDesc(Long tenantId);
    List<AuditLog> findTop50ByOrderByCreatedAtDesc();
}
