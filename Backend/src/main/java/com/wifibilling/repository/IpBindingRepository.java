package com.wifibilling.repository;
import com.wifibilling.model.IpBinding;import org.springframework.data.jpa.repository.JpaRepository;import java.util.*;
public interface IpBindingRepository extends JpaRepository<IpBinding,Long> {
 List<IpBinding> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);
 List<IpBinding> findAllByRouterId(Long routerId);
 Optional<IpBinding> findByIdAndTenantId(Long id,Long tenantId);
}
