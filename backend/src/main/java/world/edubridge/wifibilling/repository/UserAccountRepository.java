package world.edubridge.wifibilling.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import world.edubridge.wifibilling.domain.UserAccount;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByEmailIgnoreCase(String email);
    List<UserAccount> findByTenantIdOrderByIdAsc(Long tenantId);
}
