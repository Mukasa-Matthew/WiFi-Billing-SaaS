package com.wifibilling.repository;

import com.wifibilling.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByReference(String reference);

    boolean existsByReference(String reference);

    List<Transaction> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<Transaction> findAllByTenantIdAndStatusOrderByCreatedAtDesc(Long tenantId, String status);

    Optional<Transaction> findFirstByTenantIdAndPhoneAndStatusOrderByCreatedAtDesc(
            Long tenantId, String phone, String status);

    boolean existsByRouterId(Long routerId);
}
