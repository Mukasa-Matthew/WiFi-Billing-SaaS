package com.wifibilling.repository;

import com.wifibilling.radius.RadReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface RadReplyRepository extends JpaRepository<RadReply, Long> {

    List<RadReply> findAllByUsername(String username);

    Optional<RadReply> findFirstByUsernameAndAttribute(String username, String attribute);

    @Transactional
    long deleteAllByUsername(String username);
}
