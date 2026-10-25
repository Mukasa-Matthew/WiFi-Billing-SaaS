package com.wifibilling.repository;

import com.wifibilling.radius.RadPostAuth;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;

@Repository
public interface RadPostAuthRepository extends JpaRepository<RadPostAuth, Long> {

    /** Auth events for a tenant's voucher usernames, newest first, with optional reply/username filters. */
    @Query("""
            SELECT e FROM RadPostAuth e
            WHERE e.username IN :usernames
              AND (:reply IS NULL OR e.reply = CAST(:reply AS String))
              AND (:username IS NULL OR LOWER(e.username) LIKE LOWER(CONCAT('%', CAST(:username AS String), '%')))
              AND (:fromTs IS NULL OR e.authDate >= :fromTs)
              AND (:toTs IS NULL OR e.authDate < :toTs)
            ORDER BY e.authDate DESC
            """)
    Page<RadPostAuth> findEvents(@Param("usernames") Collection<String> usernames,
                                 @Param("reply") String reply,
                                 @Param("username") String username,
                                 @Param("fromTs") OffsetDateTime fromTs,
                                 @Param("toTs") OffsetDateTime toTs,
                                 Pageable pageable);

    /** Reject count within a window — used for burst detection. */
    long countByUsernameInAndReplyAndAuthDateAfter(Collection<String> usernames, String reply, OffsetDateTime after);

    /** Most recent auth event per username is overkill; we only need platform counts. */
    @Query("SELECT COUNT(e) FROM RadPostAuth e WHERE e.reply = :reply AND e.authDate >= :after")
    long countByReplySince(@Param("reply") String reply, @Param("after") OffsetDateTime after);
}
