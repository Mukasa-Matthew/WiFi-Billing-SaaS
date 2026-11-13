package com.wifibilling.controller;

import com.wifibilling.service.SystemHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Super-admin live health probes for the System health page. Read-only. */
@RestController
@RequestMapping("/api/admin/system-health")
@PreAuthorize("hasAuthority('ROLE_SUPERADMIN')")
@RequiredArgsConstructor
public class AdminHealthController {

    private final SystemHealthService health;

    @GetMapping
    public SystemHealthService.Report probe() { return health.probe(); }
}
