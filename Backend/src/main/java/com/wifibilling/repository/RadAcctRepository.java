package com.wifibilling.repository;

import com.wifibilling.radius.RadAcct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
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

    /** Per-username aggregates for the user directory: first login, total octets, live device count. */
    @Query(value = """
            SELECT username, MIN(acctstarttime),
                   COALESCE(SUM(acctinputoctets + acctoutputoctets), 0),
                   COUNT(*) FILTER (WHERE acctstoptime IS NULL
                                    AND COALESCE(acctupdatetime, acctstarttime) >= :cut)
            FROM radacct
            WHERE username IN (SELECT code FROM vouchers WHERE tenant_id = :tenantId)
            GROUP BY username
            """, nativeQuery = true)
    List<Object[]> userStats(@Param("tenantId") Long tenantId,
                             @Param("cut") java.time.OffsetDateTime cut);

    /** Paged accounting for one NAS (router WG IP) with optional filters — NOC views. */
    @Query("""
            SELECT r FROM RadAcct r
            WHERE r.nasIpAddress = :nasIp
              AND (:username IS NULL OR LOWER(r.username) LIKE LOWER(CONCAT('%', :username, '%')))
              AND (:openOnly = false OR r.acctStopTime IS NULL)
              AND (:cause IS NULL OR r.acctTerminateCause = :cause)
              AND (:fromTs IS NULL OR COALESCE(r.acctStartTime, r.acctStopTime) >= :fromTs)
              AND (:toTs IS NULL OR COALESCE(r.acctStartTime, r.acctStopTime) < :toTs)
            ORDER BY r.acctStartTime DESC NULLS LAST
            """)
    org.springframework.data.domain.Page<RadAcct> findByNas(@Param("nasIp") String nasIp,
                                                            @Param("username") String username,
                                                            @Param("openOnly") boolean openOnly,
                                                            @Param("cause") String cause,
                                                            @Param("fromTs") OffsetDateTime fromTs,
                                                            @Param("toTs") OffsetDateTime toTs,
                                                            org.springframework.data.domain.Pageable pageable);

    /** Open (no Stop record) sessions for one NAS. */
    List<RadAcct> findAllByNasIpAddressAndAcctStopTimeIsNull(String nasIp);

    /** Open sessions for one NAS that are fresh (interim update within cutoff) vs stale. */
    @Query("""
            SELECT COUNT(r) FROM RadAcct r
            WHERE r.nasIpAddress = :nasIp AND r.acctStopTime IS NULL
              AND COALESCE(r.acctUpdateTime, r.acctStartTime) >= :cut
            """)
    long countFreshOpenByNas(@Param("nasIp") String nasIp, @Param("cut") OffsetDateTime cut);

    /** Last accounting write for a NAS — staleness detection. */
    @Query("SELECT MAX(COALESCE(r.acctUpdateTime, r.acctStartTime)) FROM RadAcct r WHERE r.nasIpAddress = :nasIp")
    OffsetDateTime lastAccountingAt(@Param("nasIp") String nasIp);

    /** Open-session counts grouped by NAS IP — one query for the whole fleet table. */
    @Query("SELECT r.nasIpAddress, COUNT(r) FROM RadAcct r WHERE r.acctStopTime IS NULL GROUP BY r.nasIpAddress")
    List<Object[]> openCountsByNas();

    /** Platform-wide totals for the NOC overview. */
    @Query("SELECT COUNT(r) FROM RadAcct r WHERE r.acctStopTime IS NULL AND COALESCE(r.acctUpdateTime, r.acctStartTime) >= :cut")
    long countFreshOpenSince(@Param("cut") OffsetDateTime cut);

    @Query("SELECT COUNT(r) FROM RadAcct r WHERE r.acctStopTime IS NULL")
    long countAllOpen();

    @Query(value = "SELECT COALESCE(SUM(acctinputoctets),0), COALESCE(SUM(acctoutputoctets),0) FROM radacct WHERE COALESCE(acctstarttime, acctstoptime) >= :fromTs", nativeQuery = true)
    List<Object[]> platformDataSince(@Param("fromTs") OffsetDateTime fromTs);

    /** Per-NAS data totals within a window (router accounting summary). */
    @Query(value = """
            SELECT COALESCE(SUM(acctinputoctets),0), COALESCE(SUM(acctoutputoctets),0), COUNT(*)
            FROM radacct
            WHERE nasipaddress = :nasIp
              AND COALESCE(acctstarttime, acctstoptime) >= :fromTs
              AND COALESCE(acctstarttime, acctstoptime) < :toTs
            """, nativeQuery = true)
    List<Object[]> nasDataBetween(@Param("nasIp") String nasIp,
                                  @Param("fromTs") OffsetDateTime fromTs,
                                  @Param("toTs") OffsetDateTime toTs);

    /** Daily usage buckets for a NAS — chart series. */
    @Query(value = """
            SELECT date_trunc('day', COALESCE(acctstarttime, acctstoptime)) AS d,
                   COALESCE(SUM(acctinputoctets),0), COALESCE(SUM(acctoutputoctets),0),
                   COUNT(DISTINCT username)
            FROM radacct
            WHERE nasipaddress = :nasIp
              AND COALESCE(acctstarttime, acctstoptime) >= :fromTs
            GROUP BY 1 ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> nasDailyUsage(@Param("nasIp") String nasIp,
                                 @Param("fromTs") OffsetDateTime fromTs);

    /** Latest session row per username (ip, mac, nas) for the user directory. */
    @Query(value = """
            SELECT DISTINCT ON (username) username, framedipaddress, callingstationid, nasipaddress, acctstarttime
            FROM radacct
            WHERE username IN (SELECT code FROM vouchers WHERE tenant_id = :tenantId)
            ORDER BY username, acctstarttime DESC
            """, nativeQuery = true)
    List<Object[]> latestSessions(@Param("tenantId") Long tenantId);
}
