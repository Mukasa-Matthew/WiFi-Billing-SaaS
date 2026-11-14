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

    /** School vouchers of one person that may still be used, newest first. */
    @org.springframework.data.jpa.repository.Query("select v from Voucher v where v.tenant.id = :tenantId and v.personId = :personId "
            + "and v.schoolCategory = :category and v.status in ('unused', 'active') order by v.createdAt desc")
    List<Voucher> findLiveForPerson(@org.springframework.data.repository.query.Param("tenantId") Long tenantId,
            @org.springframework.data.repository.query.Param("personId") String personId,
            @org.springframework.data.repository.query.Param("category") String category);

    @org.springframework.data.jpa.repository.Query("select v from Voucher v where v.tenant.id = :tenantId and v.personId = :personId and v.status in ('unused', 'active')")
    List<Voucher> findAllLiveForPerson(@org.springframework.data.repository.query.Param("tenantId") Long tenantId,
            @org.springframework.data.repository.query.Param("personId") String personId);

    /** School vouchers currently claimed by a client MAC address for zero-touch portal auto-reconnect. */
    @org.springframework.data.jpa.repository.Query("select v from Voucher v where v.tenant.id = :tenantId and upper(v.claimedMac) = :mac "
            + "and v.schoolCategory is not null and v.status in ('unused', 'active') order by v.createdAt desc")
    List<Voucher> findLiveByTenantIdAndClaimedMac(@org.springframework.data.repository.query.Param("tenantId") Long tenantId,
            @org.springframework.data.repository.query.Param("mac") String mac);

    /** Hotspot (non-school) vouchers claimed by a client MAC — lets a returning device auto-reconnect. */
    @org.springframework.data.jpa.repository.Query("select v from Voucher v where v.tenant.id = :tenantId and upper(v.claimedMac) = :mac "
            + "and v.schoolCategory is null and v.status in ('unused', 'active') order by v.createdAt desc")
    List<Voucher> findLiveHotspotByTenantIdAndClaimedMac(@org.springframework.data.repository.query.Param("tenantId") Long tenantId,
            @org.springframework.data.repository.query.Param("mac") String mac);

    List<Voucher> findAllByTenantIdAndVisitorPassIdAndStatusIn(Long tenantId, String visitorPassId, java.util.Collection<String> statuses);

    List<Voucher> findAllByTenantIdAndVisitorPassId(Long tenantId, String visitorPassId);

    List<Voucher> findAllByTenantIdAndPersonIdAndSchoolCategory(Long tenantId, String personId, String schoolCategory);

    /** Stamps the person's current name on every one of their school vouchers that doesn't already carry it. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update Voucher v set v.personName = :name where v.tenant.id = :tenantId and v.personId = :personId and (v.personName is null or v.personName <> :name)")
    int stampPersonName(@org.springframework.data.repository.query.Param("tenantId") Long tenantId,
            @org.springframework.data.repository.query.Param("personId") String personId,
            @org.springframework.data.repository.query.Param("name") String name);

    /** Any name already stamped on this person's vouchers — fills vouchers bound before the name was known. */
    @org.springframework.data.jpa.repository.Query("select v.personName from Voucher v where v.tenant.id = :tenantId and v.personId = :personId and v.personName is not null")
    List<String> findPersonNames(@org.springframework.data.repository.query.Param("tenantId") Long tenantId,
            @org.springframework.data.repository.query.Param("personId") String personId);

    List<Voucher> findAllByTenantIdAndSchoolCategoryInAndStatusIn(Long tenantId, java.util.Collection<String> categories, java.util.Collection<String> statuses);

    List<Voucher> findAllBySchoolCategoryInAndStatusIn(java.util.Collection<String> categories, java.util.Collection<String> statuses);

    /** Never-used vouchers whose hard limit (leaving date, pass expiry) has passed. */
    List<Voucher> findAllByStatusAndHardExpiresAtBefore(String status, OffsetDateTime cutoff);
    Optional<Voucher> findByCodeAndTenantId(String code, Long tenantId);
    /** Vouchers linked to a customer: the customer directory's rows. */
    List<Voucher> findAllByTenantIdAndCustomerIsNotNullOrderByCreatedAtDesc(Long tenantId);
    List<Voucher> findAllByTenantIdAndCustomerIdOrderByCreatedAtDesc(Long tenantId, Long customerId);

    boolean existsByCode(String code);

    List<Voucher> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<Voucher> findAllByTenantIdAndStatusOrderByCreatedAtDesc(Long tenantId, String status);

    List<Voucher> findAllByStatus(String status);

    List<Voucher> findAllByStatusIn(List<String> statuses);

    long countByTenantId(Long tenantId);

    long countByTenantIdAndStatus(Long tenantId, String status);

    /** Vouchers still marked with the given status whose validity window has passed. */
    List<Voucher> findAllByStatusAndExpiresAtBefore(String status, OffsetDateTime cutoff);

    /** Just the voucher codes for a tenant — cheap scoping query for radacct filters. */
    @org.springframework.data.jpa.repository.Query("SELECT v.code FROM Voucher v WHERE v.tenant.id = :tenantId")
    java.util.Set<String> codesByTenant(@org.springframework.data.repository.query.Param("tenantId") Long tenantId);

    /** Look up a small set of vouchers by code (dashboard joins, active sessions). */
    List<Voucher> findAllByCodeIn(java.util.Collection<String> codes);

    /** Lightweight (code, tenant, person, pass, category, sale_type) rows for every
     *  voucher tied to a school person or visitor pass — used to build cap-sharing
     *  groups without loading entities or querying per voucher. Expired rows are
     *  included on purpose: their usage still counts toward a shared cap. */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT code, tenant_id, person_id, visitor_pass_id, school_category, sale_type
            FROM vouchers
            WHERE person_id IS NOT NULL OR visitor_pass_id IS NOT NULL
            """, nativeQuery = true)
    List<Object[]> allSchoolGroupCodes();

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
