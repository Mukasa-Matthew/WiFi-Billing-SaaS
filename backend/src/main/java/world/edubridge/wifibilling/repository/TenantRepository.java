package world.edubridge.wifibilling.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import world.edubridge.wifibilling.domain.Tenant;

import java.util.List;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    Optional<Tenant> findBySlug(String slug);
    List<Tenant> findAllByOrderByIdAsc();
}
