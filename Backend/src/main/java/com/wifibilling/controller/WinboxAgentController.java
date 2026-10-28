package com.wifibilling.controller;

import com.wifibilling.service.WinboxAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor
public class WinboxAgentController {
    private final WinboxAccessService access;
    private static String bearer(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
    }
    private static <T> ResponseEntity<T> sensitive(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                .header("Referrer-Policy", "no-referrer").header("X-Content-Type-Options", "nosniff").body(body);
    }
    @GetMapping(value = "/api/router/setup/{code}/install-winbox.rsc", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> installer(@PathVariable String code) { return sensitive(access.installer(code)); }

    @GetMapping(value = "/api/router/winbox/{id}/config.rsc", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> config(@PathVariable Long id, @RequestHeader(value = "Authorization", required = false) String auth) {
        return sensitive(access.agentConfig(id, bearer(auth)));
    }
    @PostMapping("/api/router/winbox/{id}/ack")
    public ResponseEntity<Void> ack(@PathVariable Long id, @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam long revision, @RequestParam boolean success) {
        access.acknowledge(id, bearer(auth), revision, success);
        return sensitive(null);
    }
    @GetMapping("/api/internal/winbox/routes")
    public ResponseEntity<WinboxAccessService.Routes> routes(@RequestHeader(value = "Authorization", required = false) String auth) {
        return sensitive(access.routes(bearer(auth)));
    }
    @PostMapping("/api/internal/winbox/checks")
    public ResponseEntity<Void> checks(@RequestHeader(value = "Authorization", required = false) String auth, @RequestBody List<WinboxAccessService.Check> checks) {
        access.checked(bearer(auth), checks);
        return sensitive(null);
    }
}
