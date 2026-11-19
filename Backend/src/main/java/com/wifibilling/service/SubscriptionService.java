package com.wifibilling.service;

import com.wifibilling.dto.SubscriptionDto;
import com.wifibilling.model.*;
import com.wifibilling.repository.*;
import com.wifibilling.service.payment.MarzPayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Production Multi-Tenant SaaS Subscription & Billing Lifecycle Service.
 * Manages 7-day trials, tiered plans, 2-day grace periods, MarzPay collections,
 * automated sweeps, period history auditing, and customer-payment eligibility enforcement.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    public static final ZoneId TIMEZONE_KAMPALA = ZoneId.of("Africa/Kampala");
    @org.springframework.beans.factory.annotation.Value("${app.subscription.enforcement.enabled:false}")
    private boolean subscriptionEnforcementEnabled;
    public static final long FALLBACK_DEFAULT_FEE = 50_000L;
    public static final int TRIAL_DAYS = 7;
    public static final int GRACE_DAYS = 2;
    private static final String KEY_DEFAULT_FEE = "subscription.default_monthly_fee";

    private final SubscriptionRepository subscriptions;
    private final SubscriptionPlanRepository plans;
    private final SubscriptionInvoiceRepository invoices;
    private final SubscriptionPaymentRepository payments;
    private final SubscriptionCheckoutRepository checkouts;
    private final SubscriptionNotificationRepository notificationRepository;
    private final SubscriptionPeriodHistoryRepository periodHistoryRepository;
    private final TenantRepository tenants;
    private LeaderLockService leaderLockService = new LeaderLockService();
    private WalletService walletService;
    private PlatformEarningsService platformEarnings;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWalletService(@org.springframework.context.annotation.Lazy WalletService walletService) {
        this.walletService = walletService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPlatformEarnings(@org.springframework.context.annotation.Lazy PlatformEarningsService platformEarnings) {
        this.platformEarnings = platformEarnings;
    }

    /** Stored as the checkout's phone when the owner paid from their wallet balance. */
    public static final String PAID_FROM_WALLET = "WALLET";

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLeaderLockService(LeaderLockService leaderLockService) {
        this.leaderLockService = leaderLockService;
    }
    private final PlatformSettingRepository settings;
    private final MarzPayClient marzPay;
    private final GatewayConfigService gatewayConfig;
    private final EmailService emailService;
    private final EmailTemplates emailTemplates;

    @Value("${app.public-base-url:http://localhost:3000}")
    private String publicBaseUrl;

    // Lazy self-injection so internal calls still go through the tx proxy.
    // Defaults to the raw instance so plain unit tests (no proxy) still work.
    private SubscriptionService self = this;

    @org.springframework.beans.factory.annotation.Autowired
    void setSelf(@org.springframework.context.annotation.Lazy SubscriptionService self) {
        this.self = self;
    }

    // ==========================================
    // 1. Automatic 7-Day Free Trial
    // ==========================================

    /**
     * Initializes a 7-day free trial for a new tenant.
     * Guaranteed idempotent: if trial was already granted or subscription exists, does nothing.
     */
    @Transactional
    public Subscription grantTrialIfEligible(Tenant tenant) {
        if ("ROLE_SUPERADMIN".equals(tenant.getRole())) {
            return null; // Superadmins are immune
        }

        Optional<Subscription> existing = subscriptions.findByTenantId(tenant.getId());
        if (existing.isPresent()) {
            return existing.get();
        }

        SubscriptionPlan defaultPlan = plans.findAllByActiveTrueOrderByDisplayOrderAscPriceAsc().stream()
                .findFirst()
                .orElseGet(() -> plans.findAll().stream().findFirst().orElse(null));

        long fee = defaultPlan != null ? defaultPlan.getPrice() : defaultFee();
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime trialEnd = now.plusDays(TRIAL_DAYS);
        OffsetDateTime graceEnd = trialEnd.plusDays(GRACE_DAYS);
        LocalDate startLocalDate = now.atZoneSameInstant(TIMEZONE_KAMPALA).toLocalDate();
        LocalDate endLocalDate = trialEnd.atZoneSameInstant(TIMEZONE_KAMPALA).toLocalDate();

        Subscription sub = Subscription.builder()
                .tenant(tenant)
                .plan(defaultPlan)
                .monthlyFee(fee)
                .anchorDay(trialEnd.atZoneSameInstant(TIMEZONE_KAMPALA).getDayOfMonth())
                .startDate(startLocalDate)
                .nextRenewalDate(endLocalDate)
                .expiresAt(trialEnd)
                .trialStartsAt(now)
                .trialEndsAt(trialEnd)
                .graceUntil(graceEnd)
                .trialGranted(true)
                .status(Subscription.STATUS_TRIALING)
                .build();

        sub = subscriptions.save(sub);
        log.info("Initialized 7-day free trial for tenant {} (ID {}), expires at {}", tenant.getName(), tenant.getId(), trialEnd);

        // Record initial trial in SubscriptionPeriodHistory
        SubscriptionPeriodHistory hist = SubscriptionPeriodHistory.builder()
                .tenant(tenant)
                .subscription(sub)
                .planName(defaultPlan != null ? defaultPlan.getName() : "7-Day Free Trial")
                .periodType("FREE_TRIAL")
                .periodStart(startLocalDate)
                .periodEnd(endLocalDate)
                .durationDays(TRIAL_DAYS)
                .amountCharged(0L)
                .status("ACTIVE")
                .note("7-day complimentary launch trial")
                .build();
        periodHistoryRepository.save(hist);

        return sub;
    }

    // ==========================================
    // 2. Lifecycle Evaluation & Enforcement
    // ==========================================

    @Transactional
    public Subscription refreshLifecycle(Subscription sub) {
        if (Subscription.STATUS_CANCELLED.equals(sub.getStatus())) {
            return sub;
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expires = sub.getExpiresAt() != null
                ? sub.getExpiresAt()
                : sub.getNextRenewalDate().atStartOfDay(TIMEZONE_KAMPALA).toOffsetDateTime();

        OffsetDateTime graceUntil = sub.getGraceUntil() != null
                ? sub.getGraceUntil()
                : expires.plusDays(GRACE_DAYS);

        String previousStatus = sub.getStatus();
        String currentStatus;

        if (now.isBefore(expires)) {
            currentStatus = Boolean.TRUE.equals(sub.getTrialGranted()) && sub.getTrialEndsAt() != null && expires.equals(sub.getTrialEndsAt())
                    ? Subscription.STATUS_TRIALING
                    : Subscription.STATUS_ACTIVE;
        } else if (now.isBefore(graceUntil)) {
            currentStatus = Subscription.STATUS_GRACE_PERIOD;
        } else {
            currentStatus = Subscription.STATUS_SUSPENDED;
        }

        if (!currentStatus.equals(previousStatus)) {
            sub.setStatus(currentStatus);
            sub = subscriptions.save(sub);
            log.info("Subscription for tenant {} shifted from {} to {}", sub.getTenant().getId(), previousStatus, currentStatus);
        }
        return sub;
    }

    @Transactional(readOnly = true)
    public boolean canTenantAcceptCustomerPayments(Long tenantId) {
        Optional<Tenant> tOpt = tenants.findById(tenantId);
        if (tOpt.isEmpty()) return false;
        if ("ROLE_SUPERADMIN".equals(tOpt.get().getRole())) return true;
        if ("DISABLED".equalsIgnoreCase(tOpt.get().getStatus()) || "SUSPENDED".equalsIgnoreCase(tOpt.get().getStatus())) {
            return false;
        }

        // Feature Flag: If production subscription enforcement is disabled, allow existing tenants to accept payments
        if (!subscriptionEnforcementEnabled) {
            return true;
        }

        Optional<Subscription> subOpt = subscriptions.findByTenantId(tenantId);
        if (subOpt.isEmpty()) return false;

        Subscription sub = subOpt.get();
        if (Subscription.STATUS_CANCELLED.equals(sub.getStatus()) || Subscription.STATUS_SUSPENDED.equals(sub.getStatus())) {
            return false;
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expires = sub.getExpiresAt() != null
                ? sub.getExpiresAt()
                : sub.getNextRenewalDate().atStartOfDay(TIMEZONE_KAMPALA).toOffsetDateTime();

        return now.isBefore(expires);
    }

    @Transactional(readOnly = true)
    public boolean isTenantManagementAllowed(Long tenantId) {
        Optional<Tenant> tOpt = tenants.findById(tenantId);
        if (tOpt.isEmpty()) return false;
        if ("ROLE_SUPERADMIN".equals(tOpt.get().getRole())) return true;

        // Feature Flag: If production subscription enforcement is disabled, allow existing tenant management
        if (!subscriptionEnforcementEnabled) {
            return true;
        }

        Optional<Subscription> subOpt = subscriptions.findByTenantId(tenantId);
        if (subOpt.isEmpty()) return false;

        Subscription sub = subOpt.get();
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expires = sub.getExpiresAt() != null
                ? sub.getExpiresAt()
                : sub.getNextRenewalDate().atStartOfDay(TIMEZONE_KAMPALA).toOffsetDateTime();

        return now.isBefore(expires);
    }

    @Transactional(readOnly = true)
    public void assertTenantManagementAllowed(Long tenantId) {
        if (!isTenantManagementAllowed(tenantId)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Your subscription or free trial has expired. Management operations are locked until renewed.");
        }
    }

    // ==========================================
    // 3. Mobile Money Self-Service Checkout
    // ==========================================

    /** Deliberately not transactional: the MarzPay collect call must run with
     *  no transaction held so it cannot sit idle-in-transaction on the network. */
    public SubscriptionDto.CheckoutResponse initiateSubscriptionCheckout(Long tenantId, SubscriptionDto.CheckoutRequest req) {
        var prep = self.prepareSubscriptionCheckout(tenantId, req);

        String providerUuid = null;
        try {
            JsonNode resp = marzPay.collectMoney(
                    prep.creds(),
                    prep.amount(),
                    prep.phoneE164(),
                    prep.reference(),
                    prep.country(),
                    "AirPairy SaaS Subscription - " + prep.planName(),
                    prep.callbackUrl()
            );
            if (resp != null) {
                JsonNode txn = resp.path("data").path("transaction");
                providerUuid = txn.path("uuid").asText(null);
            }
        } catch (MarzPayClient.MarzPayException e) {
            log.error("Subscription checkout {} — MarzPay collect failed (HTTP {}): {}",
                    prep.reference(), e.getStatus(), e.getMessage());
            if (e.getStatus() >= 400 && e.getStatus() < 500) {
                // The provider looked at the request and refused it: nothing was sent to the phone.
                self.failSubscriptionCheckout(prep.checkoutId(), truncate("Provider rejected request: " + e.getMessage(), 255));
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Mobile Money could not start this payment. Check that " + prep.phoneE164()
                                + " is a registered Mobile Money number and try again.");
            }
            // Timeout or provider error: the prompt may still have gone out. Keep the
            // checkout PENDING; the status poll, the callback or the sweep resolves it
            // by asking the provider. Never mark it failed — money may have moved.
            return new SubscriptionDto.CheckoutResponse(
                    prep.reference(),
                    SubscriptionCheckout.STATUS_PENDING,
                    prep.amount(),
                    prep.phoneE164(),
                    "We're confirming the request with Mobile Money. If a prompt appears on " + prep.phoneE164()
                            + ", enter your PIN to approve UGX " + String.format("%,d", prep.amount()) + "."
            );
        }

        if (providerUuid != null) self.setCheckoutProviderUuid(prep.checkoutId(), providerUuid);

        return new SubscriptionDto.CheckoutResponse(
                prep.reference(),
                SubscriptionCheckout.STATUS_PENDING,
                prep.amount(),
                prep.phoneE164(),
                "A prompt has been sent to " + prep.phoneE164() + ". Enter your Mobile Money PIN to approve payment of UGX " + String.format("%,d", prep.amount()) + "."
        );
    }

    /** Creates the pending checkout row and resolves the provider call inputs — the transactional part. */
    @Transactional
    public CheckoutPrep prepareSubscriptionCheckout(Long tenantId, SubscriptionDto.CheckoutRequest req) {
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));

        Subscription sub = subscriptions.findByTenantId(tenantId)
                .orElseGet(() -> grantTrialIfEligible(tenant));

        SubscriptionPlan plan = resolvePlan(sub, req.planId());

        long amount = plan.getPrice();
        String phoneE164 = toE164(req.phone());
        // MarzPay only accepts a UUID as the merchant reference.
        String reference = UUID.randomUUID().toString();

        SubscriptionCheckout checkout = checkouts.save(SubscriptionCheckout.builder()
                .tenant(tenant)
                .plan(plan)
                .reference(reference)
                .phone(phoneE164)
                .amount(amount)
                .status(SubscriptionCheckout.STATUS_PENDING)
                .build());

        var creds = gatewayConfig.credentialsFor(tenant, TenantGatewayConfig.MODE_PLATFORM);
        String country = gatewayConfig.countryFor(tenant, TenantGatewayConfig.MODE_PLATFORM);
        String callbackUrl = publicBaseUrl.replaceAll("/+$", "") + "/api/payments/marzpay/callback";
        return new CheckoutPrep(checkout.getId(), reference, amount, phoneE164, plan.getName(), creds, country, callbackUrl);
    }

    private SubscriptionPlan resolvePlan(Subscription sub, Long planId) {
        return planId != null
                ? plans.findById(planId).filter(SubscriptionPlan::getActive)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription plan not found or inactive"))
                : (sub.getPlan() != null ? sub.getPlan() : plans.findAllByActiveTrueOrderByDisplayOrderAscPriceAsc().stream().findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No active plans configured")));
    }

    /**
     * The owner pays the plan from their wallet balance instead of Mobile Money. One database
     * transaction takes the money and extends the subscription, so it is all or nothing.
     * {@code requestKey} (a UUID the page makes once per tap of Pay) makes a double tap or a
     * retry return the first payment instead of charging twice.
     */
    @Transactional
    public SubscriptionDto.CheckoutResponse payFromWallet(Long tenantId, Long planId, String requestKey) {
        if (walletService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Wallet payments are not available right now.");
        }
        if (!isUuid(requestKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing payment request key. Refresh the page and try again.");
        }
        Optional<SubscriptionCheckout> earlier = checkouts.findByReference(requestKey);
        if (earlier.isPresent()) return walletReceipt(tenantId, earlier.get());

        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        Subscription sub = subscriptions.findByTenantId(tenantId).orElseGet(() -> grantTrialIfEligible(tenant));
        SubscriptionPlan plan = resolvePlan(sub, planId);
        long amount = plan.getPrice();

        if (!walletService.debitForSubscription(tenantId, amount, "SUB:" + requestKey,
                "AirPairy subscription — " + plan.getName())) {
            // A concurrent request with the same key won the wallet lock and has committed by now.
            return checkouts.findByReference(requestKey).map(c -> walletReceipt(tenantId, c))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This payment is already being processed."));
        }
        SubscriptionCheckout checkout = checkouts.save(SubscriptionCheckout.builder()
                .tenant(tenant)
                .plan(plan)
                .reference(requestKey)
                .phone(PAID_FROM_WALLET)
                .amount(amount)
                .status(SubscriptionCheckout.STATUS_SUCCESSFUL)
                .completedAt(OffsetDateTime.now())
                .build());
        applySuccessfulPayment(checkout);
        log.info("Tenant {} paid subscription {} (UGX {}) from wallet", tenantId, requestKey, amount);
        return walletReceipt(tenantId, checkout);
    }

    private SubscriptionDto.CheckoutResponse walletReceipt(Long tenantId, SubscriptionCheckout checkout) {
        if (checkout.getTenant() == null || !tenantId.equals(checkout.getTenant().getId())
                || !PAID_FROM_WALLET.equals(checkout.getPhone())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment request key already used. Refresh the page and try again.");
        }
        String until = subscriptions.findByTenantId(tenantId).map(Subscription::getExpiresAt)
                .map(e -> e.atZoneSameInstant(TIMEZONE_KAMPALA).toLocalDate().toString()).orElse(null);
        return new SubscriptionDto.CheckoutResponse(checkout.getReference(), checkout.getStatus(), checkout.getAmount(), null,
                "Paid UGX " + String.format("%,d", checkout.getAmount()) + " from your wallet."
                        + (until != null ? " Your subscription is active until " + until + "." : ""));
    }

    @Transactional
    public void setCheckoutProviderUuid(Long checkoutId, String providerUuid) {
        checkouts.findById(checkoutId).ifPresent(checkout -> {
            checkout.setProviderUuid(providerUuid);
            checkouts.save(checkout);
        });
    }

    @Transactional
    public void failSubscriptionCheckout(Long checkoutId, String reason) {
        checkouts.findById(checkoutId).ifPresent(checkout -> {
            checkout.setStatus(SubscriptionCheckout.STATUS_FAILED);
            checkout.setFailureReason(reason);
            checkouts.save(checkout);
        });
    }

    record CheckoutPrep(Long checkoutId, String reference, long amount, String phoneE164, String planName,
                        com.wifibilling.service.payment.MarzPayClient.Credentials creds, String country, String callbackUrl) {}

    /**
     * A MarzPay callback mentioned this reference. The callback body is unsigned, so it is only
     * a hint: the outcome is always read back from the provider before anything is applied.
     * Not transactional — the provider call must hold no database transaction.
     * Returns false when the reference is not a subscription checkout.
     */
    public boolean confirmSubscriptionCheckout(String reference, JsonNode hint) {
        if (reference == null) return false;
        Optional<SubscriptionCheckout> opt = checkouts.findByReference(reference);
        if (opt.isEmpty()) return false;
        verifyWithProvider(opt.get());
        return true;
    }

    /** The tenant's own view of one checkout; asks the provider while it is still pending. */
    public SubscriptionDto.CheckoutResponse checkoutStatus(Long tenantId, String reference) {
        SubscriptionCheckout checkout = checkouts.findByReference(reference)
                .filter(c -> c.getTenant() != null && tenantId.equals(c.getTenant().getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Checkout not found"));
        if (SubscriptionCheckout.STATUS_PENDING.equals(checkout.getStatus())) {
            Long last = lastProviderCheck.get(checkout.getId());
            if (last == null || System.currentTimeMillis() - last >= PROVIDER_CHECK_INTERVAL_MS) {
                verifyWithProvider(checkout);
                checkout = checkouts.findByReference(reference).orElse(checkout);
            }
        }
        String status = checkout.getStatus();
        String message = switch (status) {
            case SubscriptionCheckout.STATUS_SUCCESSFUL -> "Payment received! Your subscription has been extended.";
            case SubscriptionCheckout.STATUS_FAILED -> "The payment was not completed. No money was taken — you can try again.";
            default -> "Waiting for you to approve the prompt on " + checkout.getPhone() + ".";
        };
        return new SubscriptionDto.CheckoutResponse(checkout.getReference(), status, checkout.getAmount(),
                checkout.getPhone(), message);
    }

    /**
     * Callbacks can be lost. Every two minutes, ask the provider about checkouts still
     * pending from the last day so a paid renewal is never left unapplied.
     */
    @Scheduled(fixedDelay = 120_000, initialDelay = 90_000)
    public void reconcilePendingCheckouts() {
        leaderLockService.executeIfLeader(LeaderLockService.JOB_SUBSCRIPTION_CHECKOUT, 300, 110,
                this::doReconcilePendingCheckouts);
    }

    void doReconcilePendingCheckouts() {
        backfillSubscriptionEarnings();
        OffsetDateTime now = OffsetDateTime.now();
        for (SubscriptionCheckout c : checkouts.findAllByStatus(SubscriptionCheckout.STATUS_PENDING)) {
            if (c.getCreatedAt() == null) continue;
            if (c.getCreatedAt().isAfter(now.minusMinutes(1))) continue;   // give the callback a chance first
            if (c.getCreatedAt().isBefore(now.minusDays(1))) {             // prompts expire long before this
                self.failSubscriptionCheckout(c.getId(), "No confirmation from the provider within 24 hours");
                continue;
            }
            verifyWithProvider(c);
        }
    }

    private static final long PROVIDER_CHECK_INTERVAL_MS = 5_000;
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> lastProviderCheck = new java.util.concurrent.ConcurrentHashMap<>();

    /** Reads the authoritative outcome from MarzPay and applies it. Best-effort: on any error it stays pending. */
    private void verifyWithProvider(SubscriptionCheckout checkout) {
        if (SubscriptionCheckout.STATUS_SUCCESSFUL.equals(checkout.getStatus())) return;
        String reference = checkout.getReference();
        if (!isUuid(reference)) return; // legacy SUB- references were never accepted by the provider
        lastProviderCheck.put(checkout.getId(), System.currentTimeMillis());
        try {
            var creds = gatewayConfig.credentialsFor(checkout.getTenant(), TenantGatewayConfig.MODE_PLATFORM);
            JsonNode res = marzPay.getTransaction(creds, reference);
            JsonNode payload = res != null && res.has("data") && res.get("data").isObject() ? res.get("data") : res;
            JsonNode txn = payload == null ? null : payload.path("transaction");
            if (txn == null || txn.isMissingNode()) return;
            String status = txn.path("status").asText("").toLowerCase(Locale.ROOT);
            Long paid = amountOf(txn.path("amount"));
            Long net = amountOf(txn.path("net_amount"));
            String providerUuid = txn.path("uuid").asText(null);
            String providerTx = payload.path("collection").path("provider_transaction_id").asText(providerUuid);
            String description = txn.path("description").asText(null);
            self.applyProviderOutcome(checkout.getId(), status, paid, net, providerTx, description);
        } catch (MarzPayClient.MarzPayException e) {
            if (e.isNotFound()) {
                log.info("Subscription checkout {}: provider has no record yet", reference);
            } else {
                log.warn("Subscription checkout {}: provider lookup failed: {}", reference, e.getMessage());
            }
        } catch (Exception e) {
            log.warn("Subscription checkout {}: provider lookup failed: {}", reference, e.getMessage());
        }
    }

    /**
     * Applies a provider-confirmed outcome under a row lock, so a callback, the status poll and
     * the sweep racing each other can never extend a subscription twice.
     */
    @Transactional
    public void applyProviderOutcome(Long checkoutId, String providerStatus, Long paidAmount, Long netAmount,
                                     String providerTx, String description) {
        SubscriptionCheckout checkout = checkouts.findById(checkoutId)
                .flatMap(c -> checkouts.findByReferenceForUpdate(c.getReference()))
                .orElse(null);
        if (checkout == null || SubscriptionCheckout.STATUS_SUCCESSFUL.equals(checkout.getStatus())) return;
        String status = providerStatus == null ? "" : providerStatus.toLowerCase(Locale.ROOT);

        if (status.equals("completed") || status.equals("successful") || status.equals("success")) {
            if (paidAmount != null && paidAmount < checkout.getAmount()) {
                checkout.setStatus(SubscriptionCheckout.STATUS_FAILED);
                checkout.setFailureReason(truncate("Provider confirmed only UGX " + paidAmount + " of "
                        + checkout.getAmount() + " — needs manual review", 255));
                checkouts.save(checkout);
                log.error("Subscription checkout {} underpaid: {} of {}", checkout.getReference(), paidAmount, checkout.getAmount());
                return;
            }
            // A provider-confirmed payment wins even over an earlier local FAILED: the money moved.
            checkout.setStatus(SubscriptionCheckout.STATUS_SUCCESSFUL);
            checkout.setExternalReference(providerTx);
            checkout.setNetAmount(netAmount);
            checkout.setFailureReason(null);
            checkout.setCompletedAt(OffsetDateTime.now());
            checkouts.save(checkout);
            applySuccessfulPayment(checkout);
        } else if (status.equals("failed") || status.equals("cancelled") || status.equals("expired")) {
            if (SubscriptionCheckout.STATUS_FAILED.equals(checkout.getStatus())) return;
            checkout.setStatus(SubscriptionCheckout.STATUS_FAILED);
            checkout.setFailureReason(truncate("Provider status: " + status
                    + (description == null || description.isBlank() ? "" : " — " + description), 255));
            checkout.setCompletedAt(OffsetDateTime.now());
            checkouts.save(checkout);
            log.warn("Subscription checkout {} failed at provider: {}", checkout.getReference(), status);
        }
        // pending / processing: nothing to do yet
    }

    /**
     * Subscription money sits in the platform MarzPay account either way: a Mobile Money renewal
     * lands there net of the provider fee, a wallet payment was already there as the tenant's balance.
     * Without this credit the reconciliation shows it as unexplained surplus.
     */
    private void creditPlatformEarnings(SubscriptionPayment payment, SubscriptionCheckout checkout) {
        if (platformEarnings == null || payment == null || payment.getId() == null) return;
        Long settled = PAID_FROM_WALLET.equals(checkout.getPhone()) ? checkout.getAmount() : checkout.getNetAmount();
        if (settled == null) {
            log.warn("Subscription payment {}: provider net unknown — earnings credit left to the backfill", payment.getId());
            return;
        }
        platformEarnings.creditSubscriptionPayment(payment, "MARZPAY", settled);
    }

    private final java.util.concurrent.atomic.AtomicBoolean earningsBackfilled = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Once per process: earlier renewals were never added to platform earnings. Each one is
     * credited at the net the provider reports for it, so the books match what MarzPay holds.
     */
    void backfillSubscriptionEarnings() {
        if (platformEarnings == null || !earningsBackfilled.compareAndSet(false, true)) return;
        boolean complete = true;
        for (SubscriptionPayment p : payments.findAll()) {
            if (!"MOBILE_MONEY".equals(p.getMethod()) && !"WALLET".equals(p.getMethod())) continue;
            if (platformEarnings.hasSubscriptionEarning(p.getId())) continue;
            SubscriptionCheckout c = p.getExternalRef() == null ? null : checkouts.findByReference(p.getExternalRef()).orElse(null);
            if (c == null) continue;
            Long settled = "WALLET".equals(p.getMethod()) ? p.getAmount() : c.getNetAmount();
            if (settled == null) settled = providerNet(c);
            if (settled == null) { complete = false; continue; }
            platformEarnings.creditSubscriptionPayment(p, "MARZPAY", settled);
            log.info("Backfilled platform earnings for subscription payment {}: UGX {}", p.getId(), settled);
        }
        if (!complete) earningsBackfilled.set(false); // try the rest on the next sweep
    }

    private Long providerNet(SubscriptionCheckout c) {
        if (!isUuid(c.getReference())) return null;
        try {
            var creds = gatewayConfig.credentialsFor(c.getTenant(), TenantGatewayConfig.MODE_PLATFORM);
            JsonNode res = marzPay.getTransaction(creds, c.getReference());
            JsonNode payload = res != null && res.has("data") && res.get("data").isObject() ? res.get("data") : res;
            JsonNode txn = payload == null ? null : payload.path("transaction");
            if (txn == null || !"completed".equalsIgnoreCase(txn.path("status").asText(""))) return null;
            return amountOf(txn.path("net_amount"));
        } catch (Exception e) {
            log.warn("Could not read provider net for subscription checkout {}: {}", c.getReference(), e.getMessage());
            return null;
        }
    }

    private static Long amountOf(JsonNode amount) {
        if (amount == null || amount.isMissingNode() || amount.isNull()) return null;
        if (amount.isNumber()) return amount.longValue();
        if (amount.path("raw").isNumber()) return amount.path("raw").longValue();
        if (amount.isString()) {
            try { return (long) Double.parseDouble(amount.asString().replace(",", "")); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static boolean isUuid(String s) {
        if (s == null) return false;
        try { UUID.fromString(s); return s.length() == 36; } catch (IllegalArgumentException e) { return false; }
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.substring(0, Math.min(s.length(), max));
    }

    private void applySuccessfulPayment(SubscriptionCheckout checkout) {
        Tenant tenant = checkout.getTenant();
        Subscription sub = subscriptions.findByTenantId(tenant.getId())
                .orElseGet(() -> grantTrialIfEligible(tenant));

        SubscriptionPlan plan = checkout.getPlan();
        int months = (plan != null && "MONTHS".equalsIgnoreCase(plan.getDurationUnit()))
                ? plan.getDurationValue()
                : 1;

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime currentExpiry = sub.getExpiresAt() != null ? sub.getExpiresAt() : now;

        OffsetDateTime baseTime = currentExpiry.isAfter(now) ? currentExpiry : now;
        OffsetDateTime newExpiry = baseTime.plusMonths(months);
        OffsetDateTime newGrace = newExpiry.plusDays(GRACE_DAYS);
        LocalDate startLocalDate = baseTime.atZoneSameInstant(TIMEZONE_KAMPALA).toLocalDate();
        LocalDate endLocalDate = newExpiry.atZoneSameInstant(TIMEZONE_KAMPALA).toLocalDate();

        sub.setPlan(plan);
        sub.setMonthlyFee(checkout.getAmount());
        sub.setExpiresAt(newExpiry);
        sub.setGraceUntil(newGrace);
        sub.setNextRenewalDate(endLocalDate);
        sub.setStatus(Subscription.STATUS_ACTIVE);
        sub = subscriptions.save(sub);

        // Record Invoice & Payment receipt
        SubscriptionInvoice invoice = SubscriptionInvoice.builder()
                .subscription(sub)
                .tenant(tenant)
                .periodStart(startLocalDate)
                .periodEnd(endLocalDate)
                .amountDue(checkout.getAmount())
                .status(SubscriptionInvoice.PAID)
                .paidAt(now)
                .build();
        try {
            invoice = invoices.save(invoice);
        } catch (DataIntegrityViolationException ignored) {}

        boolean fromWallet = PAID_FROM_WALLET.equals(checkout.getPhone());
        SubscriptionPayment payment = SubscriptionPayment.builder()
                .subscription(sub)
                .invoice(invoice)
                .tenant(tenant)
                .amount(checkout.getAmount())
                .method(fromWallet ? "WALLET" : "MOBILE_MONEY")
                .externalRef(checkout.getReference())
                .recordRef(UUID.randomUUID().toString())
                .paidAt(now)
                .note(fromWallet ? "Self-service renewal paid from wallet balance"
                        : "Self-service Mobile Money renewal via MarzPay (" + checkout.getPhone() + ")")
                .build();
        payment = payments.save(payment);
        creditPlatformEarnings(payment, checkout);

        // Record Subscription Period History
        SubscriptionPeriodHistory hist = SubscriptionPeriodHistory.builder()
                .tenant(tenant)
                .subscription(sub)
                .planName(plan != null ? plan.getName() : "SaaS Subscription")
                .periodType("PAID_SUBSCRIPTION")
                .periodStart(startLocalDate)
                .periodEnd(endLocalDate)
                .durationDays((int) ChronoUnit.DAYS.between(startLocalDate, endLocalDate))
                .amountCharged(checkout.getAmount())
                .status("ACTIVE")
                .paymentDate(now)
                .paymentReference(checkout.getReference())
                .note(fromWallet ? "Self-service renewal (wallet)" : "Self-service renewal (" + checkout.getPhone() + ")")
                .build();
        periodHistoryRepository.save(hist);

        log.info("Subscription RENEWED successfully for tenant {} (ID {}). New expiry: {}",
                tenant.getName(), tenant.getId(), newExpiry);

        recordNotification(
                sub,
                tenant,
                "PAYMENT_SUCCESS",
                "payment-" + checkout.getReference(),
                "Subscription Renewed Successfully",
                "Payment of UGX " + String.format("%,d", checkout.getAmount()) + " received. Your subscription is active until "
                        + endLocalDate + ".",
                "SUCCESS"
        );

        sendPaymentReceiptEmail(sub, tenant, checkout);
    }

    private void sendPaymentReceiptEmail(Subscription sub, Tenant tenant, SubscriptionCheckout checkout) {
        if (!emailService.configured() || tenant.getEmail() == null || tenant.getEmail().isBlank()) return;
        try {
            String planName = checkout.getPlan() != null ? checkout.getPlan().getName() : "SaaS Subscription";
            String dateStr = sub.getExpiresAt().atZoneSameInstant(TIMEZONE_KAMPALA).toLocalDate().toString();
            String body = """
                    <p style="margin:0 0 16px;font-size:14px;color:#334155;line-height:1.6;">
                      Hi %s,
                    </p>
                    <p style="margin:0 0 16px;font-size:14px;color:#334155;line-height:1.6;">
                      Your payment of <b>UGX %s</b> for <b>%s</b> has been received and confirmed.
                    </p>
                    <div style="background:#F0FDF4;border:1px solid #BBF7D0;border-radius:12px;padding:16px;margin-bottom:18px;">
                      <p style="margin:0 0 6px;font-size:13px;color:#166534;"><b>Status:</b> Active</p>
                      <p style="margin:0 0 6px;font-size:13px;color:#166534;"><b>Transaction Ref:</b> %s</p>
                      <p style="margin:0;font-size:13px;color:#166534;"><b>Next Renewal Date:</b> %s</p>
                    </div>
                    <p style="margin:0;font-size:13px;color:#64748B;">
                      All commercial WiFi features and customer payment collections remain fully operational.
                    </p>
                    """.formatted(
                    escape(tenant.getName()),
                    String.format("%,d", checkout.getAmount()),
                    escape(planName),
                    checkout.getReference(),
                    dateStr
            );
            emailService.send(tenant.getEmail(), "Payment Receipt: AirPairy Subscription Renewed",
                    emailTemplates.shell("Subscription payment confirmed", body, ""));
        } catch (Exception e) {
            log.warn("Failed to send subscription renewal receipt email to {}: {}", tenant.getEmail(), e.getMessage());
        }
    }

    // ==========================================
    // 4. Automated Reminders
    // ==========================================

    @Scheduled(cron = "0 0 9 * * *", zone = "${reporting.timezone:Africa/Kampala}")
    @Transactional
    public void dailyReminderSweep() {
        if (!subscriptionEnforcementEnabled) {
            log.info("Subscription enforcement is disabled via feature flag; skipping daily reminder sweep.");
            return;
        }
        if (!leaderLockService.executeIfLeader(LeaderLockService.JOB_SUBSCRIPTION_SWEEP, 600, 3600, this::doDailyReminderSweep)) return;
    }

    @Transactional
    public void doDailyReminderSweep() {
        if (!subscriptionEnforcementEnabled) {
            log.info("Subscription enforcement is disabled via feature flag; skipping doDailyReminderSweep.");
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<Subscription> all = subscriptions.findAll();

        for (Subscription sub : all) {
            try {
                refreshLifecycle(sub);
                checkAndDispatchReminders(sub, now);
            } catch (Exception e) {
                log.error("Error processing subscription reminder for sub ID {}: {}", sub.getId(), e.getMessage());
            }
        }
    }

    public void checkAndDispatchReminders(Subscription sub, OffsetDateTime now) {
        if (Subscription.STATUS_CANCELLED.equals(sub.getStatus())) return;
        OffsetDateTime expires = sub.getExpiresAt() != null ? sub.getExpiresAt() : now;

        long hoursRemaining = ChronoUnit.HOURS.between(now, expires);
        long daysRemaining = hoursRemaining / 24;

        Tenant tenant = sub.getTenant();
        boolean inTrial = Subscription.STATUS_TRIALING.equals(sub.getStatus());
        String cycleKey = (inTrial ? "trial-" : "cycle-") + expires.atZoneSameInstant(TIMEZONE_KAMPALA).toLocalDate();

        if (daysRemaining <= 5 && daysRemaining > 1) {
            String notifType = inTrial ? "TRIAL_5_DAYS" : "PAID_5_DAYS";
            triggerIdempotentReminder(sub, tenant, notifType, cycleKey, daysRemaining);
        } else if (daysRemaining <= 1 && daysRemaining >= 0 && now.isBefore(expires)) {
            String notifType = inTrial ? "TRIAL_1_DAY" : "PAID_1_DAY";
            triggerIdempotentReminder(sub, tenant, notifType, cycleKey, Math.max(1, daysRemaining));
        } else if (now.isAfter(expires) && now.isBefore(sub.getGraceUntil())) {
            triggerIdempotentReminder(sub, tenant, "EXPIRED", cycleKey, 0);
        } else if (now.isAfter(sub.getGraceUntil())) {
            triggerIdempotentReminder(sub, tenant, "GRACE_EXPIRED", cycleKey, 0);
        }
    }

    private void triggerIdempotentReminder(Subscription sub, Tenant tenant, String notifType, String cycleKey, long days) {
        if (notificationRepository.existsBySubscriptionIdAndNotificationTypeAndBillingCycleKey(sub.getId(), notifType, cycleKey)) {
            return;
        }

        String expiryDate = sub.getExpiresAt().atZoneSameInstant(TIMEZONE_KAMPALA).toLocalDate().toString();
        String priceFormatted = "UGX " + String.format("%,d", sub.getMonthlyFee());

        String title;
        String message;
        String severity;

        switch (notifType) {
            case "TRIAL_5_DAYS" -> {
                title = "Your free trial ends in 5 days";
                message = "Your 7-day free trial will end on " + expiryDate + ". Subscribe now for "
                        + priceFormatted + " to ensure zero interruption to hotspot customer payments.";
                severity = "INFO";
            }
            case "PAID_5_DAYS" -> {
                title = "Subscription renewal due in 5 days";
                message = "Your subscription expires on " + expiryDate + ". Renew today for "
                        + priceFormatted + " to maintain active customer payments and network management.";
                severity = "INFO";
            }
            case "TRIAL_1_DAY" -> {
                title = "Final day of your free trial";
                message = "Your trial ends tomorrow, " + expiryDate + ". Renew now to prevent customer payment pauses.";
                severity = "WARNING";
            }
            case "PAID_1_DAY" -> {
                title = "Final day before subscription expires";
                message = "Your subscription expires tomorrow, " + expiryDate + ". When expired, customer payments will be locked.";
                severity = "WARNING";
            }
            case "EXPIRED" -> {
                title = "Subscription Expired: 2-Day Grace Period Active";
                message = "Your subscription has expired. New customer payments and management features are paused. Renew within 2 days to restore access.";
                severity = "DANGER";
            }
            case "GRACE_EXPIRED" -> {
                title = "Account Suspended: Grace period ended";
                message = "Your 2-day grace period has passed without renewal. Customer hotspot payments and management remain locked until you renew.";
                severity = "DANGER";
            }
            default -> { return; }
        }

        boolean emailSent = sendReminderEmail(tenant, notifType, title, message, sub);
        recordNotification(sub, tenant, notifType, cycleKey, title, message, severity, emailSent);

        sub.setLastReminderType(notifType);
        sub.setLastReminderAt(OffsetDateTime.now());
        subscriptions.save(sub);
    }

    private boolean sendReminderEmail(Tenant tenant, String type, String title, String message, Subscription sub) {
        if (!emailService.configured() || tenant.getEmail() == null || tenant.getEmail().isBlank()) {
            return false;
        }

        String renewalUrl = publicBaseUrl.replaceAll("/+$", "") + "/dashboard/subscription";
        String subject = switch (type) {
            case "TRIAL_5_DAYS" -> "Notice: 5 days left in your AirPairy Free Trial";
            case "PAID_5_DAYS" -> "Upcoming Renewal: 5 days remaining in subscription";
            case "TRIAL_1_DAY", "PAID_1_DAY" -> "Urgent: 24 hours left before subscription expiry";
            case "EXPIRED" -> "Action Required: Subscription expired — 2-Day Grace Period active";
            case "GRACE_EXPIRED" -> "Account Suspended: Grace period ended";
            default -> "AirPairy Subscription Update";
        };

        String body = """
                <p style="margin:0 0 16px;font-size:14px;color:#334155;line-height:1.6;">
                  Hi %s,
                </p>
                <p style="margin:0 0 18px;font-size:14px;color:#334155;line-height:1.6;">
                  %s
                </p>
                <p style="margin:0 0 20px;text-align:center;">
                  <a href="%s" style="display:inline-block;background:#2563EB;color:#ffffff;text-decoration:none;
                     border-radius:9px;padding:12px 24px;font-size:13px;font-weight:bold;">Review &amp; Renew Subscription</a>
                </p>
                """.formatted(escape(tenant.getName()), message, renewalUrl);

        try {
            emailService.send(tenant.getEmail(), subject, emailTemplates.shell(title, body, ""));
            return true;
        } catch (Exception e) {
            log.warn("Failed to deliver subscription email to {}: {}", tenant.getEmail(), e.getMessage());
            return false;
        }
    }

    private void recordNotification(Subscription sub, Tenant tenant, String notifType, String cycleKey,
                                    String title, String message, String severity) {
        recordNotification(sub, tenant, notifType, cycleKey, title, message, severity, false);
    }

    private void recordNotification(Subscription sub, Tenant tenant, String notifType, String cycleKey,
                                    String title, String message, String severity, boolean emailSent) {
        try {
            SubscriptionNotification notif = SubscriptionNotification.builder()
                    .subscription(sub)
                    .tenant(tenant)
                    .notificationType(notifType)
                    .billingCycleKey(cycleKey)
                    .title(title)
                    .message(message)
                    .severity(severity)
                    .emailSent(emailSent)
                    .dismissed(false)
                    .createdAt(OffsetDateTime.now())
                    .build();
            notificationRepository.save(notif);
        } catch (DataIntegrityViolationException ignored) {}
    }

    @Transactional
    public void dismissNotification(Long tenantId, Long notificationId) {
        notificationRepository.findByIdAndTenantId(notificationId, tenantId).ifPresent(n -> {
            n.setDismissed(true);
            notificationRepository.save(n);
        });
    }

    // ==========================================
    // 5. Tenant View DTO Builder
    // ==========================================

    @Transactional
    public SubscriptionDto.TenantView buildTenantView(Long tenantId) {
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));

        Subscription sub = subscriptions.findByTenantId(tenantId)
                .orElseGet(() -> grantTrialIfEligible(tenant));

        if (sub == null) {
            return new SubscriptionDto.TenantView(
                    false, "NONE", "No Subscription", null, "None", 0L, "UGX",
                    LocalDate.now(), LocalDate.now(), null, null, null,
                    0, 0, 0, false, false, false, false, true, "",
                    List.of(), List.of(), List.of(), List.of(), List.of()
            );
        }

        sub = refreshLifecycle(sub);

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expires = sub.getExpiresAt() != null
                ? sub.getExpiresAt()
                : sub.getNextRenewalDate().atStartOfDay(TIMEZONE_KAMPALA).toOffsetDateTime();

        long totalSecondsRemaining = Math.max(0, ChronoUnit.SECONDS.between(now, expires));
        long daysRemaining = totalSecondsRemaining / 86400;
        long hoursRemaining = (totalSecondsRemaining % 86400) / 3600;
        long minutesRemaining = (totalSecondsRemaining % 3600) / 60;

        boolean inTrial = Subscription.STATUS_TRIALING.equals(sub.getStatus());
        boolean inGrace = Subscription.STATUS_GRACE_PERIOD.equals(sub.getStatus());
        boolean isExpired = inGrace || Subscription.STATUS_SUSPENDED.equals(sub.getStatus());
        boolean isSuspended = Subscription.STATUS_SUSPENDED.equals(sub.getStatus());
        boolean canAcceptCustomerPayments = canTenantAcceptCustomerPayments(tenantId);

        String reminderMessage = "";
        if (inGrace) {
            long graceHours = Math.max(0, ChronoUnit.HOURS.between(now, sub.getGraceUntil()));
            reminderMessage = "Your subscription has expired. New customer payments and management tools are paused. Grace period ends in "
                    + (graceHours > 24 ? (graceHours / 24) + " days." : graceHours + " hours.");
        } else if (isSuspended) {
            reminderMessage = "Your account is suspended. Renew now to immediately reactivate customer payments and management.";
        } else if (daysRemaining <= 5) {
            reminderMessage = (inTrial ? "Your free trial expires in " : "Your subscription renews in ")
                    + daysRemaining + " day" + (daysRemaining == 1 ? "" : "s") + " on "
                    + expires.atZoneSameInstant(TIMEZONE_KAMPALA).toLocalDate() + ".";
        }

        List<SubscriptionDto.PlanResponse> availablePlans = plans.findAllByActiveTrueOrderByDisplayOrderAscPriceAsc().stream()
                .map(p -> new SubscriptionDto.PlanResponse(p.getId(), p.getName(), p.getPrice(), p.getDurationUnit(), p.getDurationValue(), p.getDescription(), p.getActive(), p.getDisplayOrder()))
                .toList();

        List<SubscriptionDto.PeriodHistoryView> periodHistories = periodHistoryRepository.findAllByTenantIdOrderByPeriodStartDesc(tenantId).stream()
                .map(h -> new SubscriptionDto.PeriodHistoryView(
                        h.getId(), h.getPlanName(), h.getPeriodType(), h.getPeriodStart().toString(),
                        h.getPeriodEnd().toString(), h.getDurationDays(), h.getAmountCharged(),
                        h.getStatus(), h.getPaymentDate() != null ? h.getPaymentDate().toString() : null,
                        h.getPaymentReference(), h.getNote()))
                .toList();

        // Payment History from checkouts and recorded payments
        List<SubscriptionDto.PaymentHistoryView> paymentHistories = checkouts.findAllByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(c -> new SubscriptionDto.PaymentHistoryView(
                        c.getId(),
                        c.getCreatedAt().toString(),
                        c.getReference(),
                        c.getExternalReference(),
                        c.getPlan() != null ? c.getPlan().getName() : "SaaS Subscription",
                        c.getAmount(),
                        PAID_FROM_WALLET.equals(c.getPhone()) ? "Wallet balance" : maskPhone(c.getPhone()),
                        PAID_FROM_WALLET.equals(c.getPhone()) ? "WALLET" : "MOBILE_MONEY",
                        c.getStatus(),
                        c.getFailureReason()))
                .toList();

        List<SubscriptionDto.InvoiceView> invViews = invoices.findAllBySubscriptionIdOrderByPeriodStartDesc(sub.getId()).stream()
                .map(i -> new SubscriptionDto.InvoiceView(i.getId(), i.getPeriodStart().toString(), i.getPeriodEnd().toString(), i.getAmountDue(), i.getStatus(), i.getPaidAt() != null ? i.getPaidAt().toString() : null))
                .toList();

        List<SubscriptionDto.NotificationView> notifViews = notificationRepository.findAllByTenantIdAndDismissedFalseOrderByCreatedAtDesc(tenantId).stream()
                .map(n -> new SubscriptionDto.NotificationView(n.getId(), n.getNotificationType(), n.getTitle(), n.getMessage(), n.getSeverity(), n.getEmailSent(), n.getDismissed(), n.getCreatedAt()))
                .toList();

        return new SubscriptionDto.TenantView(
                true,
                sub.getStatus(),
                formatDisplayStatus(sub.getStatus()),
                sub.getPlan() != null ? sub.getPlan().getId() : null,
                sub.getPlan() != null ? sub.getPlan().getName() : "Standard Subscription",
                sub.getMonthlyFee(),
                "UGX",
                sub.getStartDate(),
                sub.getNextRenewalDate(),
                sub.getExpiresAt(),
                sub.getTrialEndsAt(),
                sub.getGraceUntil(),
                daysRemaining,
                hoursRemaining,
                minutesRemaining,
                inTrial,
                inGrace,
                isExpired,
                isSuspended,
                canAcceptCustomerPayments,
                reminderMessage,
                availablePlans,
                periodHistories,
                paymentHistories,
                invViews,
                notifViews
        );
    }

    private String maskPhone(String p) {
        if (p == null || p.length() < 7) return p;
        int len = p.length();
        return p.substring(0, len - 4).replaceAll("\\d", "•") + p.substring(len - 4);
    }

    private String formatDisplayStatus(String status) {
        return switch (status) {
            case Subscription.STATUS_TRIALING -> "Free Trial";
            case Subscription.STATUS_ACTIVE -> "Active";
            case Subscription.STATUS_GRACE_PERIOD -> "Grace Period";
            case Subscription.STATUS_SUSPENDED -> "Suspended";
            case Subscription.STATUS_CANCELLED -> "Cancelled";
            default -> status;
        };
    }

    // ==========================================
    // 6. Super Admin Extensions & Management
    // ==========================================

    @Transactional
    public Subscription grantManualExtension(Long subscriptionId, int days, String reason, boolean complimentary) {
        Subscription sub = get(subscriptionId);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime base = sub.getExpiresAt() != null && sub.getExpiresAt().isAfter(now)
                ? sub.getExpiresAt()
                : now;

        OffsetDateTime newExpires = base.plusDays(days);
        OffsetDateTime newGrace = newExpires.plusDays(GRACE_DAYS);
        LocalDate startLocalDate = base.atZoneSameInstant(TIMEZONE_KAMPALA).toLocalDate();
        LocalDate endLocalDate = newExpires.atZoneSameInstant(TIMEZONE_KAMPALA).toLocalDate();

        sub.setExpiresAt(newExpires);
        sub.setGraceUntil(newGrace);
        sub.setNextRenewalDate(endLocalDate);
        sub.setStatus(Subscription.STATUS_ACTIVE);
        sub.setAdminNote((complimentary ? "[COMPLIMENTARY EXTENSION] " : "[ADMIN OVERRIDE] ") + reason);
        sub = subscriptions.save(sub);

        // Record in Period History
        SubscriptionPeriodHistory hist = SubscriptionPeriodHistory.builder()
                .tenant(sub.getTenant())
                .subscription(sub)
                .planName(sub.getPlan() != null ? sub.getPlan().getName() : "SaaS Plan")
                .periodType(complimentary ? "COMPLIMENTARY_EXTENSION" : "ADMIN_OVERRIDE")
                .periodStart(startLocalDate)
                .periodEnd(endLocalDate)
                .durationDays(days)
                .amountCharged(0L)
                .status("ACTIVE")
                .note(reason)
                .build();
        periodHistoryRepository.save(hist);

        recordNotification(
                sub,
                sub.getTenant(),
                "EXTENSION",
                "ext-" + UUID.randomUUID(),
                (complimentary ? "Complimentary Extension Granted" : "Subscription Extended"),
                "Your subscription has been extended by " + days + " days until " + endLocalDate + ".",
                "SUCCESS"
        );

        return sub;
    }

    @Transactional
    public Subscription assignPlan(Long subscriptionId, Long planId) {
        Subscription sub = get(subscriptionId);
        SubscriptionPlan plan = plans.findById(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));

        sub.setPlan(plan);
        sub.setMonthlyFee(plan.getPrice());
        sub.setAdminNote(Optional.ofNullable(sub.getAdminNote()).orElse("") + " [Plan changed to " + plan.getName() + "]");
        return subscriptions.save(sub);
    }

    private String toE164(String phone) {
        String clean = phone.replaceAll("[^0-9+]", "");
        if (clean.startsWith("+")) return clean;
        if (clean.startsWith("256")) return "+" + clean;
        if (clean.startsWith("0")) return "+256" + clean.substring(1);
        return "+256" + clean;
    }

    public Subscription get(Long id) {
        return subscriptions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found"));
    }

    public List<Subscription> all() { return subscriptions.findAll(); }

    public long defaultFee() {
        return settings.findById(KEY_DEFAULT_FEE)
                .map(s -> Long.parseLong(s.getValue())).orElse(FALLBACK_DEFAULT_FEE);
    }

    public long updateDefaultFee(long fee) {
        if (fee < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fee must be non-negative");
        PlatformSetting s = settings.findById(KEY_DEFAULT_FEE).orElseGet(() -> PlatformSetting.builder().key(KEY_DEFAULT_FEE).build());
        s.setValue(Long.toString(fee));
        settings.save(s);
        return fee;
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
