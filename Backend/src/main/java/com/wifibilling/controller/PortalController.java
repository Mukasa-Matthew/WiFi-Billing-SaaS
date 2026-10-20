package com.wifibilling.controller;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.dto.PortalPackageResponse;
import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Router;
import com.wifibilling.repository.PackagePlanRepository;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.util.RouterTokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/portal")
@RequiredArgsConstructor
public class PortalController {

    private final RouterRepository routerRepository;
    private final PackagePlanRepository packagePlanRepository;
    private final PlatformProperties props;

    /**
     * Public endpoint invoked by captive portal client-side JS.
     * Instantly reflects package creations, edits, and deletions performed by the router's tenant.
     */
    @GetMapping(value = "/packages", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PortalPackageResponse> getPackages(@RequestParam("token") String token) {
        Router router = resolveRouter(token);
        List<PackagePlan> plans = packagePlanRepository.findAllByTenantIdOrderByPriceAsc(router.getTenant().getId());

        return plans.stream()
                .map(p -> new PortalPackageResponse(
                        p.getId(),
                        p.getName(),
                        p.getPrice(),
                        p.getRateLimit(),
                        p.getSessionTimeout()))
                .toList();
    }

    /**
     * Serves hotspot templates from static/hotspot/{filename}.
     * When serving login.html, substitutes ROUTER_TOKEN_PLACEHOLDER and SERVER_BASE_URL_PLACEHOLDER
     * with the router's actual token and configured public base URL.
     */
    @GetMapping(value = "/templates/{filename}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> getTemplate(
            @PathVariable String filename,
            @RequestParam("token") String token) {

        // Validate token
        Router router = resolveRouter(token);

        // Sanitize filename to prevent directory traversal
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
        }

        Resource resource = new ClassPathResource("static/hotspot/" + filename);
        if (!resource.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Template file not found: " + filename);
        }

        try {
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);

            if ("login.html".equalsIgnoreCase(filename)) {
                content = content
                        .replace("ROUTER_TOKEN_PLACEHOLDER", router.getToken())
                        .replace("SERVER_BASE_URL_PLACEHOLDER", props.getPublicBaseUrl());
            }

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.TEXT_HTML)
                    .body(content);
        } catch (IOException e) {
            log.error("Failed to read template {}: {}", filename, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error reading template");
        }
    }

    private Router resolveRouter(String token) {
        if (!RouterTokens.isValid(token)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found");
        }
        return routerRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found"));
    }
}
