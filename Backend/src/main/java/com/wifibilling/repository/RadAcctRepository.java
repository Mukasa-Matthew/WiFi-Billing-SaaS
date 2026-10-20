package com.wifibilling.repository;

import com.wifibilling.radius.RadAcct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RadAcctRepository extends JpaRepository<RadAcct, Long> {

    /** Find all active (open) accounting sessions for a username. */
    List<RadAcct> findAllByUsernameAndAcctStopTimeIsNull(String username);

    /** Find the earliest start time recorded for a username to stamp first_login. */
    @Query("SELECT r FROM RadAcct r WHERE r.username = :username ORDER BY r.acctStartTime ASC LIMIT 1")
    Optional<RadAcct> findFirstSessionByUsername(@Param("username") String username);
}
