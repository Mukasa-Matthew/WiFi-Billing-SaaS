package com.wifibilling;

import com.jayway.jsonpath.JsonPath;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Portal logo and background: an uploaded image must actually reach the captive
 * portal. It used to be written to the slot's disk and the URL handed to the portal
 * had no endpoint behind it, so every upload showed as a broken image.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:portal_assets;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=test-only-jwt-secret-with-at-least-32-characters",
        "wireguard.server.endpoint=203.0.113.10:443",
        "wireguard.server.public-key=yoay+E/LyCqRq0xxqsIRJdRoqe8XjRVmkMe3nDx5wEc=",
        "wireguard.server.subnet=10.9.0.0/16",
        "radius.server.ip=10.9.0.1",
        "provisioning.public-base-url=http://localhost:8080",
        "payment.gateway.mode=mock"
})
@AutoConfigureMockMvc
class PortalAssetsIntegrationTest {

    @MockitoBean com.wifibilling.service.EmailService emailService;
    @Autowired MockMvc mvc;
    @Autowired TenantRepository tenants;
    @Autowired PasswordEncoder passwords;
    @Autowired com.wifibilling.service.SubscriptionService subscriptions;

    /** Smallest valid PNG / JPEG / WebP headers: enough for type detection. */
    static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3);
    static final byte[] PNG2 = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 9, 9, 9);
    static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 16, 'J', 'F', 'I', 'F');
    static final byte[] SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\"/></svg>".getBytes(StandardCharsets.UTF_8);
    static final byte[] EVIL_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"></svg>".getBytes(StandardCharsets.UTF_8);

    record Site(String jwt, String token) {}

    private Site site(String name) throws Exception {
        String email = name + "@example.test";
        Tenant tenant = tenants.save(Tenant.builder().name(name).email(email).phone("+256700000020")
                .passwordHash(passwords.encode("testing-password")).role("ROLE_ADMIN").status("ACTIVE").build());
        subscriptions.grantTrialIfEligible(tenant);
        String jwt = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"testing-password\"}")).andReturn().getResponse().getContentAsString(), "$.token");
        Number routerId = JsonPath.read(mvc.perform(post("/api/routers").header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + " router\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String token = JsonPath.read(mvc.perform(get("/api/routers/" + routerId + "/portal-id").header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getContentAsString(), "$.portalId");
        return new Site(jwt, token);
    }

    private ResultActions upload(Site s, String kind, byte[] data, String declaredType) throws Exception {
        return mvc.perform(multipart("/api/settings/assets/" + kind)
                .file(new MockMultipartFile("file", "image", declaredType, data))
                .header("Authorization", "Bearer " + s.jwt()));
    }

    private String configUrl(Site s, String field) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/portal/config").param("token", s.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.settings." + field);
    }

    /** The served path of a config URL (the base URL is an external host in tests). */
    private static String path(String url) {
        return url.substring(url.indexOf("/api/portal/assets/"));
    }

    @Test
    void uploadedLogoIsServedToThePortalAndReplacingItChangesTheUrl() throws Exception {
        Site shop = site("logo-shop");

        upload(shop, "logo", PNG, "image/png").andExpect(status().isOk()).andExpect(jsonPath("$.hasLogo").value(true));
        String first = configUrl(shop, "logoUrl");
        assertTrue(first.startsWith("http://localhost:8080/api/portal/assets/" + shop.token() + "/logo?v=logo-"), first);

        byte[] served = mvc.perform(get(path(first)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age")))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(PNG, served);

        upload(shop, "logo", PNG2, "image/png").andExpect(status().isOk());
        String second = configUrl(shop, "logoUrl");
        assertNotEquals(first, second, "a replaced image gets a new URL, so phones don't keep a cached old one");
        assertArrayEquals(PNG2, mvc.perform(get(path(second))).andReturn().getResponse().getContentAsByteArray());

        mvc.perform(delete("/api/settings/assets/logo").header("Authorization", "Bearer " + shop.jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasLogo").value(false));
        assertNull(JsonPath.read(mvc.perform(get("/api/portal/config").param("token", shop.token()))
                .andReturn().getResponse().getContentAsString(), "$.settings.logoUrl"));
        mvc.perform(get(path(second))).andExpect(status().isNotFound());
    }

    @Test
    void backgroundAcceptsPhotosAndRejectsSvgAndNonImages() throws Exception {
        Site shop = site("bg-shop");

        upload(shop, "background", "not an image".getBytes(StandardCharsets.UTF_8), "image/png").andExpect(status().isBadRequest());
        upload(shop, "background", SVG, "image/svg+xml").andExpect(status().isBadRequest());

        // The real type wins over the browser-reported one.
        upload(shop, "background", JPEG, "image/png").andExpect(status().isOk()).andExpect(jsonPath("$.hasBackground").value(true));
        mvc.perform(get(path(configUrl(shop, "backgroundUrl"))))
                .andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
    }

    @Test
    void svgLogosAreSandboxedAndScriptedOnesRefused() throws Exception {
        Site shop = site("svg-shop");

        upload(shop, "logo", EVIL_SVG, "image/svg+xml").andExpect(status().isBadRequest());
        upload(shop, "logo", SVG, "image/svg+xml").andExpect(status().isOk());
        mvc.perform(get(path(configUrl(shop, "logoUrl"))))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/svg+xml"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("sandbox")));
    }

    @Test
    void oneBusinessCannotReadAnothersImagesAndOversizedFilesAreRefused() throws Exception {
        Site a = site("iso-a");
        Site b = site("iso-b");
        upload(a, "logo", PNG, "image/png").andExpect(status().isOk());

        mvc.perform(get("/api/portal/assets/" + b.token() + "/logo")).andExpect(status().isNotFound());
        mvc.perform(get("/api/portal/assets/" + a.token() + "/passwords")).andExpect(status().isNotFound());

        byte[] big = Arrays.copyOf(PNG, 1_000_001);
        upload(a, "logo", big, "image/png").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("too large")));
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) b[i] = (byte) values[i];
        return b;
    }
}
