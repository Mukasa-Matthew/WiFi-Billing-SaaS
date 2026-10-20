package com.wifibilling.controller;

import com.wifibilling.dto.AdminTenantDto;
import com.wifibilling.model.Tenant;
import com.wifibilling.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminTenantControllerTest {

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private RouterRepository routerRepository;
    @Mock
    private VoucherRepository voucherRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private RadAcctRepository radAcctRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private AdminTenantController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminTenantController(
                tenantRepository,
                routerRepository,
                voucherRepository,
                transactionRepository,
                radAcctRepository,
                passwordEncoder
        );
    }

    @Test
    void createTenant_Success() {
        when(tenantRepository.existsByEmailIgnoreCase("newtenant@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("hashed_secret");
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> {
            Tenant t = i.getArgument(0);
            t.setId(10L);
            return t;
        });

        AdminTenantDto.CreateRequest req = new AdminTenantDto.CreateRequest(
                "New Cafe", "newtenant@example.com", "+256770000000", "secret123"
        );
        AdminTenantDto.SummaryResponse res = controller.createTenant(req);

        assertNotNull(res);
        assertEquals(10L, res.id());
        assertEquals("New Cafe", res.name());
        assertEquals("ROLE_ADMIN", res.role());
        assertEquals("ACTIVE", res.status());
    }

    @Test
    void createTenant_DuplicateEmailThrowsConflict() {
        when(tenantRepository.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);

        AdminTenantDto.CreateRequest req = new AdminTenantDto.CreateRequest(
                "Dup Cafe", "taken@example.com", "+256770000000", "secret123"
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.createTenant(req));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void updateTenantStatus_ModifiesStatus() {
        Tenant tenant = Tenant.builder().id(5L).name("Cafe").role("ROLE_ADMIN").status("ACTIVE").build();
        when(tenantRepository.findById(5L)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> i.getArgument(0));
        when(routerRepository.findAllByTenantId(5L)).thenReturn(Collections.emptyList());

        AdminTenantDto.SummaryResponse res = controller.updateTenantStatus(5L, new AdminTenantDto.UpdateStatusRequest("SUSPENDED"));
        assertEquals("SUSPENDED", res.status());
    }
}
