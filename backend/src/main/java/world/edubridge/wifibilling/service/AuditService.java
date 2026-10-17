package world.edubridge.wifibilling.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.edubridge.wifibilling.domain.AuditLog;
import world.edubridge.wifibilling.repository.AuditLogRepository;

import java.util.Map;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void record(Long tenantId, Long actorUserId, String action, String resourceType,
                       String resourceId, Map<String, Object> metadata, String userAgentOrSource) {
        AuditLog log = new AuditLog();
        log.setTenantId(tenantId);
        log.setUserId(actorUserId);
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setUserAgent(userAgentOrSource);
        auditLogRepository.save(log);
    }
}
