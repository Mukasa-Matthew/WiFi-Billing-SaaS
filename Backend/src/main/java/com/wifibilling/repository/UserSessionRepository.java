package com.wifibilling.repository;

import com.wifibilling.model.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {
    Optional<UserSession> findBySessionId(String sessionId);

    List<UserSession> findAllByTenantIdAndRevokedFalseOrderByLastActiveAtDesc(Long tenantId);

    @Modifying
    @Query("UPDATE UserSession s SET s.revoked = true WHERE s.tenant.id = :tenantId AND s.sessionId != :currentSessionId")
    void revokeAllExcept(@Param("tenantId") Long tenantId, @Param("currentSessionId") String currentSessionId);

    @Modifying
    @Query("UPDATE UserSession s SET s.revoked = true WHERE s.tenant.id = :tenantId")
    void revokeAllForTenant(@Param("tenantId") Long tenantId);
}
