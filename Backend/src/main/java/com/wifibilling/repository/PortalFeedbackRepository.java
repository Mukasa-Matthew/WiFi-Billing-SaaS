package com.wifibilling.repository;

import com.wifibilling.model.PortalFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PortalFeedbackRepository extends JpaRepository<PortalFeedback, Long> {
    List<PortalFeedback> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);
    long countByTenantId(Long tenantId);
}
