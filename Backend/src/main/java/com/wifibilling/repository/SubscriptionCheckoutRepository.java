package com.wifibilling.repository;

import com.wifibilling.model.SubscriptionCheckout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionCheckoutRepository extends JpaRepository<SubscriptionCheckout, Long> {

    Optional<SubscriptionCheckout> findByReference(String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SubscriptionCheckout c where c.reference = :reference")
    Optional<SubscriptionCheckout> findByReferenceForUpdate(String reference);

    Optional<SubscriptionCheckout> findByExternalReference(String externalReference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SubscriptionCheckout c where c.externalReference = :externalReference")
    Optional<SubscriptionCheckout> findByExternalReferenceForUpdate(String externalReference);

    List<SubscriptionCheckout> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<SubscriptionCheckout> findAllByStatus(String status);
}
