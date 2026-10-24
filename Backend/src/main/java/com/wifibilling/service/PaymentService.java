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

        String checkoutRef = sanitizeCheckoutRef(req.checkoutRef());
        if (checkoutRef != null) {
            var existing = transactionRepository.findByCheckoutRefForUpdate(checkoutRef);
            if (existing.isPresent()) {
                return resumeCheckout(existing.get(), req);
            }
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
                .checkoutRef(checkoutRef)
                .clientMac(sanitizeMac(req.mac()))
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

    /**
     * Re-entry point for a checkout the client already started. Never creates a
     * second transaction or provider collection for the same checkout:
     *   - SUCCESSFUL → report it so the portal can auto-connect immediately
     *   - FAILED     → report it; the portal discards the checkout and starts fresh
     *   - PENDING    → recheck the provider; if the collection provably never
     *                  arrived (404), redispatch once for the same checkout.
     */
    private PaymentDto.InitiateResponse resumeCheckout(Transaction tx, PaymentDto.InitiateRequest req) {
        if (!STATUS_PENDING.equals(tx.getStatus())) {
            return new PaymentDto.InitiateResponse(tx.getReference(), tx.getStatus(), tx.getAmount(),
                    STATUS_SUCCESSFUL.equals(tx.getStatus())
                            ? "Payment already confirmed."
                            : "This checkout already failed. Start a new payment.");
        }
        boolean sameCheckout = tx.getPackagePlan().getId().equals(req.packageId())
                && tx.getPhone().equals(toE164(req.phone()));
        if (!sameCheckout) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Checkout already in progress with different details");
        }
        recheckProvider(tx);
        if (STATUS_PENDING.equals(tx.getStatus()) && tx.getExternalReference() == null
                && !useMockGateway(tx.getGatewayMode())) {
            dispatchMarzPayCollection(tx, tx.getRouter(), tx.getGatewayMode());
        }
        return new PaymentDto.InitiateResponse(tx.getReference(), tx.getStatus(), tx.getAmount(),
                "Payment already in progress — resuming.");
    }

    private static String sanitizeCheckoutRef(String ref) {
        if (ref == null) return null;
        String clean = ref.trim();
        if (clean.isEmpty() || clean.length() > 64 || !clean.matches("[A-Za-z0-9_-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid checkout reference");
        }
        return clean;
    }

    private static String sanitizeMac(String mac) {
        if (mac == null || mac.isBlank()) return null;
        String clean = mac.trim().toUpperCase().replace('-', ':');
        if (!clean.matches("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")) return null;
        return clean;
    }

    /**
     * Server-side provider recheck for a pending transaction whose provider
     * reference we hold. Feeds the authoritative GET /collect-money/{uuid}
     * response through the same confirmation path as callbacks. Best-effort:
     * any failure leaves the transaction PENDING for the next poll/webhook.
     */
    private void recheckProvider(Transaction tx) {
        String extRef = tx.getExternalReference();
        if (extRef == null || useMockGateway(tx.getGatewayMode())) return;
        try {
            var creds = gatewayConfig.credentialsFor(tx.getTenant(), tx.getGatewayMode());
            var status = marzPay.getCollection(creds, extRef);
            var payload = status.has("data") && status.get("data").isObject() ? status.get("data") : status;
            processMarzPayCollection(payload);
        } catch (com.wifibilling.service.payment.MarzPayClient.MarzPayException e) {
            if (e.getStatus() == 404) {
                // The provider has no record of this reference — the original
                // dispatch provably never arrived, so one redispatch is safe.
                log.info("Provider 404 for {} — original collection never arrived; redispatching", extRef);
                tx.setExternalReference(null);
                transactionRepository.save(tx);
                dispatchMarzPayCollection(tx, tx.getRouter(), tx.getGatewayMode());
            } else {
                log.warn("Provider recheck for {} failed: {}", tx.getReference(), e.getMessage());
            }
        } catch (Exception e) {
            log.warn("Provider recheck for {} failed: {}", tx.getReference(), e.getMessage());
        }
    }

    /** Dev fallback: the platform gateway has no credentials configured (sandbox/dev),
     *  so the existing mock gateway simulates the provider. Own-gateway mode always
     *  uses real MarzPay credentials. */
    private boolean useMockGateway(String mode) {
        return com.wifibilling.model.TenantGatewayConfig.MODE_PLATFORM.equals(mode)
                && !gatewayConfig.platformConfigured();
    }

    /**
     * Dispatches one MarzPay collection for a transaction. The UUID reference is
     * persisted BEFORE the HTTP call so that a lost response can never trigger a
     * second collection: every later recheck verifies /collect-money/{uuid} with
     * the same reference instead of charging again. Any dispatch error leaves the
     * transaction PENDING (outcome uncertain) — never FAILED — and the
     * provider recheck in resume/status polling resolves it.
     */
    private void dispatchMarzPayCollection(Transaction tx, Router router, String mode) {
        var creds = gatewayConfig.credentialsFor(router.getTenant(), mode);
        String collectRef = UUID.randomUUID().toString(); // MarzPay requires UUID v4
        String callbackUrl = platformProperties.getPublicBaseUrl() + "/api/payments/marzpay/callback";
        tx.setExternalReference(collectRef);
        transactionRepository.save(tx);
        try {
            var res = marzPay.collectMoney(creds,
                    tx.getAmount().longValue(), tx.getPhone(), collectRef,
                    gatewayConfig.countryFor(router.getTenant(), mode),
                    "WiFi " + tx.getPackagePlan().getName(), callbackUrl);
            var txn = res.path("data").path("transaction");
            if (!txn.path("uuid").isMissingNode() && !txn.path("uuid").isNull()) {
                tx.setProviderUuid(txn.path("uuid").asText());
            }
            transactionRepository.save(tx);
        } catch (Exception e) {
            // Uncertain outcome — MarzPay may have received the collection. Keep
            // the reference and stay PENDING; recheckProvider() resolves it.
            tx.setNotes("Collection dispatch uncertain: " + e.getMessage());
            transactionRepository.save(tx);
            log.error("MarzPay collection dispatch uncertain for ref {} (provider ref {}): {}",
                    tx.getReference(), collectRef, e.getMessage());
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
        Transaction tx = transactionRepository.findByReferenceForUpdate(webhook.reference())
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
                    new GenerateVouchersRequest(tx.getPackagePlan().getId(), 1),
                    tx.getClientMac()
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
        Transaction tx = transactionRepository.findByExternalReferenceForUpdate(ref)
                .or(() -> transactionRepository.findByReferenceForUpdate(ref))
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
    @Transactional
    public PaymentDto.StatusResponse getStatus(String reference) {
        Transaction tx = transactionRepository.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));

        // If a callback was missed or delayed, actively verify with the provider
        // once the transaction is old enough to reasonably have resolved. This
        // also resolves dispatch-uncertain transactions without a new charge.
        if (STATUS_PENDING.equals(tx.getStatus())
                && tx.getCreatedAt() != null
                && tx.getCreatedAt().isBefore(OffsetDateTime.now().minusSeconds(10))) {
            recheckProvider(tx);
            tx = transactionRepository.findByReferenceForUpdate(reference)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
        }

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
