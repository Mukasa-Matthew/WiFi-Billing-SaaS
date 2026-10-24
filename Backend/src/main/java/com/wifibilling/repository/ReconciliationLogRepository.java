package com.wifibilling.repository;

import com.wifibilling.model.ReconciliationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReconciliationLogRepository extends JpaRepository<ReconciliationLog, Long> {
    List<ReconciliationLog> findTop20ByOrderByCreatedAtDesc();
}
