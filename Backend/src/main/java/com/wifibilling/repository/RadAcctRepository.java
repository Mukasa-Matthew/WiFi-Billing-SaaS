package com.wifibilling.repository;

import com.wifibilling.radius.RadAcct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RadAcctRepository extends JpaRepository<RadAcct, Long> {

    /** Find all active (open) accounting sessions for a username. */
    List<RadAcct> findAllByUsernameAndAcctStopTimeIsNull(String username);

    /** Find all active (open) accounting sessions across the platform. */
    List<RadAcct> findAllByAcctStopTimeIsNullOrderByAcctStartTimeDesc();

    /** Find active accounting sessions for a specific set of usernames (scoped to a tenant's vouchers). */
    List<RadAcct> findAllByUsernameInAndAcctStopTimeIsNullOrderByAcctStartTimeDesc(Collection<String> usernames);

    /** Find recent accounting sessions for a specific set of usernames. */
    List<RadAcct> findTop100ByUsernameInOrderByAcctStartTimeDesc(Collection<String> usernames);

    /** Find recent accounting sessions across the platform. */
    List<RadAcct> findTop100ByOrderByAcctStartTimeDesc();

    /** Find all accounting records for a set of usernames. */
    List<RadAcct> findAllByUsernameIn(Collection<String> usernames);

    /** Find the earliest start time recorded for a username to stamp first_login. */
    @Query("SELECT r FROM RadAcct r WHERE r.username = :username ORDER BY r.acctStartTime ASC LIMIT 1")
    Optional<RadAcct> findFirstSessionByUsername(@Param("username") String username);

    /** Distinct users online per period, tenant-scoped via voucher codes. */
    @Query(value = """
            SELECT date_trunc(:grain, COALESCE(acctstarttime, acctstoptime)) AS d, COUNT(DISTINCT username)
            FROM radacct
            WHERE username IN (SELECT code FROM vouchers WHERE tenant_id = :tenantId)
              AND COALESCE(acctstarttime, acctstoptime) >= :fromTs
              AND COALESCE(acctstarttime, acctstoptime) < :toTs
            GROUP BY 1
            """, nativeQuery = true)
    List<Object[]> usersPerBucket(@Param("tenantId") Long tenantId,
                                  @Param("grain") String grain,
                                  @Param("fromTs") java.time.OffsetDateTime fromTs,
                                  @Param("toTs") java.time.OffsetDateTime toTs);

    /** Top data consumers within a period (username, total octets). */
    @Query(value = """
            SELECT username, SUM(acctinputoctets + acctoutputoctets) AS total
            FROM radacct
            WHERE username IN (SELECT code FROM vouchers WHERE tenant_id = :tenantId)
              AND COALESCE(acctstarttime, acctstoptime) >= :fromTs
              AND COALESCE(acctstarttime, acctstoptime) < :toTs
            GROUP BY username ORDER BY total DESC LIMIT 5
            """, nativeQuery = true)
    List<Object[]> topDataUsers(@Param("tenantId") Long tenantId,
                                @Param("fromTs") java.time.OffsetDateTime fromTs,
                                @Param("toTs") java.time.OffsetDateTime toTs);

    /** Download (output) and upload (input) octets since a timestamp, tenant-scoped. */
    @Query(value = """
            SELECT COALESCE(SUM(acctoutputoctets), 0), COALESCE(SUM(acctinputoctets), 0)
            FROM radacct
            WHERE username IN (SELECT code FROM vouchers WHERE tenant_id = :tenantId)
              AND COALESCE(acctstarttime, acctstoptime) >= :fromTs
            """, nativeQuery = true)
    List<Object[]> dataSince(@Param("tenantId") Long tenantId,
                             @Param("fromTs") java.time.OffsetDateTime fromTs);
}
