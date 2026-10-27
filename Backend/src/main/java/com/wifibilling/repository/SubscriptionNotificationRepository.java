package com.wifibilling.repository;

import com.wifibilling.model.SubscriptionNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionNotificationRepository extends JpaRepository<SubscriptionNotification, Long> {

    boolean existsBySubscriptionIdAndNotificationTypeAndBillingCycleKey(Long subscriptionId, String notificationType, String billingCycleKey);

    List<SubscriptionNotification> findAllByTenantIdAndDismissedFalseOrderByCreatedAtDesc(Long tenantId);

    List<SubscriptionNotification> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<SubscriptionNotification> findAllBySubscriptionIdOrderByCreatedAtDesc(Long subscriptionId);

    Optional<SubscriptionNotification> findByIdAndTenantId(Long id, Long tenantId);
}
