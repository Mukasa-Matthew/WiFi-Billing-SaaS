package com.wifibilling.repository;

import com.wifibilling.model.Voucher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface VoucherRepository extends JpaRepository<Voucher, Long> {

    Optional<Voucher> findByCode(String code);

    boolean existsByCode(String code);

    List<Voucher> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<Voucher> findAllByTenantIdAndStatusOrderByCreatedAtDesc(Long tenantId, String status);

    List<Voucher> findAllByStatus(String status);

    long countByTenantIdAndStatus(Long tenantId, String status);

    /** Vouchers still marked with the given status whose validity window has passed. */
    List<Voucher> findAllByStatusAndExpiresAtBefore(String status, OffsetDateTime cutoff);
}
