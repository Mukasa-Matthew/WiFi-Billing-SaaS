package com.wifibilling.service;

import com.wifibilling.model.Tenant;
import com.wifibilling.model.TenantPortalAsset;
import com.wifibilling.repository.TenantPortalAssetRepository;
import com.wifibilling.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Captive-portal logo and background images. Stored in the database (not the slot's
 * disk, which every blue-green deploy replaces), validated by their actual bytes, and
 * versioned by content hash so a replaced image is never served from a stale cache.
 */
@Service
@RequiredArgsConstructor
public class PortalAssetService {

    /** Per-image limit; the dashboard shrinks photos before upload, so real files are far smaller. */
    public static final long MAX_BYTES = 1_000_000;

    private static final Pattern UNSAFE_SVG = Pattern.compile(
            "<script|<foreignobject|javascript:|\\son[a-z]+\\s*=|<iframe|<embed|<object", Pattern.CASE_INSENSITIVE);

    private final TenantPortalAssetRepository assets;
    private final TenantRepository tenants;

    /** Validates and stores an image, replacing any previous one of the same kind. */
    @Transactional
    public Tenant store(Tenant tenant, String kind, byte[] data) {
        requireKind(kind);
        if (data == null || data.length == 0) throw bad("The image is empty.");
        if (data.length > MAX_BYTES) throw bad("The image is too large (max " + MAX_BYTES / 1000 + " KB).");
        String type = detectType(data, TenantPortalAsset.LOGO.equals(kind));
        String sha = sha256(data);

        TenantPortalAsset asset = assets.findByTenantIdAndKind(tenant.getId(), kind)
                .orElseGet(() -> TenantPortalAsset.builder().tenantId(tenant.getId()).kind(kind).build());
        asset.setContentType(type);
        asset.setData(data);
        asset.setSha256(sha);
        asset.setUpdatedAt(OffsetDateTime.now());
        assets.save(asset);

        // The tenant field doubles as "has an image" and its cache-busting version.
        String version = kind + "-" + sha.substring(0, 12);
        if (TenantPortalAsset.LOGO.equals(kind)) tenant.setPortalLogoFile(version); else tenant.setPortalBackgroundFile(version);
        return tenants.save(tenant);
    }

    @Transactional
    public Tenant remove(Tenant tenant, String kind) {
        requireKind(kind);
        assets.deleteByTenantIdAndKind(tenant.getId(), kind);
        if (TenantPortalAsset.LOGO.equals(kind)) tenant.setPortalLogoFile(null); else tenant.setPortalBackgroundFile(null);
        return tenants.save(tenant);
    }

    @Transactional(readOnly = true)
    public Optional<TenantPortalAsset> find(Long tenantId, String kind) {
        if (!TenantPortalAsset.LOGO.equals(kind) && !TenantPortalAsset.BACKGROUND.equals(kind)) return Optional.empty();
        return assets.findByTenantIdAndKind(tenantId, kind);
    }

    /** The real image type from its first bytes; the browser-reported type is not trusted. */
    static String detectType(byte[] d, boolean allowSvg) {
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "image/webp";
        if (allowSvg) {
            String head = new String(d, 0, Math.min(d.length, 4096), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (head.contains("<svg")) {
                if (UNSAFE_SVG.matcher(new String(d, StandardCharsets.UTF_8)).find()) {
                    throw bad("This SVG contains scripts or links and can't be used. Export it as PNG instead.");
                }
                return "image/svg+xml";
            }
        }
        throw bad("Use a PNG, JPG" + (allowSvg ? ", SVG" : "") + " or WebP image.");
    }

    private static void requireKind(String kind) {
        if (!TenantPortalAsset.LOGO.equals(kind) && !TenantPortalAsset.BACKGROUND.equals(kind)) {
            throw bad("kind must be logo or background");
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
