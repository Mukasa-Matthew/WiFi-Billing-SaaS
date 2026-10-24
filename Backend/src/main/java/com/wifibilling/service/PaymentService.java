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
    private final GatewayConfigService gatewayConfig;
    private final com.wifibilling.service.payment.MarzPayClient marzPay;
    private final WalletService walletService;
    private final com.wifibilling.config.PlatformProperties platformProperties;

    /**
     * Initiates a Mobile Money payment collection for a chosen package on a router.
     * Selects the tenant's active gateway (platform or own credentials) and freezes
     * that choice on the transaction so a later switch never rewrites history.
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

        String mode = gatewayConfig.modeFor(router.getTenant());
        String reference = "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

        String network = detectNetwork(req.phone(), req.network());
        String phoneE164 = toE164(req.phone());

        Transaction transaction = Transaction.builder()
                .tenant(router.getTenant())
                .packagePlan(packagePlan)
                .router(router)
                .reference(reference)
                .phone(phoneE164)
                .amount(packagePlan.getPrice())
                .network(network)
                .status(STATUS_PENDING)
                .gatewayMode(mode)
                .createdAt(OffsetDateTime.now())
                .build();

        transaction = transactionRepository.save(transaction);

        // Dispatch the collection through the tenant's active gateway.
        if (useMockGateway(mode)) {
            String extRef = gatewayClient.requestPayment(transaction);
            if (extRef != null) {
                transaction.setExternalReference(extRef);
                transactionRepository.save(transaction);
            }
        } else {
            dispatchMarzPayCollection(transaction, router, mode);
        }

        log.info("Initiated transaction ref: {} for amount: {} UGX via {} gateway={} (router: {})",
                reference, packagePlan.getPrice(), network, mode, router.getId());

        return new PaymentDto.InitiateResponse(
                reference,
                STATUS_PENDING,
                packagePlan.getPrice(),
                "Payment prompt sent to " + phoneE164 + ". Please approve on your phone."
        );
    }

    /** Dev fallback: the platform gateway has no credentials configured (sandbox/dev),
     *  so the existing mock gateway simulates the provider. Own-gateway mode always
     *  uses real MarzPay credentials. */
    private boolean useMockGateway(String mode) {
        return com.wifibilling.model.TenantGatewayConfig.MODE_PLATFORM.equals(mode)
                && !gatewayConfig.platformConfigured();
    }

    private void dispatchMarzPayCollection(Transaction tx, Router router, String mode) {
        var creds = gatewayConfig.credentialsFor(router.getTenant(), mode);
        String collectRef = UUID.randomUUID().toString(); // MarzPay requires UUID v4
        String callbackUrl = platformProperties.getPublicBaseUrl() + "/api/payments/marzpay/callback";
        try {
            var res = marzPay.collectMoney(creds,
                    tx.getAmount().longValue(), tx.getPhone(), collectRef,
                    gatewayConfig.countryFor(router.getTenant(), mode),
                    "WiFi " + tx.getPackagePlan().getName(), callbackUrl);
            tx.setExternalReference(collectRef);
            var txn = res.path("data").path("transaction");
            if (!txn.path("uuid").isMissingNode() && !txn.path("uuid").isNull()) {
                tx.setProviderUuid(txn.path("uuid").asText());
            }
            transactionRepository.save(tx);
        } catch (Exception e) {
            tx.setStatus(STATUS_FAILED);
            tx.setCompletedAt(OffsetDateTime.now());
            tx.setNotes("Collection initiation failed: " + e.getMessage());
            transactionRepository.save(tx);
            log.error("MarzPay collection failed for ref {}: {}", tx.getReference(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Payment could not be initiated. Please try again.");
        }
    }

    /** Normalize any local phone format to E.164 (+256…). */
    private String toE164(String phone) {
        String clean = phone.replaceAll("[^0-9+]", "");
        if (clean.startsWith("+")) return clean;
        if (clean.startsWith("256")) return "+" + clean;
        if (clean.startsWith("0")) return "+256" + clean.substring(1);
        return "+256" + clean;
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
            if (webhook.externalReference() != null) {
                tx.setExternalReference(webhook.externalReference());
            }
            confirmSuccess(tx, null, null, null);
        } else {
            tx.setStatus(STATUS_FAILED);
            tx.setCompletedAt(OffsetDateTime.now());
            transactionRepository.save(tx);
            log.info("Payment FAILED for ref: {}, reason: {}", tx.getReference(), webhook.failureReason());
        }
    }

    /**
     * Shared successful-payment finalization, used by mock webhooks, MarzPay
     * callbacks, and reconciliation. Idempotent: a transaction that already has a
     * voucher never generates another, and the wallet credit is guarded by a
     * unique ledger externalRef — a replayed callback can never double-credit.
     */
    private void confirmSuccess(Transaction tx, Long gatewayFee, Long netAmount, String providerTxId) {
        tx.setStatus(STATUS_SUCCESSFUL);
        tx.setCompletedAt(OffsetDateTime.now());
        if (gatewayFee != null) tx.setGatewayFee(gatewayFee);
        if (netAmount != null) tx.setNetAmount(netAmount);
        if (providerTxId != null) tx.setProviderTransactionId(providerTxId);

        // Provision a single voucher for this tenant and package — exactly once.
        if (tx.getVoucherCode() == null) {
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
        }
        transactionRepository.save(tx);

        // Platform-gateway collections credit the tenant's internal wallet at the
        // provider-reported net. Own-gateway money settles to the tenant's MarzPay
        // account directly and must never touch our wallet ledger.
        if (com.wifibilling.model.TenantGatewayConfig.MODE_PLATFORM.equals(tx.getGatewayMode())) {
            long net = netAmount != null ? netAmount : tx.getAmount().longValue();
            walletService.creditCollection(tx.getTenant().getId(), net,
                    "COL:" + tx.getReference(), tx.getId(),
                    "Collection " + tx.getReference());
            if (gatewayFee != null && gatewayFee > 0) {
                walletService.recordFee(tx.getTenant().getId(), gatewayFee,
                        "FEE:" + tx.getReference(), tx.getId());
            }
        }
    }

    /**
     * Verified MarzPay callback/reconciliation for a collection. Looks up our
     * transaction by the UUID we sent (`externalReference`) or by internal ref,
     * applies the final status, and confirms via the shared path.
     */
    @Transactional
    public void processMarzPayCollection(tools.jackson.databind.JsonNode payload) {
        var txn = payload.path("transaction");
        String ref = txn.path("reference").asText(null);
        String status = txn.path("status").asText("").toLowerCase();
        String uuid = txn.path("uuid").asText(null);
        if (ref == null) {
            log.warn("MarzPay collection callback without reference — ignored");
            return;
        }
        Transaction tx = transactionRepository.findByExternalReference(ref)
                .or(() -> transactionRepository.findByReference(ref))
                .orElse(null);
        if (tx == null) {
            log.warn("MarzPay callback for unknown reference {} — ignored", ref);
            return;
        }
        if (!STATUS_PENDING.equalsIgnoreCase(tx.getStatus())) {
            log.info("MarzPay callback for {} already in status {} — idempotent skip", ref, tx.getStatus());
            return;
        }
        if (uuid != null && !uuid.isBlank()) tx.setProviderUuid(uuid);

        if ("completed".equals(status)) {
            var money = txn.path("net_amount");
            Long net = money.path("raw").isNumber() ? money.path("raw").longValue() : null;
            var charge = txn.path("charge");
            Long fee = charge.path("raw").isNumber() ? charge.path("raw").longValue() : null;
            String providerTx = payload.path("collection").path("provider_transaction_id").asText(null);
            confirmSuccess(tx, fee, net, providerTx);
        } else if ("failed".equals(status) || "cancelled".equals(status) || "expired".equals(status)) {
            tx.setStatus(STATUS_FAILED);
            tx.setCompletedAt(OffsetDateTime.now());
            tx.setNotes("Provider status: " + status);
            transactionRepository.save(tx);
        }
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
