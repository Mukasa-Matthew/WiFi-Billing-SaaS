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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUCCESSFUL = "SUCCESSFUL";
    public static final String STATUS_FAILED = "FAILED";

    /** Max payment initiations per phone per router per rolling window. */
    private static final int INITIATE_RATE_LIMIT = 5;
    private static final long INITIATE_RATE_WINDOW_SECONDS = 600;
    /** Derived checkout keys dedupe identical checkouts within this bucket. */
    private static final long AUTO_CHECKOUT_BUCKET_SECONDS = 600;
    /** Don't recheck the provider more often than this per transaction. */
    private static final long RECHECK_INTERVAL_SECONDS = 15;
    /** Stop actively rechecking after this many attempts — flagged for review. */
    private static final int MAX_RECHECKS = 40;

    private final TransactionRepository transactionRepository;
    private final RouterRepository routerRepository;
    private final PackagePlanRepository packagePlanRepository;
    private final VoucherService voucherService;
    private final PaymentGatewayClient gatewayClient;
    private final GatewayConfigService gatewayConfig;
    private final com.wifibilling.service.payment.MarzPayClient marzPay;
    private final WalletService walletService;
    private final com.wifibilling.config.PlatformProperties platformProperties;

    // Lazy self-injection so internal calls still go through the tx proxy.
    // Defaults to the raw instance so plain unit tests (no proxy) still work.
    private PaymentService self = this;

    @org.springframework.beans.factory.annotation.Autowired
    void setSelf(@org.springframework.context.annotation.Lazy PaymentService self) {
        this.self = self;
    }

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

        // A client-supplied checkout reference resolves first — resuming an
        // existing checkout must not revalidate package availability.
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

        String phoneE164 = toE164(req.phone());

        // Keyless clients: derive a stable key from
        // (router, phone, package, 10-min bucket) so they dedupe too.
        boolean derivedRef = checkoutRef == null;
        if (derivedRef) {
            checkoutRef = "AUTO-" + sha256(router.getId() + "|" + phoneE164 + "|" + req.packageId()
                        + "|" + (System.currentTimeMillis() / (AUTO_CHECKOUT_BUCKET_SECONDS * 1000))).substring(0, 40);
            var existing = transactionRepository.findByCheckoutRefForUpdate(checkoutRef);
            if (existing.isPresent()) {
                Transaction tx = existing.get();
                if (STATUS_PENDING.equals(tx.getStatus())) {
                    return resumeCheckout(tx, req);
                }
                if (STATUS_FAILED.equals(tx.getStatus())) {
                    // Legitimately new purchase after a failed attempt.
                    checkoutRef = checkoutRef + "-" + UUID.randomUUID().toString().substring(0, 8);
                } else {
                    return new PaymentDto.InitiateResponse(tx.getReference(), tx.getStatus(), tx.getAmount(),
                            "Payment already confirmed.");
                }
            }
        }

        // Rate limit raw initiations (USSD-prompt spam). Resumes return early above.
        long recent = transactionRepository.countByRouterIdAndPhoneAndCreatedAtAfter(
                router.getId(), phoneE164,
                OffsetDateTime.now().minusSeconds(INITIATE_RATE_WINDOW_SECONDS));
        if (recent >= INITIATE_RATE_LIMIT) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many payment attempts. Please wait a few minutes.");
        }

        String mode = gatewayConfig.modeFor(router.getTenant());
        String reference = "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        String network = detectNetwork(req.phone(), req.network());
        boolean mock = useMockGateway(mode);

        Transaction.TransactionBuilder builder = Transaction.builder()
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
                .createdAt(OffsetDateTime.now());
        if (!mock) {
            // MarzPay collection reference committed WITH the row — the provider
            // request is only sent after this transaction is durable.
            builder.externalReference(UUID.randomUUID().toString()); // MarzPay requires UUID v4
        }
        Transaction transaction = transactionRepository.save(builder.build());

        if (mock) {
            // Dev fallback gateway: no external HTTP, safe to run in-transaction.
            String extRef = gatewayClient.requestPayment(transaction);
            if (extRef != null) {
                transaction.setExternalReference(extRef);
                transactionRepository.save(transaction);
            }
        } else {
            // Dispatch after commit so the provider reference is durable first.
            Long txId = transaction.getId();
            registerAfterCommit(() -> self.dispatchCollection(txId));
        }

        log.info("Initiated transaction ref: {} for amount: {} UGX via {} gateway={} (router: {}, checkout: {})",
                reference, packagePlan.getPrice(), network, mode, router.getId(), checkoutRef);

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
        // The checkout is immutable: router, package, and phone must all match.
        boolean sameCheckout = tx.getRouter() != null && tx.getRouter().getToken().equals(req.token())
                && tx.getPackagePlan().getId().equals(req.packageId())
                && tx.getPhone().equals(toE164(req.phone()));
        if (!sameCheckout) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Checkout already in progress with different details");
        }
        if (tx.getDispatchAt() == null) {
            // Provably never sent — dispatch once after commit.
            Long txId = tx.getId();
            registerAfterCommit(() -> self.dispatchCollection(txId));
        } else {
            // Already dispatched — resolve by provider lookup only. NEVER re-send.
            recheckProvider(tx);
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

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
        if (extRef == null || tx.getDispatchAt() == null || useMockGateway(tx.getGatewayMode())) return;
        if (tx.getRecheckCount() != null && tx.getRecheckCount() >= MAX_RECHECKS) return;
        OffsetDateTime now = OffsetDateTime.now();
        if (tx.getLastRecheckAt() != null
                && tx.getLastRecheckAt().isAfter(now.minusSeconds(RECHECK_INTERVAL_SECONDS))) {
            return; // throttled
        }
        tx.setLastRecheckAt(now);
        tx.setRecheckCount((tx.getRecheckCount() == null ? 0 : tx.getRecheckCount()) + 1);
        transactionRepository.save(tx);
        try {
            var creds = gatewayConfig.credentialsFor(tx.getTenant(), tx.getGatewayMode());
            // /transactions/{id} is documented to accept our merchant reference.
            var status = marzPay.getTransaction(creds, extRef);
            var payload = status.has("data") && status.get("data").isObject() ? status.get("data") : status;
            processMarzPayCollection(payload);
        } catch (com.wifibilling.service.payment.MarzPayClient.MarzPayException e) {
            if (e.isNotFound()) {
                // Provider has no record — outcome unresolved. Flag for manual
                // review; NEVER auto-redispatch (visibility lag is possible).
                tx.setNotes("Provider has no record of collection ref " + extRef
                        + " — outcome unresolved, flagged for manual review");
                transactionRepository.save(tx);
                log.warn("Provider shows no record for tx {} (ref {}) — unresolved, no redispatch",
                        tx.getReference(), extRef);
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
    /**
     * Commits dispatch_at if and only if this transaction is still PENDING and
     * was never dispatched. Atomic — exactly one caller wins the claim.
     */
    // REQUIRES_NEW: callers may run inside afterCommit, where the just-committed
    // transaction is still bound to the thread. REQUIRED would join that stale
    // transaction and the @Modifying claimDispatch would see "no active transaction".
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimCollectionDispatch(Long txId) {
        Transaction tx = transactionRepository.findById(txId).orElse(null);
        if (tx == null || !STATUS_PENDING.equals(tx.getStatus())
                || tx.getExternalReference() == null
                || useMockGateway(tx.getGatewayMode())) {
            return false;
        }
        return transactionRepository.claimDispatch(txId) == 1;
    }

    /**
     * Performs the single MarzPay collection dispatch for a transaction. Runs
     * OUTSIDE any transaction so dispatch_at commits before the HTTP call:
     * claiming is atomic — whichever caller (afterCommit, resume, sweep) sets
     * dispatch_at first wins and only that caller sends the request. A failure
     * after claiming leaves the transaction PENDING and "uncertain": the
     * provider may have received it, so we NEVER send again automatically.
     */
    public void dispatchCollection(Long txId) {
        if (!self.claimCollectionDispatch(txId)) {
            return; // not dispatchable, or another dispatcher claimed it
        }
        Transaction tx = transactionRepository.findByIdFetchRefs(txId).orElse(null);
        if (tx == null || !STATUS_PENDING.equals(tx.getStatus())) return;
        String collectRef = tx.getExternalReference();
        try {
            var creds = gatewayConfig.credentialsFor(tx.getTenant(), tx.getGatewayMode());
            String callbackUrl = platformProperties.getPublicBaseUrl() + "/api/payments/marzpay/callback";
            var res = marzPay.collectMoney(creds,
                    tx.getAmount().longValue(), tx.getPhone(), collectRef,
                    gatewayConfig.countryFor(tx.getTenant(), tx.getGatewayMode()),
                    "WiFi " + tx.getPackagePlan().getName(), callbackUrl);
            var txn = res.path("data").path("transaction");
            if (!txn.path("uuid").isMissingNode() && !txn.path("uuid").isNull()) {
                tx.setProviderUuid(txn.path("uuid").asText());
            }
            transactionRepository.save(tx);
        } catch (Exception e) {
            // Response lost / provider error — outcome is uncertain. Stay PENDING;
            // recheckProvider resolves it via GET /transactions/{reference}.
            tx.setNotes("Collection dispatch uncertain: " + e.getMessage());
            transactionRepository.save(tx);
            log.error("MarzPay collection dispatch uncertain for ref {} (provider ref {}): {}",
                    tx.getReference(), collectRef, e.getMessage());
        }
    }

    /**
     * Scheduled reconciliation for collections. Every 30s:
     *  - PENDING with dispatch_at NULL   → never sent; dispatch once (safe).
     *  - PENDING with dispatch_at SET    → verify with the provider; apply the
     *    authoritative outcome. Provider "not found" is flagged, never retried.
     * Handles: missed webhooks, lost responses, backend crashes, provider lag.
     */
    @Scheduled(fixedDelayString = "${payment.reconcile.interval-ms:30000}",
               initialDelayString = "${payment.reconcile.initial-delay-ms:60000}")
    public void reconcilePendingCollections() {
        List<Transaction> pending = transactionRepository
                .findTop50ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                        STATUS_PENDING, OffsetDateTime.now().minusSeconds(15));
        for (Transaction t : pending) {
            try {
                if (useMockGateway(t.getGatewayMode())) continue;
                if (t.getDispatchAt() == null) {
                    dispatchCollection(t.getId()); // provably never sent
                } else {
                    self.reconcileTransaction(t.getId());
                }
            } catch (Exception e) {
                log.warn("Reconciliation step failed for tx {}: {}", t.getReference(), e.getMessage());
            }
        }
    }

    /** Transactional per-transaction reconcile used by the sweep. */
    @Transactional
    public void reconcileTransaction(Long txId) {
        Transaction tx = transactionRepository.findByIdForUpdate(txId).orElse(null);
        if (tx == null || !STATUS_PENDING.equals(tx.getStatus())) return;
        recheckProvider(tx);
    }

    private void registerAfterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                // Post-commit work must not fail the HTTP request — the caller's
                // transaction is already committed by the time this runs.
                @Override public void afterCommit() {
                    try { r.run(); }
                    catch (Exception e) { log.error("afterCommit task failed: {}", e.getMessage(), e); }
                }
            });
        } else {
            r.run();
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
            String nasIp = tx.getRouter() != null ? tx.getRouter().getWgIp() : null;
            List<VoucherResponse> vouchers = voucherService.generate(
                    tx.getTenant().getId(),
                    new GenerateVouchersRequest(tx.getPackagePlan().getId(), 1),
                    tx.getClientMac(), nasIp
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
            String reason = txn.path("description").asText("");
            tx.setNotes("Provider status: " + status
                    + (reason.isBlank() ? "" : " — " + reason.substring(0, Math.min(reason.length(), 300))));
            transactionRepository.save(tx);
        }
    }

    /**
     * Customer-safe explanation for a failed payment, derived from the provider
     * reason stored in notes. Returns null when no reason can be established —
     * the portal then shows its generic message. Never echoes raw notes.
     */
    static String customerFailureMessage(String notes) {
        if (notes == null) return null;
        String n = notes.toUpperCase();
        if (n.contains("MINIMUM")) {
            return "This package is below the mobile money minimum amount. Please choose another package.";
        }
        if (n.contains("BALANCE") || n.contains("FUNDS") || n.contains("LIMIT")) {
            return "Your mobile money balance is too low or a transaction limit was reached. Top up or use another number, then try again.";
        }
        if (n.contains("PIN")) {
            return "The mobile money PIN was incorrect. Please try again.";
        }
        if (n.contains("EXPIRE") || n.contains("TIMEOUT") || n.contains("TIMED OUT")) {
            return "The payment prompt expired before it was approved. Please try again and approve it on your phone.";
        }
        if (n.contains("NOT_FOUND") || n.contains("NOT REGISTERED") || n.contains("INVALID_NUMBER")
                || n.contains("INVALID PHONE") || n.contains("PAYER_NOT")) {
            return "This number could not be charged — check it is registered for mobile money, or use another number.";
        }
        if (n.startsWith("PROVIDER REJECTED COLLECTION")) {
            return "The payment could not be started. Check your phone number and try again.";
        }
        if (n.contains("REJECT") || n.contains("DECLIN") || n.contains("CANCEL")) {
            return "The payment was declined or cancelled on your phone. You can try again.";
        }
        return null;
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
                tx.getCompletedAt(),
                STATUS_FAILED.equals(tx.getStatus()) ? customerFailureMessage(tx.getNotes()) : null
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
