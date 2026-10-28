package com.wifibilling.controller;

import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.AuthService;
import com.wifibilling.service.WinboxAccessService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping({"/api/routers/{id}/winbox", "/api/admin/noc/routers/{id}/winbox"})
public class WinboxAccessController {
    private final WinboxAccessService access;

    private static <T> ResponseEntity<T> sensitive(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                .header("Referrer-Policy", "no-referrer").body(body);
    }
    @GetMapping
    public ResponseEntity<WinboxAccessService.Status> status(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id, HttpServletRequest req) {
        return sensitive(access.status(p, id, AuthService.extractClientIp(req)));
    }
    @PostMapping("/{operation:enable|disable|rotate}")
    public ResponseEntity<WinboxAccessService.Status> change(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id,
            @PathVariable String operation, HttpServletRequest req) {
        return sensitive(access.change(p, id, operation, AuthService.extractClientIp(req)));
    }
    @PostMapping("/installation")
    public ResponseEntity<WinboxAccessService.Installation> installation(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id, HttpServletRequest req) {
        return sensitive(access.installation(p, id, AuthService.extractClientIp(req)));
    }
    @PostMapping("/credentials")
    public ResponseEntity<WinboxAccessService.Credentials> credentials(@AuthenticationPrincipal TenantPrincipal p, @PathVariable Long id, HttpServletRequest req) {
        return sensitive(access.credentials(p, id, AuthService.extractClientIp(req)));
    }
}
