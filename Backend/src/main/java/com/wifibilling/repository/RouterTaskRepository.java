package com.wifibilling.repository;
import com.wifibilling.model.RouterTask;import org.springframework.data.jpa.repository.JpaRepository;import java.util.*;
public interface RouterTaskRepository extends JpaRepository<RouterTask,Long> {
 void deleteAllByRouterId(Long routerId);
 List<RouterTask> findAllByRouterIdOrderByCreatedAtDesc(Long routerId);
 Optional<RouterTask> findFirstByRouterIdAndStatusOrderByIdAsc(Long routerId,String status);
 Optional<RouterTask> findFirstByRouterIdAndActionAndStatusOrderByIdAsc(Long routerId,String action,String status);
 Optional<RouterTask> findFirstByRouterIdAndActionOrderByCreatedAtDesc(Long routerId,String action);
 Optional<RouterTask> findByIdAndRouterId(Long id,Long routerId);
}
