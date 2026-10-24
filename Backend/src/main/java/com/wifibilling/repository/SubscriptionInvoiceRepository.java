package com.wifibilling.repository;

import com.wifibilling.model.SubscriptionInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SubscriptionInvoiceRepository extends JpaRepository<SubscriptionInvoice, Long> {
    List<SubscriptionInvoice> findAllBySubscriptionIdOrderByPeriodStartDesc(Long subscriptionId);
    Optional<SubscriptionInvoice> findBySubscriptionIdAndPeriodStart(Long subscriptionId, LocalDate periodStart);
    List<SubscriptionInvoice> findAllByTenantIdAndStatus(Long tenantId, String status);
    List<SubscriptionInvoice> findAllByStatusAndPeriodEndLessThanEqual(String status, LocalDate date);
    long countByStatus(String status);
}
