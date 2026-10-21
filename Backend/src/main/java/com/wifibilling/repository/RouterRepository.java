package com.wifibilling.repository;

import com.wifibilling.model.Router;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RouterRepository extends JpaRepository<Router, Long> {

    Optional<Router> findByToken(String token);

    Optional<Router> findByWgIp(String wgIp);

    Optional<Router> findByWgPublicKey(String wgPublicKey);

    boolean existsByWgIp(String wgIp);

    List<Router> findAllByTenantId(Long tenantId);

    Optional<Router> findByIdAndTenantId(Long id, Long tenantId);

    /** Routers that have completed the WireGuard key handshake (used to re-sync hub peers on startup). */
    List<Router> findAllByWgPublicKeyIsNotNull();

    /** All WireGuard IPs currently allocated, used for next-free-address allocation. */
    @Query("select r.wgIp from Router r where r.wgIp is not null and r.wgIp <> ''")
    List<String> findAllAllocatedWgIps();

    /** Bulk-flags routers whose telemetry has gone silent. */
    @Modifying
    @Query("update Router r set r.status = 'offline' where r.status = 'online' and r.lastSeen < :cutoff")
    int markOfflineSeenBefore(@Param("cutoff") OffsetDateTime cutoff);
}
