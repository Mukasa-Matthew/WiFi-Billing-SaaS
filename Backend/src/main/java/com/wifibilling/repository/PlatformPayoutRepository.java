package com.wifibilling.repository;

import com.wifibilling.model.PlatformPayout;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PlatformPayoutRepository extends JpaRepository<PlatformPayout, Long> {
    Optional<PlatformPayout> findByPayoutRef(String payoutRef);
    List<PlatformPayout> findTop100ByOrderByCreatedAtDesc();
}
