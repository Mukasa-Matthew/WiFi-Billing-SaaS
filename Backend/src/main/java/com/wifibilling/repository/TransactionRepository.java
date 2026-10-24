package com.wifibilling.repository;

import com.wifibilling.model.Transaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByReference(String reference);
    Optional<Transaction> findByExternalReference(String externalReference);
    Optional<Transaction> findByCheckoutRef(String checkoutRef);

    /** Serialized lookups for status transitions — concurrent webhooks/status
     *  rechecks must not both observe PENDING and double-fulfill. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from Transaction t where t.reference = :ref")
    Optional<Transaction> findByReferenceForUpdate(String ref);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from Transaction t where t.externalReference = :ref")
    Optional<Transaction> findByExternalReferenceForUpdate(String ref);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from Transaction t where t.checkoutRef = :ref")
    Optional<Transaction> findByCheckoutRefForUpdate(String ref);

    boolean existsByReference(String reference);

    List<Transaction> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);

    /** Initiation rate limiting — pending purchases per phone on a router. */
    long countByRouterIdAndPhoneAndCreatedAtAfter(Long routerId, String phone, OffsetDateTime after);

    /** Reconciliation sweep — stale pending transactions. */
    List<Transaction> findTop50ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            String status, OffsetDateTime before);

    Optional<Transaction> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from Transaction t where t.id = :id")
    Optional<Transaction> findByIdForUpdate(Long id);

    /**
     * Atomic dispatch claim: sets dispatch_at only if the transaction is still
     * PENDING and was never dispatched. Exactly one concurrent caller wins.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "update Transaction t set t.dispatchAt = :#{T(java.time.OffsetDateTime).now()} "
            + "where t.id = :id and t.status = 'PENDING' and t.dispatchAt is null")
    int claimDispatch(Long id);

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
