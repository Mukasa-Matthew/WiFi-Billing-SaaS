package com.wifibilling.repository;

import com.wifibilling.model.WithdrawalRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface WithdrawalRequestRepository extends JpaRepository<WithdrawalRequest, Long> {
    List<WithdrawalRequest> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);
    Optional<WithdrawalRequest> findByIdAndTenantId(Long id, Long tenantId);
    Optional<WithdrawalRequest> findByPayoutRef(String payoutRef);
    Optional<WithdrawalRequest> findByProviderUuid(String providerUuid);
    List<WithdrawalRequest> findAllByStatusIn(List<String> statuses);
    long countByTenantIdAndStatusIn(Long tenantId, List<String> statuses);
}
