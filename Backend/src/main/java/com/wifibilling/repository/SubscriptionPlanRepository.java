package com.wifibilling.repository;

import com.wifibilling.model.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, Long> {
    List<SubscriptionPlan> findAllByOrderByDisplayOrderAscPriceAsc();
    List<SubscriptionPlan> findAllByActiveTrueOrderByDisplayOrderAscPriceAsc();
    Optional<SubscriptionPlan> findByNameIgnoreCase(String name);
}
