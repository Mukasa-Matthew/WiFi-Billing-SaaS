package com.wifibilling.repository;
import com.wifibilling.model.RouterTask;import org.springframework.data.jpa.repository.JpaRepository;import java.util.*;
public interface RouterTaskRepository extends JpaRepository<RouterTask,Long> {
 List<RouterTask> findAllByRouterIdOrderByCreatedAtDesc(Long routerId);
 Optional<RouterTask> findFirstByRouterIdAndStatusOrderByIdAsc(Long routerId,String status);
 Optional<RouterTask> findByIdAndRouterId(Long id,Long routerId);
}
