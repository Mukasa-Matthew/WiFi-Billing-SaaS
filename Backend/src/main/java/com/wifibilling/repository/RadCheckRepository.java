package com.wifibilling.repository;

import com.wifibilling.radius.RadCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface RadCheckRepository extends JpaRepository<RadCheck, Long> {

    List<RadCheck> findAllByUsername(String username);

    Optional<RadCheck> findFirstByUsernameAndAttribute(String username, String attribute);

    boolean existsByUsername(String username);

    @Transactional
    long deleteAllByUsername(String username);
}
