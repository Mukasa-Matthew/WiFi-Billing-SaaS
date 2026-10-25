package com.wifibilling.repository;

import com.wifibilling.model.NocIncident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NocIncidentRepository extends JpaRepository<NocIncident, Long> {

    Optional<NocIncident> findFirstByRouterIdAndTypeAndResolvedAtIsNull(Long routerId, String type);

    List<NocIncident> findAllByResolvedAtIsNullOrderByOpenedAtDesc();

    List<NocIncident> findTop50ByRouterIdOrderByOpenedAtDesc(Long routerId);

    long countByResolvedAtIsNull();
}
