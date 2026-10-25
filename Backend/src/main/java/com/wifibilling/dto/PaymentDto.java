package com.wifibilling.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class PaymentDto {

    public record InitiateRequest(
            @NotBlank String token,
            @NotNull Long packageId,
            @NotBlank String phone,
            String network,
            /** Client-generated checkout idempotency key (UUID). Repeated submits
             *  of the same checkout resume the existing transaction. */
            String checkoutRef,
            /** MikroTik hotspot client MAC, echoed by the portal from $(mac). */
            String mac) {
    }

    public record InitiateResponse(
            String reference,
            String status,
            BigDecimal amount,
            String message) {
    }

    public record WebhookRequest(
            @NotBlank String reference,
            @NotBlank String status, // SUCCESSFUL, FAILED
            String externalReference,
            String failureReason) {
    }

    public record StatusResponse(
            String reference,
            String status,
            String voucherCode,
            BigDecimal amount,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt,
            String failureMessage) {
    }
}
