package com.wifibilling.repository;

import com.wifibilling.model.IntegrationNonce;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface IntegrationNonceRepository extends JpaRepository<IntegrationNonce, String> {
    @Modifying
    @Query("DELETE FROM IntegrationNonce n WHERE n.receivedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") OffsetDateTime cutoff);
}
