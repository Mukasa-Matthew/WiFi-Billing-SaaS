package com.wifibilling.repository;

import com.wifibilling.model.WithdrawalRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface WithdrawalRequestRepository extends JpaRepository<WithdrawalRequest, Long> {
    List<WithdrawalRequest> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);
    Optional<WithdrawalRequest> findByIdAndTenantId(Long id, Long tenantId);
    Optional<WithdrawalRequest> findByPayoutRef(String payoutRef);
    Optional<WithdrawalRequest> findByProviderUuid(String providerUuid);
    List<WithdrawalRequest> findAllByOrderByCreatedAtDesc();
    List<WithdrawalRequest> findAllByStatusIn(List<String> statuses);
    long countByTenantIdAndStatusIn(Long tenantId, List<String> statuses);

    Optional<WithdrawalRequest> findByTenantIdAndIdempotencyKey(Long tenantId, String idempotencyKey);

    /** Row lock: dispatch outcome, provider callbacks, cancel and fail all
     *  serialize on the withdrawal before touching the wallet. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WithdrawalRequest w where w.id = :id")
    Optional<WithdrawalRequest> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WithdrawalRequest w where w.payoutRef = :payoutRef")
    Optional<WithdrawalRequest> findByPayoutRefForUpdate(@Param("payoutRef") String payoutRef);

    /**
     * Atomic dispatch claim: succeeds (returns 1) for exactly one caller, and only
     * for a withdrawal that has never been sent. Everything else gets 0.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update WithdrawalRequest w
               set w.status = 'PROCESSING', w.dispatchedAt = :now, w.version = w.version + 1
             where w.id = :id and w.dispatchedAt is null
               and w.status in ('REQUESTED', 'PENDING_APPROVAL')
            """)
    int claimDispatch(@Param("id") Long id, @Param("now") OffsetDateTime now);

    /** Sent payouts still awaiting a provider outcome, oldest first. */
    List<WithdrawalRequest> findTop50ByStatusAndDispatchedAtBeforeOrderByDispatchedAtAsc(String status, OffsetDateTime before);
}
