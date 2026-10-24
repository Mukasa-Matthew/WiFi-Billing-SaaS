package com.wifibilling.service;

import com.wifibilling.dto.GenerateVouchersRequest;
import com.wifibilling.dto.PaymentDto;
import com.wifibilling.dto.VoucherResponse;
import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Router;
import com.wifibilling.model.Transaction;
import com.wifibilling.repository.PackagePlanRepository;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.service.payment.PaymentGatewayClient;
import com.wifibilling.util.RouterTokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUCCESSFUL = "SUCCESSFUL";
    public static final String STATUS_FAILED = "FAILED";

    private final TransactionRepository transactionRepository;
    private final RouterRepository routerRepository;
    private final PackagePlanRepository packagePlanRepository;
    private final VoucherService voucherService;
    private final PaymentGatewayClient gatewayClient;

    /**
     * Initiates a Mobile Money payment collection for a chosen package on a router.
     */
    @Transactional
    public PaymentDto.InitiateResponse initiatePayment(PaymentDto.InitiateRequest req) {
        if (!RouterTokens.isValid(req.token())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found");
        }

        Router router = routerRepository.findByToken(req.token())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Router not found"));

        PackagePlan packagePlan = packagePlanRepository.findByIdAndTenantId(req.packageId(), router.getTenant().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found for router tenant"));

        if (!"ACTIVE".equals(packagePlan.effectiveAvailability())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Package is unavailable for purchase");
        }
        if (packagePlan.getRouter() != null && !packagePlan.getRouter().getId().equals(router.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found for router");
        }

        String reference = "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

        String network = detectNetwork(req.phone(), req.network());

        Transaction transaction = Transaction.builder()
                .tenant(router.getTenant())
                .packagePlan(packagePlan)
                .router(router)
                .reference(reference)
                .phone(req.phone().trim())
                .amount(packagePlan.getPrice())
                .network(network)
                .status(STATUS_PENDING)
                .createdAt(OffsetDateTime.now())
                .build();

        transaction = transactionRepository.save(transaction);

        // Dispatch STK / USSD prompt via gateway client
        String extRef = gatewayClient.requestPayment(transaction);
        if (extRef != null) {
            transaction.setExternalReference(extRef);
            transactionRepository.save(transaction);
        }

        log.info("Initiated transaction ref: {} for amount: {} UGX via {} (router: {})",
                reference, packagePlan.getPrice(), network, router.getId());

        return new PaymentDto.InitiateResponse(
                reference,
                STATUS_PENDING,
                packagePlan.getPrice(),
                "Payment prompt sent to " + req.phone() + ". Please approve on your phone."
        );
    }

    /**
     * Webhook notification from payment provider.
     * When payment succeeds, generates a voucher and links it to the transaction.
     */
    @Transactional
    public void processWebhook(PaymentDto.WebhookRequest webhook) {
        Transaction tx = transactionRepository.findByReference(webhook.reference())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found: " + webhook.reference()));

        if (!STATUS_PENDING.equalsIgnoreCase(tx.getStatus())) {
            log.warn("Transaction {} is already in status: {}", tx.getReference(), tx.getStatus());
            return;
        }

        if (STATUS_SUCCESSFUL.equalsIgnoreCase(webhook.status())) {
            tx.setStatus(STATUS_SUCCESSFUL);
            tx.setCompletedAt(OffsetDateTime.now());
            if (webhook.externalReference() != null) {
                tx.setExternalReference(webhook.externalReference());
            }

            // Provision a single voucher for this tenant and package
            List<VoucherResponse> vouchers = voucherService.generate(
                    tx.getTenant().getId(),
                    new GenerateVouchersRequest(tx.getPackagePlan().getId(), 1)
            );

            if (!vouchers.isEmpty()) {
                String voucherCode = vouchers.get(0).code();
                tx.setVoucherCode(voucherCode);
                voucherService.recordOnlineSale(tx.getTenant().getId(), voucherCode);
                log.info("Payment SUCCESSFUL for ref {}. Provisioned voucher: {}", tx.getReference(), voucherCode);
            } else {
                log.error("Failed to generate voucher for successful transaction ref {}", tx.getReference());
            }
        } else {
            tx.setStatus(STATUS_FAILED);
            tx.setCompletedAt(OffsetDateTime.now());
            log.info("Payment FAILED for ref: {}, reason: {}", tx.getReference(), webhook.failureReason());
        }

        transactionRepository.save(tx);
    }

    /**
     * Polling endpoint used by the captive portal front-end to detect payment completion.
     */
    @Transactional(readOnly = true)
    public PaymentDto.StatusResponse getStatus(String reference) {
        Transaction tx = transactionRepository.findByReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));

        return new PaymentDto.StatusResponse(
                tx.getReference(),
                tx.getStatus(),
                tx.getVoucherCode(),
                tx.getAmount(),
                tx.getCreatedAt(),
                tx.getCompletedAt()
        );
    }

    private String detectNetwork(String phone, String hint) {
        if (hint != null && !hint.isBlank()) {
            return hint.trim().toUpperCase();
        }
        String clean = phone.replaceAll("[^0-9]", "");
        if (clean.startsWith("256")) clean = clean.substring(3);
        else if (clean.startsWith("0")) clean = clean.substring(1);

        if (clean.startsWith("77") || clean.startsWith("78") || clean.startsWith("76")) {
            return "MTN";
        } else if (clean.startsWith("70") || clean.startsWith("75") || clean.startsWith("74")) {
            return "AIRTEL";
        }
        return "UNKNOWN";
    }
}
