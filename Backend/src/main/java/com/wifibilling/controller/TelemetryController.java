package com.wifibilling.controller;

import com.wifibilling.dto.TelemetryRequest;
import com.wifibilling.service.TelemetryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/router")
@RequiredArgsConstructor
public class TelemetryController {

    private final TelemetryService telemetryService;

    @PostMapping(value = "/telemetry", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> telemetry(@Valid @RequestBody TelemetryRequest body) {
        telemetryService.record(body);
        return ResponseEntity.noContent().build();
    }
}
