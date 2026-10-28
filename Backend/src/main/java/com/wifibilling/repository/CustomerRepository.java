package com.wifibilling.repository;
import com.wifibilling.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface CustomerRepository extends JpaRepository<Customer,Long> {
 List<Customer> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);
 Optional<Customer> findByIdAndTenantId(Long id,Long tenantId);
 long countByTenantId(Long tenantId);
 Optional<Customer> findFirstByTenantIdAndPhoneInOrderByIdAsc(Long tenantId,Collection<String> phones);
 long countByTenantIdAndCreatedAtGreaterThanEqual(Long tenantId,java.time.OffsetDateTime since);
}
