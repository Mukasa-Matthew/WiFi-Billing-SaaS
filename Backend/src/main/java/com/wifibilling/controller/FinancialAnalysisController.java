package com.wifibilling.controller;

import com.wifibilling.dto.FinancialAnalysisDto;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.FinancialAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/** The hotspot owner's Financial analysis page (their own tenant only). */
@RestController
@RequestMapping("/api/finance/analysis")
@RequiredArgsConstructor
public class FinancialAnalysisController {

    private static final List<String> CHANNELS = List.of("MTN", "AIRTEL", "OTHER", "WALK_IN");
    private static final List<String> GROUPS = List.of("STUDENT", "PAID_CUSTOMER", "WALK_IN");

    private final FinancialAnalysisService service;

    @GetMapping
    public FinancialAnalysisDto analyse(@AuthenticationPrincipal TenantPrincipal p,
                                        @RequestParam(defaultValue = "30d") String period,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(required = false) Long routerId,
                                        @RequestParam(required = false) Long packageId,
                                        @RequestParam(required = false) String channel,
                                        @RequestParam(required = false) String group) {
        if (!FinancialAnalysisService.PERIODS.contains(period)) throw bad("Unknown period");
        if (channel != null && !channel.isBlank() && !CHANNELS.contains(channel)) throw bad("Unknown payment channel");
        if (group != null && !group.isBlank() && !GROUPS.contains(group)) throw bad("Unknown customer type");
        return service.analyse(p.getTenantId(), period, from, to, new FinancialAnalysisService.Filters(
                routerId, packageId, blankToNull(channel), blankToNull(group)));
    }

    public record TargetRequest(Long target) {}
    public record TargetResponse(Long target) {}

    @PutMapping("/target")
    public TargetResponse setTarget(@AuthenticationPrincipal TenantPrincipal p, @RequestBody TargetRequest req) {
        return new TargetResponse(service.setTarget(p.getTenantId(), req == null ? null : req.target()));
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
}
