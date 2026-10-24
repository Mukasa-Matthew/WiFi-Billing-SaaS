package com.wifibilling.repository;

import com.wifibilling.model.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    Optional<Subscription> findByTenantId(Long tenantId);
    Optional<Subscription> findByIdAndTenantId(Long id, Long tenantId);
    List<Subscription> findAllByStatus(String status);
    List<Subscription> findAllByStatusAndNextRenewalDateLessThanEqual(String status, LocalDate date);
}
