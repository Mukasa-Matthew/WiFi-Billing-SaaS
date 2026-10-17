package world.edubridge.wifibilling.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import world.edubridge.wifibilling.domain.TenantSubscription;

import java.util.Optional;

public interface TenantSubscriptionRepository extends JpaRepository<TenantSubscription, Long> {
    Optional<TenantSubscription> findByTenantId(Long tenantId);
}
