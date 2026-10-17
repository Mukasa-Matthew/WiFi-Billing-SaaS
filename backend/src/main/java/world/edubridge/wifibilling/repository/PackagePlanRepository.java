package world.edubridge.wifibilling.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import world.edubridge.wifibilling.domain.PackagePlan;

import java.util.List;
import java.util.Optional;

public interface PackagePlanRepository extends JpaRepository<PackagePlan, Long> {
    List<PackagePlan> findByTenantIdOrderBySortOrderAscIdAsc(Long tenantId);
    List<PackagePlan> findByTenantIdAndActiveTrueAndPortalVisibleTrueOrderBySortOrderAscIdAsc(Long tenantId);
    Optional<PackagePlan> findByIdAndTenantId(Long id, Long tenantId);
}
