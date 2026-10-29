package com.wifibilling.service;

import com.wifibilling.model.*;
import com.wifibilling.repository.*;
import com.wifibilling.security.TenantPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:winbox;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=winbox-tests-not-a-production-signing-key", "winbox.enabled=true", "winbox.public-host=remote.invalid",
        "winbox.hub-ip=10.9.0.1", "provisioning.public-base-url=https://app.invalid", "wireguard.server.subnet=10.9.0.0/16"
})
@Transactional
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class WinboxAccessServiceTest {
    static final String GATEWAY = UUID.randomUUID() + "-" + UUID.randomUUID();
    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        registry.add("app.encryption-key", () -> Base64.getEncoder().encodeToString(key));
        registry.add("winbox.gateway-token", () -> GATEWAY);
    }
    @Autowired WinboxAccessService service;
    @Autowired WinboxAccessRepository accesses;
    @Autowired RouterRepository routers;
    @Autowired TenantRepository tenants;
    @Autowired AuditLogRepository audit;
    @Autowired EncryptionService encryption;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.wifibilling.controller.RouterController routerController;
    @Autowired com.wifibilling.config.WinboxProperties config;
    @Autowired RouterSetupService setups;
    Router router;
    TenantPrincipal owner;

    @BeforeEach
    void create() {
        Tenant tenant = tenants.save(Tenant.builder().name("Fixture").email(UUID.randomUUID() + "@example.test")
                .phone("+256700000001").passwordHash("not-a-login-password").role("ROLE_ADMIN").status("ACTIVE").build());
        byte[] peer = new byte[32]; new java.security.SecureRandom().nextBytes(peer);
        router = routers.saveAndFlush(Router.builder().tenant(tenant).name("Fixture router").token("tok_" + UUID.randomUUID().toString().replace("-", ""))
                .wgIp("10.9.0." + (tenant.getId() + 1)).wgPublicKey(Base64.getEncoder().encodeToString(peer)).radiusSecret("").status("online").build());
        owner = new TenantPrincipal(tenant.getId(), tenant.getEmail(), "Owner", "ROLE_ADMIN", false);
    }

    WinboxAccess enable() {
        service.change(owner, router.getId(), "enable", "127.0.0.1");
        return accesses.findByRouterId(router.getId()).orElseThrow();
    }

    String install() {
        var issued = service.installation(owner, router.getId(), "127.0.0.1");
        service.installer(issued.path().split("/")[4]);
        return encryption.decrypt(accesses.findByRouterId(router.getId()).orElseThrow().getAgentCiphertext());
    }

    void ready(String agent) {
        var a = accesses.findByRouterId(router.getId()).orElseThrow();
        service.acknowledge(router.getId(), agent, a.getRevision(), true);
        service.checked(GATEWAY, List.of(new WinboxAccessService.Check(router.getId(), a.getRevision(), "READY")));
    }

    @Test
    void winboxInstallationCannotRevokeOrAuthorizeWireGuardEnrollment() {
        var full = setups.issue(router, RouterSetupCode.Purpose.FULL);
        enable();
        var winbox = service.installation(owner, router.getId(), "127.0.0.1").path().split("/")[4];
        assertThatThrownBy(() -> setups.authorizeCallback(winbox)).isInstanceOf(ResponseStatusException.class);
        assertThat(setups.redeem(full.code(), RouterSetupCode.Purpose.FULL)).isEqualTo(router.getId());
        assertThat(service.installer(winbox)).contains("check-certificate=yes");
    }

    @Test
    void routerScriptsRestrictAccountAndTunnelWithoutChangingBillingCredentials() {
        enable(); String agent = install();
        String script = service.agentConfig(router.getId(), agent);
        assertThat(script).contains("policy=local,telnet,ssh,ftp,reboot,read,write,policy,test,winbox,password,web,sniff,sensitive,api,romon,rest-api", "in-interface=wg-billing", "dst-port=8291", "10.9.0.1/32", "Dedicated account name collision", "apWinboxRevision", "/ipv6/firewall/filter", "in-interface-list=!LAN");
        assertThat(script).doesNotContain("/radius/", "private-key", "/ip/service/set [find name=ssh]", "check-certificate=no");
        var a = accesses.findByRouterId(router.getId()).orElseThrow();
        service.acknowledge(router.getId(), agent, a.getRevision(), false);
        assertThat(service.routes(GATEWAY).routes()).isEmpty();
        assertThat(service.status(owner, router.getId(), "127.0.0.1").state()).isEqualTo("CONFIGURATION_FAILED");
    }

    @Test
    void infrastructureAndDedicatedEncryptionKeyAreRequired() {
        String key = config.getEncryptionKey();
        org.springframework.test.util.ReflectionTestUtils.setField(config, "encryptionKey", "");
        try {
            assertThatThrownBy(() -> enable()).isInstanceOf(ResponseStatusException.class);
            assertThat(service.status(owner, router.getId(), "127.0.0.1").state()).isEqualTo("UNAVAILABLE");
            assertThat(service.routes(GATEWAY).routes()).isEmpty();
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(config, "encryptionKey", key); }
    }

    @Test
    void httpAuthorizationAndNoStoreHeadersProtectCredentialRoutes() throws Exception {
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities());
        var other = new TenantPrincipal(owner.getTenantId() + 100, "other@example.test", "Other", "ROLE_ADMIN", false);
        var outsider = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(other, null, other.getAuthorities());
        String path = "/api/routers/" + router.getId() + "/winbox";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(outsider)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/noc/routers/" + router.getId() + "/winbox")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/internal/winbox/routes"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/router/winbox/" + router.getId() + "/config.rsc"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }

    @Test
    void enableNeedsRealRouterAckAndGatewayCheckBeforeCredentialsOrAddressAreReleased() {
        var a = enable();
        assertThat(service.status(owner, router.getId(), "127.0.0.1").state()).isEqualTo("INSTALL_REQUIRED");
        assertThat(service.status(owner, router.getId(), "127.0.0.1").address()).isNull();
        assertThatThrownBy(() -> service.credentials(owner, router.getId(), "127.0.0.1")).isInstanceOf(ResponseStatusException.class);
        String agent = install();
        assertThat(service.routes(GATEWAY).routes()).isEmpty();
        ready(agent);
        var credentials = service.credentials(owner, router.getId(), "127.0.0.1");
        assertThat(credentials.password()).hasSize(43).isNotEqualTo(router.getRadiusSecret());
        assertThat(a.getPasswordCiphertext()).doesNotContain(credentials.password());
        assertThat(encryption.decrypt(a.getPasswordCiphertext())).isEqualTo(credentials.password());
        assertThat(service.status(owner, router.getId(), "127.0.0.1").address()).isEqualTo("remote.invalid:" + a.getPort());
        assertThat(service.routes(GATEWAY).routes()).singleElement().satisfies(r -> {
            assertThat(r.tunnelIp()).isEqualTo(router.getWgIp()); assertThat(r.peerKey()).isEqualTo(router.getWgPublicKey());
        });
        assertThat(audit.findAll()).allSatisfy(row -> assertThat(row.getDetails()).doesNotContain(credentials.password()).doesNotContain(agent));
    }

    @Test
    void rotationWithdrawsMappingUntilNewRevisionAppliesAndDisableRevokesSecrets() {
        enable(); String agent = install(); ready(agent);
        var a = accesses.findByRouterId(router.getId()).orElseThrow();
        router = routers.findById(router.getId()).orElseThrow();
        String before = service.credentials(owner, router.getId(), "127.0.0.1").password(); long revision = a.getRevision();
        service.change(owner, router.getId(), "rotate", "127.0.0.1");
        assertThat(service.routes(GATEWAY).routes()).isEmpty();
        assertThat(encryption.decrypt(a.getPasswordCiphertext())).isNotEqualTo(before);
        assertThatThrownBy(() -> service.acknowledge(router.getId(), agent, revision, true)).isInstanceOf(ResponseStatusException.class);
        ready(agent);
        service.change(owner, router.getId(), "disable", "127.0.0.1");
        assertThat(service.routes(GATEWAY).routes()).isEmpty();
        assertThat(service.agentConfig(router.getId(), agent)).contains("disabled=yes").doesNotContain(encryption.decrypt(a.getPasswordCiphertext()));
        assertThatThrownBy(() -> service.credentials(owner, router.getId(), "127.0.0.1")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void publicRouterTokenCannotAuthenticateWinboxAgentAndInstallerIsSingleUse() {
        enable(); var code = service.installation(owner, router.getId(), "127.0.0.1").path().split("/")[4];
        String installer = service.installer(code);
        assertThat(installer).contains("check-certificate=yes").doesNotContain("check-certificate=no");
        assertThatThrownBy(() -> service.installer(code)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.agentConfig(router.getId(), router.getToken())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.routes(router.getToken())).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void suspensionAndReassignmentWithdrawEndpointsAndRequireExplicitReenable() {
        enable(); String agent = install(); ready(agent);
        var a = accesses.findByRouterId(router.getId()).orElseThrow();
        router = routers.findById(router.getId()).orElseThrow();
        service.revokeTenant(owner.getTenantId());
        assertThat(service.routes(GATEWAY).routes()).isEmpty(); assertThat(a.isEnabled()).isFalse();
        service.change(owner, router.getId(), "enable", "127.0.0.1"); ready(agent);
        router.getTenant().setStatus("SUSPENDED");
        assertThat(service.routes(GATEWAY).routes()).isEmpty(); assertThat(a.isEnabled()).isFalse();
        router.getTenant().setStatus("ACTIVE");
        assertThat(service.routes(GATEWAY).routes()).isEmpty();
        router.setWgIp("10.9.0.99");
        assertThat(service.change(owner, router.getId(), "enable", "127.0.0.1").state()).isEqualTo("INSTALL_REQUIRED");
        assertThat(accesses.findByRouterId(router.getId()).orElseThrow().getPort()).isNotEqualTo(a.getPort());
        assertThatThrownBy(() -> service.agentConfig(router.getId(), agent)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void tenantIsolationSuperadminAuditAndUniquePorts() {
        enable();
        var outsider = new TenantPrincipal(owner.getTenantId() + 100, "other@example.test", "Other", "ROLE_ADMIN", false);
        assertThatThrownBy(() -> service.status(outsider, router.getId(), "127.0.0.1")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.change(outsider, router.getId(), "enable", "127.0.0.1")).isInstanceOf(ResponseStatusException.class);
        var admin = new TenantPrincipal(owner.getTenantId() + 100, "root@example.test", "Root", "ROLE_SUPERADMIN", false);
        service.status(admin, router.getId(), "127.0.0.1");
        assertThat(audit.findAll()).anySatisfy(row -> assertThat(row.getActorEmail()).isEqualTo("root@example.test"));
        int first = accesses.findByRouterId(router.getId()).orElseThrow().getPort();
        create();
        assertThat(enable().getPort()).isNotEqualTo(first).isBetween(30000, 39999);
    }

    @Test
    void hardRemovalCascadesAccessRecordAndFailedOrStaleChecksNeverReleaseCredentials() {
        enable(); String agent = install(); ready(agent);
        var a = accesses.findByRouterId(router.getId()).orElseThrow();
        router = routers.findById(router.getId()).orElseThrow();
        a.setGatewayCheckedAt(java.time.OffsetDateTime.now().minusSeconds(16));
        assertThat(service.status(owner, router.getId(), "127.0.0.1").state()).isEqualTo("PENDING");
        service.checked(GATEWAY, List.of(new WinboxAccessService.Check(router.getId(), a.getRevision(), "OFFLINE")));
        assertThat(service.status(owner, router.getId(), "127.0.0.1").state()).isEqualTo("OFFLINE");
        routerController.delete(owner, router.getId());
        assertThat(accesses.findByRouterId(router.getId())).isEmpty();
    }
}
