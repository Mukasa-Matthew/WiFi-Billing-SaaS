package com.wifibilling.repository;

import com.wifibilling.model.RouterSetupCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface RouterSetupCodeRepository extends JpaRepository<RouterSetupCode, Long> {

    Optional<RouterSetupCode> findByCodeHash(String codeHash);

    /** Single-use redemption: only one caller can flip scriptFetchedAt from null. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RouterSetupCode c set c.scriptFetchedAt = :now where c.id = :id and c.scriptFetchedAt is null and c.revokedAt is null")
    int markScriptFetched(@Param("id") Long id, @Param("now") OffsetDateTime now);

    /** Binds the first registered key; only one caller can set it. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RouterSetupCode c set c.registeredKeyHash = :keyHash where c.id = :id and c.registeredKeyHash is null")
    int bindRegisteredKey(@Param("id") Long id, @Param("keyHash") String keyHash);

    /** Issuing a new code invalidates any earlier unfinished one for the router. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RouterSetupCode c set c.revokedAt = :now where c.router.id = :routerId and c.revokedAt is null and c.completedAt is null")
    int revokeOutstanding(@Param("routerId") Long routerId, @Param("now") OffsetDateTime now);

    void deleteAllByRouterId(Long routerId);
}
