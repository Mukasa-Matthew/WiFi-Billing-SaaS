package com.wifibilling.repository;

import com.wifibilling.radius.Nas;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NasRepository extends JpaRepository<Nas, Long> {

    Optional<Nas> findByNasname(String nasname);

    boolean existsByNasname(String nasname);

    void deleteByNasname(String nasname);
}
