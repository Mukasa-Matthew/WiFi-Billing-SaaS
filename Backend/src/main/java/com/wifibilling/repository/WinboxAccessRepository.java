package com.wifibilling.repository;

import com.wifibilling.model.WinboxAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface WinboxAccessRepository extends JpaRepository<WinboxAccess, Integer> {
    Optional<WinboxAccess> findByRouterId(Long routerId);
}
