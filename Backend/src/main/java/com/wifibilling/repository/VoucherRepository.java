package com.wifibilling.repository;

import com.wifibilling.model.Voucher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface VoucherRepository extends JpaRepository<Voucher, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select v from Voucher v where v.code = :code and v.tenant.id = :tenantId")
    Optional<Voucher> lockForSale(@org.springframework.data.repository.query.Param("code") String code,
            @org.springframework.data.repository.query.Param("tenantId") Long tenantId);

    Optional<Voucher> findByCode(String code);

    boolean existsByCode(String code);

    List<Voucher> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<Voucher> findAllByTenantIdAndStatusOrderByCreatedAtDesc(Long tenantId, String status);

    List<Voucher> findAllByStatus(String status);

    long countByTenantId(Long tenantId);

    long countByTenantIdAndStatus(Long tenantId, String status);

    /** Vouchers still marked with the given status whose validity window has passed. */
    List<Voucher> findAllByStatusAndExpiresAtBefore(String status, OffsetDateTime cutoff);

    /** Just the voucher codes for a tenant — cheap scoping query for radacct filters. */
    @org.springframework.data.jpa.repository.Query("SELECT v.code FROM Voucher v WHERE v.tenant.id = :tenantId")
    java.util.Set<String> codesByTenant(@org.springframework.data.repository.query.Param("tenantId") Long tenantId);

    /** Look up a small set of vouchers by code (dashboard joins, active sessions). */
    List<Voucher> findAllByCodeIn(java.util.Collection<String> codes);

    /** Voucher registrations bucketed per period for the dashboard charts. */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT date_trunc(:grain, created_at) AS d, COUNT(*)
            FROM vouchers
            WHERE tenant_id = :tenantId AND created_at >= :fromTs AND created_at < :toTs
            GROUP BY 1
            """, nativeQuery = true)
    java.util.List<Object[]> registrationBuckets(@org.springframework.data.repository.query.Param("tenantId") Long tenantId,
                                                 @org.springframework.data.repository.query.Param("grain") String grain,
                                                 @org.springframework.data.repository.query.Param("fromTs") OffsetDateTime fromTs,
                                                 @org.springframework.data.repository.query.Param("toTs") OffsetDateTime toTs);
}
