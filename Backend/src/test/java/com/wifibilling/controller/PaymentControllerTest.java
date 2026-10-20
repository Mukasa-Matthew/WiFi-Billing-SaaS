package com.wifibilling.controller;

import com.wifibilling.dto.PaymentDto;
import com.wifibilling.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock
    private PaymentService paymentService;

    private PaymentController controller;

    @BeforeEach
    void setUp() {
        controller = new PaymentController(paymentService);
    }

    @Test
    void initiate_DelegatesToService() {
        PaymentDto.InitiateRequest req = new PaymentDto.InitiateRequest("tok_12345678", 1L, "0771234567", "MTN");
        PaymentDto.InitiateResponse expected = new PaymentDto.InitiateResponse("PAY-123", "PENDING", new BigDecimal("1000"), "Prompt sent");

        when(paymentService.initiatePayment(req)).thenReturn(expected);

        PaymentDto.InitiateResponse response = controller.initiate(req);
        assertEquals("PAY-123", response.reference());
        assertEquals("PENDING", response.status());
    }

    @Test
    void webhook_DelegatesToService() {
        PaymentDto.WebhookRequest req = new PaymentDto.WebhookRequest("PAY-123", "SUCCESSFUL", "EXT-123", null);
        ResponseEntity<Void> response = controller.webhook(req);

        assertEquals(200, response.getStatusCode().value());
        verify(paymentService).processWebhook(req);
    }

    @Test
    void getStatus_ReturnsStatus() {
        PaymentDto.StatusResponse expected = new PaymentDto.StatusResponse(
                "PAY-123", "SUCCESSFUL", "VOUCH-ABC", new BigDecimal("1000"),
                OffsetDateTime.now(), OffsetDateTime.now()
        );
        when(paymentService.getStatus("PAY-123")).thenReturn(expected);

        PaymentDto.StatusResponse actual = controller.getStatus("PAY-123");
        assertEquals("PAY-123", actual.reference());
        assertEquals("SUCCESSFUL", actual.status());
        assertEquals("VOUCH-ABC", actual.voucherCode());
    }
}
