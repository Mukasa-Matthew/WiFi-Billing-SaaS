package com.wifibilling.repository;

import com.wifibilling.model.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {
    boolean existsByPayloadHash(String payloadHash);
}
