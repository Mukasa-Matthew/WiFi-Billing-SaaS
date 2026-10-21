package com.wifibilling.controller;

import com.wifibilling.dto.ActiveSessionDto;
import com.wifibilling.dto.DisconnectSessionRequest;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    @GetMapping("/active")
    public List<ActiveSessionDto> getActiveSessions(@AuthenticationPrincipal TenantPrincipal principal) {
        return sessionService.getActiveSessions(principal.getTenantId(), principal.getRole());
    }

    @GetMapping("/history")
    public List<ActiveSessionDto> getSessionHistory(@AuthenticationPrincipal TenantPrincipal principal) {
        return sessionService.getSessionHistory(principal.getTenantId(), principal.getRole());
    }

    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Valid @RequestBody DisconnectSessionRequest request) {
        boolean sent = sessionService.disconnectSession(principal.getTenantId(), principal.getRole(), request.username());
        return ResponseEntity.ok(Map.of(
                "success", sent,
                "message", "Disconnect request issued for " + request.username(),
                "username", request.username()
        ));
    }
}
