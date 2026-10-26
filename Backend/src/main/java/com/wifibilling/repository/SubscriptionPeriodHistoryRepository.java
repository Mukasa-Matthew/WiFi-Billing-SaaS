package com.wifibilling.repository;

import com.wifibilling.model.SubscriptionPeriodHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubscriptionPeriodHistoryRepository extends JpaRepository<SubscriptionPeriodHistory, Long> {

    List<SubscriptionPeriodHistory> findAllByTenantIdOrderByPeriodStartDesc(Long tenantId);

    List<SubscriptionPeriodHistory> findAllBySubscriptionIdOrderByPeriodStartDesc(Long subscriptionId);
}
