package com.wifibilling.repository;

import com.wifibilling.model.WinboxAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface WinboxAccessRepository extends JpaRepository<WinboxAccess, Integer> {
    Optional<WinboxAccess> findByRouterId(Long routerId);

    /*
     * Router agents and the gateway report status every few seconds. Writing just their
     * own columns in one statement — instead of saving the whole row — keeps them from
     * colliding with each other (or with config changes) on the row version. The
     * revision guard still rejects a report for a configuration that has moved on.
     */

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update WinboxAccess a set a.appliedRevision = :revision, a.routerResult = :result, a.routerCheckedAt = :now
             where a.port = :port and a.revision = :revision""")
    int recordRouterAck(@Param("port") Integer port, @Param("revision") long revision,
                        @Param("result") String result, @Param("now") OffsetDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update WinboxAccess a set a.gatewayRevision = :revision, a.gatewayResult = :result, a.gatewayCheckedAt = :now,
                   a.lastSuccessfulCheck = coalesce(:succeededAt, a.lastSuccessfulCheck)
             where a.port = :port and a.revision = :revision and a.enabled = true""")
    int recordGatewayCheck(@Param("port") Integer port, @Param("revision") long revision, @Param("result") String result,
                           @Param("now") OffsetDateTime now, @Param("succeededAt") OffsetDateTime succeededAt);
}
