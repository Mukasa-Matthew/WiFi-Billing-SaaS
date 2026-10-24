package com.wifibilling.repository;

import com.wifibilling.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByReference(String reference);
    Optional<Transaction> findByExternalReference(String externalReference);

    boolean existsByReference(String reference);

    List<Transaction> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<Transaction> findTop1000ByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<Transaction> findAllByTenantIdAndStatusOrderByCreatedAtDesc(Long tenantId, String status);

    Optional<Transaction> findFirstByTenantIdAndPhoneAndStatusOrderByCreatedAtDesc(
            Long tenantId, String phone, String status);

    boolean existsByRouterId(Long routerId);

    /** Revenue + count bucketed per period ('day' or 'month') for the dashboard charts. */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT date_trunc(:grain, created_at) AS d, COALESCE(SUM(amount), 0), COUNT(*)
            FROM transactions
            WHERE tenant_id = :tenantId AND status = 'SUCCESSFUL'
              AND created_at >= :fromTs AND created_at < :toTs
            GROUP BY 1
            """, nativeQuery = true)
    List<Object[]> revenueBuckets(@org.springframework.data.repository.query.Param("tenantId") Long tenantId,
                                  @org.springframework.data.repository.query.Param("grain") String grain,
                                  @org.springframework.data.repository.query.Param("fromTs") java.time.OffsetDateTime fromTs,
                                  @org.springframework.data.repository.query.Param("toTs") java.time.OffsetDateTime toTs);

    /** Revenue totals for "today" and "this month" in one scan-free query. */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT COALESCE(SUM(amount) FILTER (WHERE created_at >= :day), 0),
                   COALESCE(SUM(amount) FILTER (WHERE created_at >= :month), 0)
            FROM transactions WHERE tenant_id = :tenantId AND status = 'SUCCESSFUL'
            """, nativeQuery = true)
    List<Object[]> revenueTotals(@org.springframework.data.repository.query.Param("tenantId") Long tenantId,
                                 @org.springframework.data.repository.query.Param("day") java.time.OffsetDateTime day,
                                 @org.springframework.data.repository.query.Param("month") java.time.OffsetDateTime month);

    /** Per-router revenue: today, this month, all-time, plus sale count. */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT router_id,
                   COALESCE(SUM(amount) FILTER (WHERE created_at >= :day), 0),
                   COALESCE(SUM(amount) FILTER (WHERE created_at >= :month), 0),
                   COALESCE(SUM(amount), 0), COUNT(*)
            FROM transactions WHERE tenant_id = :tenantId AND status = 'SUCCESSFUL'
            GROUP BY router_id
            """, nativeQuery = true)
    List<Object[]> revenueByRouter(@org.springframework.data.repository.query.Param("tenantId") Long tenantId,
                                   @org.springframework.data.repository.query.Param("day") java.time.OffsetDateTime day,
                                   @org.springframework.data.repository.query.Param("month") java.time.OffsetDateTime month);

    /** Per-package performance: monthly revenue, monthly sales count, total sold. */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT package_id,
                   COALESCE(SUM(amount) FILTER (WHERE created_at >= :month), 0),
                   COUNT(*) FILTER (WHERE created_at >= :month),
                   COUNT(*)
            FROM transactions WHERE tenant_id = :tenantId AND status = 'SUCCESSFUL'
            GROUP BY package_id
            """, nativeQuery = true)
    List<Object[]> salesByPackage(@org.springframework.data.repository.query.Param("tenantId") Long tenantId,
                                  @org.springframework.data.repository.query.Param("month") java.time.OffsetDateTime month);
}
