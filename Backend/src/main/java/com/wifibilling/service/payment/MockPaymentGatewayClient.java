package com.wifibilling.service.payment;

import com.wifibilling.dto.PaymentDto;
import com.wifibilling.model.Transaction;
import com.wifibilling.service.PaymentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class MockPaymentGatewayClient implements PaymentGatewayClient {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final int autoApproveSeconds;

    @Autowired
    @Lazy
    private PaymentService paymentService;

    public MockPaymentGatewayClient(
            @Value("${payment.gateway.mock.auto-approve-seconds:5}") int autoApproveSeconds) {
        this.autoApproveSeconds = autoApproveSeconds;
    }

    @Override
    public String requestPayment(Transaction transaction) {
        String mockExtRef = "MOCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[MOCK MoMo] Triggered USSD prompt for phone: {}, amount: {} UGX, ref: {}, mockExtRef: {}",
                transaction.getPhone(), transaction.getAmount(), transaction.getReference(), mockExtRef);

        // Simulate customer approving USSD prompt after configured delay
        scheduler.schedule(() -> {
            try {
                log.info("[MOCK MoMo] Auto-approving payment for reference: {}", transaction.getReference());
                paymentService.processWebhook(new PaymentDto.WebhookRequest(
                        transaction.getReference(),
                        "SUCCESSFUL",
                        mockExtRef,
                        null
                ));
            } catch (Exception e) {
                log.error("[MOCK MoMo] Error in auto-approval: {}", e.getMessage());
            }
        }, autoApproveSeconds, TimeUnit.SECONDS);

        return mockExtRef;
    }

    @Override
    public String getProviderName() {
        return "MOCK";
    }
}
