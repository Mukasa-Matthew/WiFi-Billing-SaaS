package com.wifibilling.controller;

import com.wifibilling.dto.TenantDashboardStatsDto;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.TenantDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class TenantDashboardController {

    private final TenantDashboardService dashboardService;

    @GetMapping("/stats")
    public TenantDashboardStatsDto getStats(@AuthenticationPrincipal TenantPrincipal principal,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "7d") String range) {
        return dashboardService.getStats(principal.getTenantId(), principal.getRole(), range);
    }
}
