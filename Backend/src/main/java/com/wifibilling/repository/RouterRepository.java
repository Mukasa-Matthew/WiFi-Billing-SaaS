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

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Router r where r.id = :id")
    Optional<Router> lockById(@Param("id") Long id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Router r where r.id = :id and r.tenant.id = :tenantId")
    Optional<Router> lockByIdAndTenantId(@Param("id") Long id, @Param("tenantId") Long tenantId);

    Optional<Router> findByWgIp(String wgIp);

    Optional<Router> findByWgPublicKey(String wgPublicKey);

    boolean existsByWgIp(String wgIp);

    List<Router> findAllByTenantId(Long tenantId);

    @Query("select r from Router r where r.tenant.id = :tenantId and (r.status is null or r.status <> :excludedStatus)")
    List<Router> findAllByTenantIdAndStatusNot(@Param("tenantId") Long tenantId, @Param("excludedStatus") String excludedStatus);

    Optional<Router> findByIdAndTenantId(Long id, Long tenantId);

    /** Routers that have completed the WireGuard key handshake (used to re-sync hub peers on startup). */
    List<Router> findAllByWgPublicKeyIsNotNull();

    /** All WireGuard IPs currently allocated, used for next-free-address allocation. */
    @Query("select r.wgIp from Router r where r.wgIp is not null and r.wgIp <> ''")
    List<String> findAllAllocatedWgIps();

    /** Find routers that are about to transition to offline to notify tenants. */
    @Query("select r from Router r join fetch r.tenant where r.status in ('online', 'degraded') and r.lastSeen < :cutoff")
    List<Router> findStaleRouters(@Param("cutoff") OffsetDateTime cutoff);

    /** Bulk-flags routers whose telemetry has gone silent. */
    @Modifying
    @Query("update Router r set r.status = 'offline', r.offlineNotifiedAt = :now where r.status in ('online', 'degraded') and r.lastSeen < :cutoff")
    int markOfflineSeenBefore(@Param("cutoff") OffsetDateTime cutoff, @Param("now") OffsetDateTime now);
}
