package com.wifibilling.controller;

import com.wifibilling.model.Transaction;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.security.TenantPrincipal;
import com.wifibilling.service.ReportingClock;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantPaymentsReportTest {

    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final ReportingClock clock = new ReportingClock(ZoneId.of("Africa/Kampala"),
            Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC));
    private final TenantPaymentsController controller = new TenantPaymentsController(transactions, clock);
    private final TenantPrincipal owner = new TenantPrincipal(7L, "owner@example.test", "Owner", "ROLE_ADMIN", false);

    private static Object[] fact(String status, long amount, String network, String gatewayMode) {
        return new Object[]{status, BigDecimal.valueOf(amount), network, "0772000000", gatewayMode};
    }

    @Test
    void mobileMoneyOnAnyNetworkIsNeverWalkIn() {
        TenantPaymentsController.RangeSummary s = TenantPaymentsController.summarize(List.of(
                fact("SUCCESSFUL", 1000, "MTN", "PLATFORM"),
                fact("SUCCESSFUL", 500, "MOMO", "PLATFORM"),   // other network, still mobile money
                fact("SUCCESSFUL", 2000, "CASH", null),        // walk-in
                fact("FAILED", 9999, "AIRTEL", "PLATFORM"),
                fact("PENDING", 9999, "MTN", "PLATFORM")));
        assertEquals(new BigDecimal("3500"), s.collected());
        assertEquals(new BigDecimal("1500"), s.mobileMoney());
        assertEquals(new BigDecimal("2000"), s.walkIn());
        assertEquals(5, s.total());
        assertEquals(3, s.successful());
        assertEquals(1, s.failed());
        assertEquals(1, s.pending());
    }

    @Test
    void reportUsesKampalaDayBoundariesAndIncludesTheEndDate() {
        when(transactions.findInRange(anyLong(), any(), any(), anyString(), any(Pageable.class))).thenReturn(Page.empty());
        when(transactions.rangeFacts(anyLong(), any(), any())).thenReturn(List.of());
        controller.report(owner, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 26), "successful", 0, 25);
        // Kampala is UTC+3: 1 Nov 00:00 local = 31 Oct 21:00 UTC; end is the start of 27 Nov local.
        verify(transactions).findInRange(eq(7L),
                eq(OffsetDateTime.of(2026, 10, 31, 21, 0, 0, 0, ZoneOffset.UTC)),
                eq(OffsetDateTime.of(2026, 11, 26, 21, 0, 0, 0, ZoneOffset.UTC)),
                eq("SUCCESSFUL"), any(Pageable.class));
    }

    @Test
    void badRangesAndStatusesAreRejected() {
        LocalDate d = LocalDate.of(2026, 11, 10);
        assertThrows(ResponseStatusException.class, () -> controller.report(owner, d, d.minusDays(1), "ALL", 0, 25));
        assertThrows(ResponseStatusException.class, () -> controller.report(owner, d.minusDays(400), d, "ALL", 0, 25));
        assertThrows(ResponseStatusException.class, () -> controller.report(owner, d, d, "DROP TABLE", 0, 25));
        verifyNoInteractions(transactions);
    }

    @Test
    void pageSizeIsClamped() {
        when(transactions.findInRange(anyLong(), any(), any(), anyString(), any(Pageable.class))).thenReturn(Page.empty());
        when(transactions.rangeFacts(anyLong(), any(), any())).thenReturn(List.of());
        LocalDate d = LocalDate.of(2026, 11, 10);
        controller.report(owner, d, d, "ALL", -3, 10_000);
        verify(transactions).findInRange(anyLong(), any(), any(), anyString(),
                argThat((Pageable p) -> p.getPageNumber() == 0 && p.getPageSize() == 200));
    }

    @Test
    void exportNeutralisesSpreadsheetFormulasAndQuotes() {
        assertEquals("\"'=HYPERLINK(1)\"", TenantPaymentsController.cell("=HYPERLINK(1)"));
        assertEquals("\"'-5\"", TenantPaymentsController.cell("-5"));
        assertEquals("\"say \"\"hi\"\"\"", TenantPaymentsController.cell("say \"hi\""));
        assertEquals("\"\"", TenantPaymentsController.cell(null));
    }

    @Test
    void exportRefusesRangesOverTheRowCap() {
        Page<Transaction> huge = new PageImpl<>(List.of(), Pageable.ofSize(1), TenantPaymentsController.MAX_EXPORT_ROWS + 1L);
        when(transactions.findInRange(anyLong(), any(), any(), anyString(), any(Pageable.class))).thenReturn(huge);
        LocalDate d = LocalDate.of(2026, 11, 10);
        assertThrows(ResponseStatusException.class, () -> controller.export(owner, d, d, "ALL"));
    }

    @Test
    void exportWritesAHeaderAndOneLinePerPayment() {
        Transaction t = Transaction.builder().reference("REF-1").status("SUCCESSFUL").amount(new BigDecimal("1000.00"))
                .phone("0772000000").network("MTN").gatewayMode("PLATFORM")
                .createdAt(OffsetDateTime.of(2026, 11, 10, 7, 30, 0, 0, ZoneOffset.UTC)).build();
        when(transactions.findInRange(anyLong(), any(), any(), anyString(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(t)));
        LocalDate d = LocalDate.of(2026, 11, 10);
        String body = controller.export(owner, d, d, "ALL").getBody();
        assertNotNull(body);
        String[] lines = body.replace("﻿", "").split("\r\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("Date,Reference,Status"));
        assertTrue(lines[1].startsWith("\"2026-11-10 10:30\",\"REF-1\",\"SUCCESSFUL\",\"MTN MoMo\",\"1000.00\""), lines[1]);
    }
}
