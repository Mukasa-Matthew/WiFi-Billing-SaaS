package com.wifibilling.controller;

import com.wifibilling.model.PortalFeedback;
import com.wifibilling.repository.PortalFeedbackRepository;
import com.wifibilling.security.TenantPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final PortalFeedbackRepository feedbackRepository;

    public record FeedbackRow(Long id, String name, String phone, String mac, String router,
                            Integer rating, String message, OffsetDateTime createdAt) {}

    @GetMapping
    @Transactional(readOnly = true)
    public List<FeedbackRow> list(@AuthenticationPrincipal TenantPrincipal principal) {
        return feedbackRepository.findAllByTenantIdOrderByCreatedAtDesc(principal.getTenantId())
                .stream()
                .map(f -> new FeedbackRow(f.getId(), f.getName(), f.getPhone(), f.getMac(),
                        f.getRouter() != null ? f.getRouter().getName() : null,
                        f.getRating(), f.getMessage(), f.getCreatedAt()))
                .toList();
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable Long id) {
        PortalFeedback f = feedbackRepository.findById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Feedback not found"));
        if (!f.getTenant().getId().equals(principal.getTenantId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Not your feedback");
        }
        feedbackRepository.delete(f);
    }
}
