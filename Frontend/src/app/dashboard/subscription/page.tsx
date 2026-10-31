"use client";

import React, { useEffect, useState, useMemo } from "react";
import api from "@/lib/api";
import { Table, Badge, Notice, panel, button, buttonGhost, field, label, hint, message } from "@/components/BillingUI";
import { Dialog } from "@/components/ui/Dialog";
import { BusyLabel, DashboardLoading } from "@/components/LoadingUI";
import {
  CalendarCheck,
  CreditCard,
  WarningCircle,
  CheckCircle,
  ClockCountdown,
  ShieldCheck,
  X,
  Bell,
  ArrowRight,
  FileText,
  Clock,
  Star
} from "@/components/icons";

interface PlanOption {
  id: number;
  name: string;
  price: number;
  durationUnit: string;
  durationValue: number;
  description: string;
  active: boolean;
  displayOrder: number;
}

interface PeriodHistoryItem {
  id: number;
  planName: string;
  periodType: string; // FREE_TRIAL, PAID_SUBSCRIPTION, COMPLIMENTARY_EXTENSION, ADMIN_OVERRIDE
  periodStart: string;
  periodEnd: string;
  durationDays: number;
  amountCharged: number;
  status: string;
  paymentDate?: string | null;
  paymentReference?: string | null;
  note?: string | null;
}

interface PaymentHistoryItem {
  id: number;
  paidAt: string;
  reference: string;
  externalRef?: string | null;
  planName: string;
  amount: number;
  phone: string;
  method: string;
  status: string; // SUCCESSFUL, PENDING, FAILED, CANCELLED
  note?: string | null;
}

interface InvoiceView {
  id: number;
  periodStart: string;
  periodEnd: string;
  amountDue: number;
  status: string;
  paidAt?: string | null;
}

interface NotificationView {
  id: number;
  notificationType: string;
  title: string;
  message: string;
  severity: string;
  emailSent: boolean;
  dismissed: boolean;
  createdAt: string;
}

interface TenantSubView {
  subscribed: boolean;
  status: string;
  displayStatus: string;
  planId?: number | null;
  planName?: string;
  price?: number;
  currency?: string;
  startDate?: string;
  nextRenewalDate?: string;
  expiresAt?: string;
  trialEndsAt?: string;
  graceUntil?: string;
  daysRemaining: number;
  hoursRemaining: number;
  minutesRemaining: number;
  inTrial: boolean;
  inGrace: boolean;
  isExpired: boolean;
  isSuspended: boolean;
  canAcceptCustomerPayments: boolean;
  reminderMessage?: string;
  availablePlans: PlanOption[];
  periodHistory: PeriodHistoryItem[];
  paymentHistory: PaymentHistoryItem[];
  invoices: InvoiceView[];
  notifications: NotificationView[];
}

const TONE: Record<string, string> = {
  ACTIVE: "green",
  TRIALING: "blue",
  GRACE_PERIOD: "amber",
  SUSPENDED: "red",
  CANCELLED: "slate",
  SUCCESSFUL: "green",
  PENDING: "amber",
  FAILED: "red",
  PAID: "green",
  UNPAID: "amber",
};

const PERIOD_TYPE_NAME: Record<string, string> = {
  FREE_TRIAL: "7-Day Free Trial",
  PAID_SUBSCRIPTION: "Paid Subscription",
  COMPLIMENTARY_EXTENSION: "Complimentary Extension",
  ADMIN_OVERRIDE: "Admin Extension",
};

const money = (v?: number | null) => `UGX ${(v ?? 0).toLocaleString()}`;
const d = (s?: string | null) => (s ? new Date(s).toLocaleDateString("en-UG", { timeZone: "Africa/Kampala" }) : "—");
const dt = (s?: string | null) =>
  s
    ? new Date(s).toLocaleString("en-UG", {
        timeZone: "Africa/Kampala",
        month: "short",
        day: "numeric",
        year: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      })
    : "—";

export default function SubscriptionHubPage() {
  const [sub, setSub] = useState<TenantSubView | null>(null);
  const [err, setErr] = useState("");
  const [ok, setOk] = useState("");
  const [loading, setLoading] = useState(true);

  // Live countdown state
  const [nowTimestamp, setNowTimestamp] = useState<number>(Date.now());

  // Checkout modal
  const [checkoutOpen, setCheckoutOpen] = useState(false);
  const [selectedPlan, setSelectedPlan] = useState<PlanOption | null>(null);
  const [phone, setPhone] = useState("");
  const [checkoutBusy, setCheckoutBusy] = useState(false);
  const [checkoutMsg, setCheckoutMsg] = useState<{ text: string; tone: "info" | "success" | "danger" } | null>(null);
  const [activeReference, setActiveReference] = useState<string | null>(null);

  // Receipt modal
  const [selectedReceipt, setSelectedReceipt] = useState<PaymentHistoryItem | null>(null);

  // Ticking countdown timer
  useEffect(() => {
    const timer = setInterval(() => {
      setNowTimestamp(Date.now());
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  const load = async () => {
    try {
      const res = await api.get<TenantSubView>("/api/subscription");
      setSub(res.data);
      if (res.data.availablePlans && res.data.availablePlans.length > 0) {
        const current = res.data.availablePlans.find((p) => p.id === res.data.planId) || res.data.availablePlans[0];
        setSelectedPlan(current);
      }
    } catch (e) {
      setErr(message(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  // Poll for payment confirmation if reference is active
  useEffect(() => {
    if (!activeReference || !checkoutOpen) return;
    const interval = setInterval(async () => {
      try {
        const res = await api.get<TenantSubView>("/api/subscription");
        setSub(res.data);
        if (!res.data.isExpired && res.data.status === "ACTIVE") {
          setCheckoutMsg({
            text: "Payment received! Your subscription has been activated successfully.",
            tone: "success",
          });
          setActiveReference(null);
          setOk("Subscription renewed! All customer payments and router tools are fully unlocked.");
        }
      } catch (e) {
        // ignore poll errors
      }
    }, 3500);

    return () => clearInterval(interval);
  }, [activeReference, checkoutOpen]);

  // Live countdown calculation
  const liveCountdown = useMemo(() => {
    if (!sub || !sub.expiresAt) return { days: 0, hours: 0, minutes: 0, seconds: 0, isPast: true };
    const expiryTime = new Date(sub.expiresAt).getTime();
    const diff = expiryTime - nowTimestamp;
    if (diff <= 0) {
      return { days: 0, hours: 0, minutes: 0, seconds: 0, isPast: true };
    }
    const days = Math.floor(diff / (1000 * 60 * 60 * 24));
    const hours = Math.floor((diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60));
    const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60));
    const seconds = Math.floor((diff % (1000 * 60)) / 1000);
    return { days, hours, minutes, seconds, isPast: false };
  }, [sub, nowTimestamp]);

  const handleStartCheckout = (plan?: PlanOption) => {
    if (plan) setSelectedPlan(plan);
    setCheckoutMsg(null);
    setActiveReference(null);
    setCheckoutOpen(true);
  };

  const handleInitiatePayment = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedPlan) return;
    if (!phone || phone.trim().length < 9) {
      setCheckoutMsg({ text: "Please enter a valid Mobile Money phone number.", tone: "danger" });
      return;
    }

    setCheckoutBusy(true);
    setCheckoutMsg({ text: "Initiating Mobile Money prompt...", tone: "info" });
    try {
      const res = await api.post<{ reference: string; message: string; status: string }>("/api/subscription/checkout", {
        planId: selectedPlan.id,
        phone: phone.trim(),
      });
      setActiveReference(res.data.reference);
      setCheckoutMsg({
        text: res.data.message || `USSD prompt sent to ${phone}. Enter your PIN to approve UGX ${selectedPlan.price.toLocaleString()}.`,
        tone: "info",
      });
    } catch (e) {
      setCheckoutMsg({ text: message(e), tone: "danger" });
    } finally {
      setCheckoutBusy(false);
    }
  };

  const handleDismissNotification = async (id: number) => {
    try {
      await api.post(`/api/subscription/notifications/${id}/dismiss`);
      if (sub) {
        setSub({
          ...sub,
          notifications: sub.notifications.filter((n) => n.id !== id),
        });
      }
    } catch (e) {
      // ignore
    }
  };

  if (loading) {
    return (
      <DashboardLoading label="Loading your subscription…" />
    );
  }

  if (err) return <Notice text={err} />;
  if (!sub) return null;

  return (
    <div className="space-y-8 pb-12">
      {/* Page Title & Action Bar */}
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-[1.65rem] font-bold leading-tight tracking-[-0.02em] text-[var(--fg)]">Subscription &amp; Billing Hub</h1>
          <p className="mt-1 text-xs text-[var(--fg-muted)]">
            Review your SaaS subscription, track live renewal time, inspect billing history, and pay via Mobile Money.
          </p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={() => handleStartCheckout()}
            className={`${button} bg-blue-600 hover:bg-blue-500 text-white font-bold px-5 py-2.5 text-xs shadow-md transition`}
          >
            <CreditCard size={15} />
            {sub.isExpired ? "Renew Subscription Now" : "Renew / Upgrade Plan"}
          </button>
        </div>
      </div>

      {ok && (
        <div className="flex items-center justify-between rounded-xl border border-emerald-300 bg-emerald-50 px-4 py-3.5 text-xs font-semibold text-emerald-800 shadow-sm">
          <span className="flex items-center gap-2">
            <CheckCircle size={16} /> {ok}
          </span>
          <button onClick={() => setOk("")} className="text-emerald-700 hover:text-emerald-900">
            <X size={15} />
          </button>
        </div>
      )}

      {/* Prominent Expiration Warning / Grace Banner */}
      {sub.isExpired && (
        <div className="rounded-xl border border-red-300 bg-red-50 p-5 text-red-900 shadow-sm">
          <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
            <div className="flex items-start gap-3">
              <WarningCircle size={24} className="shrink-0 text-red-600 mt-0.5 animate-pulse" />
              <div>
                <h3 className="font-bold text-sm text-red-900">
                  {sub.inGrace ? "Subscription Expired — 2-Day Grace Period Active" : "Account Suspended: Renewal Required"}
                </h3>
                <p className="mt-1 text-xs text-red-800 leading-relaxed">
                  {sub.inGrace
                    ? "Your subscription expired. New customer payments and package/voucher generation are temporarily paused. Active customer sessions and router tunnels remain connected."
                    : "Your 2-day grace period has ended. Router management and customer payments are disabled. Renew now to automatically unlock all features."}
                </p>
              </div>
            </div>
            <button
              onClick={() => handleStartCheckout()}
              className="shrink-0 rounded-lg bg-red-600 hover:bg-red-700 text-white px-4 py-2 text-xs font-bold shadow transition"
            >
              Renew &amp; Unlock Now
            </button>
          </div>
        </div>
      )}

      {/* SECTION 1: PROMINENT SUBSCRIPTION OVERVIEW HERO CARD */}
      <div className="relative overflow-hidden rounded-2xl border border-[var(--border)] bg-gradient-to-br from-[var(--surface)] via-[var(--surface)] to-[var(--subtle)] p-6 shadow-md md:p-8">
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-12 lg:items-center">
          {/* Plan & Status Info */}
          <div className="lg:col-span-7 space-y-4">
            <div className="flex flex-wrap items-center gap-2.5">
              <span className="text-xs font-bold uppercase tracking-wider text-[var(--fg-muted)]">Your Plan</span>
              <Badge tone={TONE[sub.status] || "slate"}>
                {sub.displayStatus}
              </Badge>
              {sub.canAcceptCustomerPayments ? (
                <span className="flex items-center gap-1 rounded-full bg-emerald-100 px-2.5 py-0.5 text-[10px] font-bold text-emerald-800 dark:bg-emerald-950/60 dark:text-emerald-700">
                  <CheckCircle size={11} /> Payments Active
                </span>
              ) : (
                <span className="flex items-center gap-1 rounded-full bg-red-100 px-2.5 py-0.5 text-[10px] font-bold text-red-800 dark:bg-red-950/60 dark:text-red-700">
                  <WarningCircle size={11} /> Payments Paused
                </span>
              )}
            </div>

            <div>
              <h2 className="text-2xl font-black text-[var(--fg)] tracking-tight sm:text-3xl">
                {sub.planName}
              </h2>
              <p className="mt-1 text-sm font-semibold text-[var(--fg-muted)]">
                {money(sub.price)} <span className="font-normal text-xs text-[var(--fg-faint)]">/ billing cycle</span>
              </p>
            </div>

            <div className="grid grid-cols-2 gap-4 pt-2 sm:grid-cols-3">
              <div>
                <span className="text-[11px] font-medium text-[var(--fg-faint)]">Activated On</span>
                <p className="mt-0.5 text-xs font-bold text-[var(--fg)]">{d(sub.startDate)}</p>
              </div>
              <div>
                <span className="text-[11px] font-medium text-[var(--fg-faint)]">Next Renewal / Expiry</span>
                <p className="mt-0.5 text-xs font-bold text-[var(--fg)]">{d(sub.expiresAt || sub.nextRenewalDate)}</p>
              </div>
              <div>
                <span className="text-[11px] font-medium text-[var(--fg-faint)]">Grace Period Window</span>
                <p className="mt-0.5 text-xs font-bold text-[var(--fg)]">{d(sub.graceUntil)}</p>
              </div>
            </div>
          </div>

          {/* Prominent Live Countdown Box */}
          <div className="lg:col-span-5">
            <div className={`rounded-xl border p-5 text-center shadow-inner ${
              sub.isExpired
                ? "border-red-300 bg-red-50/50 dark:bg-red-950/20"
                : liveCountdown.days <= 2
                ? "border-amber-300 bg-amber-50/50 dark:bg-amber-950/20"
                : "border-blue-200 bg-blue-50/40 dark:bg-blue-950/20"
            }`}>
              <div className="flex items-center justify-center gap-1.5 text-xs font-bold uppercase tracking-wider text-[var(--fg-muted)]">
                <ClockCountdown size={15} /> Time Remaining
              </div>

              {sub.isExpired ? (
                <div className="my-3">
                  <span className="text-3xl font-black text-red-600">00:00:00</span>
                  <p className="mt-1 text-xs font-semibold text-red-700">
                    {sub.inGrace ? "Expired — Grace period running" : "Suspended"}
                  </p>
                </div>
              ) : (
                <div className="my-3 grid grid-cols-4 gap-2">
                  <div className="rounded-lg bg-[var(--surface)] p-2 shadow-sm border border-[var(--border-soft)]">
                    <span className="text-2xl font-black text-[var(--fg)]">{liveCountdown.days}</span>
                    <span className="block text-[10px] font-medium text-[var(--fg-faint)] uppercase">Days</span>
                  </div>
                  <div className="rounded-lg bg-[var(--surface)] p-2 shadow-sm border border-[var(--border-soft)]">
                    <span className="text-2xl font-black text-[var(--fg)]">{liveCountdown.hours}</span>
                    <span className="block text-[10px] font-medium text-[var(--fg-faint)] uppercase">Hours</span>
                  </div>
                  <div className="rounded-lg bg-[var(--surface)] p-2 shadow-sm border border-[var(--border-soft)]">
                    <span className="text-2xl font-black text-[var(--fg)]">{liveCountdown.minutes}</span>
                    <span className="block text-[10px] font-medium text-[var(--fg-faint)] uppercase">Mins</span>
                  </div>
                  <div className="rounded-lg bg-[var(--surface)] p-2 shadow-sm border border-[var(--border-soft)]">
                    <span className="text-2xl font-black text-[var(--fg)]">{liveCountdown.seconds}</span>
                    <span className="block text-[10px] font-medium text-[var(--fg-faint)] uppercase">Secs</span>
                  </div>
                </div>
              )}

              <p className="text-[11px] text-[var(--fg-muted)]">
                Early renewal stacks onto remaining days. Never lose active paid time.
              </p>
              <button
                onClick={() => handleStartCheckout()}
                className="mt-3 w-full rounded-lg bg-blue-600 hover:bg-blue-500 text-white py-2 text-xs font-bold transition shadow"
              >
                Renew / Extend Access
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* SECTION 2: AVAILABLE SUBSCRIPTION PLANS */}
      <div className={panel}>
        <div className="border-b border-[var(--border-soft)] px-5 py-4">
          <h2 className="text-sm font-bold text-[var(--fg)]">Available Subscription Plans</h2>
          <p className="text-xs text-[var(--fg-muted)]">
            Choose a plan configured by Super Admin. Self-service renewal with MTN or Airtel Mobile Money.
          </p>
        </div>
        <div className="grid grid-cols-1 gap-4 p-5 md:grid-cols-2 lg:grid-cols-3">
          {sub.availablePlans.map((p) => {
            const isCurrent = p.id === sub.planId;
            return (
              <div
                key={p.id}
                className={`relative flex flex-col justify-between rounded-xl border p-5 transition ${
                  isCurrent
                    ? "border-blue-500 bg-blue-50/20 dark:bg-blue-950/20 shadow-sm"
                    : "border-[var(--border-soft)] hover:border-[var(--border-strong)]"
                }`}
              >
                {isCurrent && (
                  <span className="absolute -top-2.5 right-4 rounded-full bg-blue-600 px-2.5 py-0.5 text-[10px] font-bold text-white uppercase tracking-wider shadow-sm">
                    Current Plan
                  </span>
                )}
                <div>
                  <h3 className="font-bold text-[var(--fg)] text-base">{p.name}</h3>
                  <div className="mt-2 flex items-baseline gap-1">
                    <span className="text-2xl font-black text-[var(--fg)]">{money(p.price)}</span>
                    <span className="text-xs text-[var(--fg-muted)]">
                      / {p.durationValue} {p.durationUnit.toLowerCase()}
                    </span>
                  </div>
                  {p.description && (
                    <p className="mt-3 text-xs text-[var(--fg-muted)] leading-relaxed">{p.description}</p>
                  )}
                </div>
                <button
                  onClick={() => handleStartCheckout(p)}
                  className={`mt-5 w-full ${button} ${
                    isCurrent ? "bg-blue-600 hover:bg-blue-500" : "bg-slate-800 hover:bg-slate-700"
                  } text-white text-xs font-semibold`}
                >
                  Pay {money(p.price)}
                </button>
              </div>
            );
          })}
        </div>
      </div>

      {/* SECTION 3: SUBSCRIPTION PERIOD HISTORY */}
      <div className={panel}>
        <div className="border-b border-[var(--border-soft)] px-5 py-4">
          <h2 className="text-sm font-bold text-[var(--fg)]">Subscription Period History</h2>
          <p className="text-xs text-[var(--fg-muted)]">
            Every subscription period granted or purchased (Free Trial, Renewals, and Extensions).
          </p>
        </div>
        {/* Desktop View Table */}
        <div className="hidden md:block">
          <Table
            rows={sub.periodHistory || []}
            emptyTitle="No subscription history recorded yet."
            columns={[
              {
                label: "Plan / Grant",
                render: (h) => (
                  <div>
                    <span className="font-bold text-[var(--fg)]">{h.planName}</span>
                    <span className="block text-[11px] text-[var(--fg-muted)]">
                      {PERIOD_TYPE_NAME[h.periodType] || h.periodType}
                    </span>
                  </div>
                ),
              },
              {
                label: "Period Range",
                render: (h) => (
                  <span className="text-xs text-[var(--fg-body)]">
                    {d(h.periodStart)} → {d(h.periodEnd)} ({h.durationDays} days)
                  </span>
                ),
              },
              {
                label: "Fee Paid",
                render: (h) => (
                  <span className="font-semibold text-xs text-[var(--fg)]">{money(h.amountCharged)}</span>
                ),
              },
              {
                label: "Payment Reference",
                render: (h) => (
                  <span className="font-mono text-xs text-[var(--fg-muted)]">
                    {h.paymentReference || "—"}
                  </span>
                ),
              },
              {
                label: "Status",
                render: (h) => <Badge tone={TONE[h.status] || "slate"}>{h.status}</Badge>,
              },
            ]}
          />
        </div>

        {/* Mobile View Cards */}
        <div className="divide-y divide-[var(--border-soft)] md:hidden">
          {sub.periodHistory && sub.periodHistory.length > 0 ? (
            sub.periodHistory.map((h) => (
              <div key={h.id} className="p-4 space-y-2 text-xs">
                <div className="flex justify-between items-start">
                  <div>
                    <span className="font-bold text-[var(--fg)]">{h.planName}</span>
                    <span className="block text-[11px] text-[var(--fg-muted)]">
                      {PERIOD_TYPE_NAME[h.periodType] || h.periodType}
                    </span>
                  </div>
                  <Badge tone={TONE[h.status] || "slate"}>{h.status}</Badge>
                </div>
                <div className="flex justify-between text-[var(--fg-muted)]">
                  <span>Range: {d(h.periodStart)} → {d(h.periodEnd)}</span>
                  <span className="font-bold text-[var(--fg)]">{money(h.amountCharged)}</span>
                </div>
                {h.paymentReference && (
                  <div className="font-mono text-[10px] text-[var(--fg-faint)]">
                    Ref: {h.paymentReference}
                  </div>
                )}
              </div>
            ))
          ) : (
            <p className="p-6 text-center text-xs text-[var(--fg-muted)]">No subscription history recorded yet.</p>
          )}
        </div>
      </div>

      {/* SECTION 4: PAYMENT RECEIPTS & HISTORY */}
      <div className={panel}>
        <div className="border-b border-[var(--border-soft)] px-5 py-4">
          <h2 className="text-sm font-bold text-[var(--fg)]">Subscription Payment Receipts</h2>
          <p className="text-xs text-[var(--fg-muted)]">
            Verified Mobile Money transactions for your SaaS platform access.
          </p>
        </div>
        {/* Desktop View Table */}
        <div className="hidden md:block">
          <Table
            rows={sub.paymentHistory || []}
            emptyTitle="No subscription payments recorded yet."
            columns={[
              {
                label: "Date",
                render: (p) => <span className="text-xs text-[var(--fg-muted)]">{dt(p.paidAt)}</span>,
              },
              {
                label: "Transaction Reference",
                render: (p) => <span className="font-mono text-xs font-semibold">{p.reference}</span>,
              },
              {
                label: "Plan",
                render: (p) => <span className="text-xs text-[var(--fg-body)]">{p.planName}</span>,
              },
              {
                label: "Amount",
                render: (p) => <span className="font-bold text-xs text-[var(--fg)]">{money(p.amount)}</span>,
              },
              {
                label: "Phone",
                render: (p) => <span className="font-mono text-xs text-[var(--fg-muted)]">{p.phone}</span>,
              },
              {
                label: "Status",
                render: (p) => <Badge tone={TONE[p.status] || "slate"}>{p.status}</Badge>,
              },
              {
                label: "Receipt",
                render: (p) => (
                  <button
                    onClick={() => setSelectedReceipt(p)}
                    className="inline-flex items-center gap-1 text-xs font-semibold text-blue-600 hover:text-blue-500"
                  >
                    <FileText size={13} /> View Receipt
                  </button>
                ),
              },
            ]}
          />
        </div>

        {/* Mobile View Cards */}
        <div className="divide-y divide-[var(--border-soft)] md:hidden">
          {sub.paymentHistory && sub.paymentHistory.length > 0 ? (
            sub.paymentHistory.map((p) => (
              <div key={p.id} className="p-4 space-y-2 text-xs">
                <div className="flex justify-between items-start">
                  <div>
                    <span className="font-mono font-bold text-[var(--fg)]">{p.reference}</span>
                    <span className="block text-[11px] text-[var(--fg-muted)]">{p.planName}</span>
                  </div>
                  <Badge tone={TONE[p.status] || "slate"}>{p.status}</Badge>
                </div>
                <div className="flex justify-between text-[var(--fg-muted)]">
                  <span>{dt(p.paidAt)}</span>
                  <span className="font-bold text-[var(--fg)]">{money(p.amount)}</span>
                </div>
                <div className="flex justify-between items-center pt-1">
                  <span className="font-mono text-[11px] text-[var(--fg-faint)]">{p.phone}</span>
                  <button
                    onClick={() => setSelectedReceipt(p)}
                    className="text-xs font-semibold text-blue-600 hover:underline"
                  >
                    View Receipt
                  </button>
                </div>
              </div>
            ))
          ) : (
            <p className="p-6 text-center text-xs text-[var(--fg-muted)]">No payments recorded yet.</p>
          )}
        </div>
      </div>

      {/* SECTION 5: SUBSCRIPTION NOTIFICATIONS & REMINDER AUDIT */}
      <div className={panel}>
        <div className="border-b border-[var(--border-soft)] px-5 py-4">
          <h2 className="text-sm font-bold text-[var(--fg)]">Subscription Notifications &amp; Alerts</h2>
          <p className="text-xs text-[var(--fg-muted)]">
            Official expiration warnings, renewal receipts, and grace period alerts.
          </p>
        </div>
        <div className="p-5 space-y-3">
          {sub.notifications && sub.notifications.length > 0 ? (
            sub.notifications.map((n) => (
              <div
                key={n.id}
                className={`flex items-start justify-between gap-3 rounded-xl border p-4 text-xs transition ${
                  n.severity === "DANGER"
                    ? "border-red-300 bg-red-50 text-red-900"
                    : n.severity === "WARNING"
                    ? "border-amber-300 bg-amber-50 text-amber-900"
                    : n.severity === "SUCCESS"
                    ? "border-emerald-300 bg-emerald-50 text-emerald-900"
                    : "border-blue-200 bg-blue-50 text-blue-900"
                }`}
              >
                <div className="flex items-start gap-3">
                  <Bell size={18} className="shrink-0 mt-0.5 text-current" />
                  <div>
                    <h4 className="font-bold text-xs">{n.title}</h4>
                    <p className="mt-0.5 text-[11px] leading-relaxed opacity-90">{n.message}</p>
                    <span className="mt-1 block text-[10px] opacity-75">{dt(n.createdAt)}</span>
                  </div>
                </div>
                <button
                  onClick={() => handleDismissNotification(n.id)}
                  title="Dismiss notification"
                  className="rounded p-1 opacity-70 hover:opacity-100 hover:bg-black/5"
                >
                  <X size={14} />
                </button>
              </div>
            ))
          ) : (
            <p className="py-6 text-center text-xs text-[var(--fg-muted)]">
              No active subscription notifications. Everything is in order.
            </p>
          )}
        </div>
      </div>

      {/* MODAL 1: MOBILE MONEY CHECKOUT MODAL */}
      {checkoutOpen && (
        <Dialog onClose={() => setCheckoutOpen(false)} title="Renew SaaS Subscription">
          <form onSubmit={handleInitiatePayment} className="space-y-4">
            <div>
              <label className={label}>Selected Subscription Plan</label>
              <div className="mt-1 flex items-center justify-between rounded-lg border border-[var(--border-strong)] bg-[var(--subtle)] p-3.5">
                <div>
                  <div className="font-bold text-[var(--fg)] text-sm">{selectedPlan?.name}</div>
                  <div className="text-xs text-[var(--fg-muted)]">
                    {selectedPlan?.durationValue} {selectedPlan?.durationUnit.toLowerCase()} access
                  </div>
                </div>
                <div className="text-right">
                  <div className="font-extrabold text-[var(--fg)] text-base">{money(selectedPlan?.price)}</div>
                  <div className="text-[10px] text-[var(--fg-muted)]">Official SaaS Platform Fee</div>
                </div>
              </div>
            </div>

            <div>
              <label className={label}>Mobile Money Phone Number</label>
              <input
                type="tel"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                placeholder="e.g. 0770000000 or 0750000000"
                className={field}
                required
              />
              <p className={hint}>MTN MoMo or Airtel Money. A PIN approval prompt will be sent directly to your phone.</p>
            </div>

            {checkoutMsg && (
              <div
                className={`rounded-lg border p-3.5 text-xs font-semibold leading-relaxed ${
                  checkoutMsg.tone === "danger"
                    ? "border-red-300 bg-red-50 text-red-800"
                    : checkoutMsg.tone === "success"
                    ? "border-emerald-300 bg-emerald-50 text-emerald-800"
                    : "border-blue-300 bg-blue-50 text-blue-800"
                }`}
              >
                {checkoutMsg.text}
              </div>
            )}

            <div className="mt-6 flex justify-end gap-2 border-t border-[var(--border-soft)] pt-4">
              <button
                type="button"
                onClick={() => setCheckoutOpen(false)}
                className={buttonGhost}
                disabled={checkoutBusy}
              >
                Close
              </button>
              <button
                type="submit"
                className={`${button} bg-blue-600 hover:bg-blue-500 text-white font-bold`}
                disabled={checkoutBusy}
              >
                <BusyLabel busy={checkoutBusy} label="Sending prompt…">{`Pay ${money(selectedPlan?.price)} via MoMo`}</BusyLabel>
              </button>
            </div>
          </form>
        </Dialog>
      )}

      {/* MODAL 2: RECEIPT VIEW MODAL */}
      {selectedReceipt && (
        <Dialog onClose={() => setSelectedReceipt(null)} title="Subscription Payment Receipt">
          <div className="space-y-4 text-xs">
            <div className="rounded-xl border border-emerald-300 bg-emerald-50 p-4 text-center">
              <CheckCircle size={32} className="mx-auto text-emerald-600" />
              <h3 className="mt-2 text-base font-extrabold text-emerald-950">Payment Confirmed</h3>
              <p className="text-xs text-emerald-800">{money(selectedReceipt.amount)} UGX</p>
            </div>

            <div className="divide-y divide-[var(--border-soft)] rounded-lg border border-[var(--border-soft)] bg-[var(--subtle)] p-3">
              <div className="flex justify-between py-2">
                <span className="text-[var(--fg-muted)]">Receipt Ref:</span>
                <span className="font-mono font-bold text-[var(--fg)]">{selectedReceipt.reference}</span>
              </div>
              <div className="flex justify-between py-2">
                <span className="text-[var(--fg-muted)]">Payment Method:</span>
                <span className="font-bold text-[var(--fg)]">{selectedReceipt.method} ({selectedReceipt.phone})</span>
              </div>
              <div className="flex justify-between py-2">
                <span className="text-[var(--fg-muted)]">Plan:</span>
                <span className="font-bold text-[var(--fg)]">{selectedReceipt.planName}</span>
              </div>
              <div className="flex justify-between py-2">
                <span className="text-[var(--fg-muted)]">Date &amp; Time:</span>
                <span className="font-medium text-[var(--fg)]">{dt(selectedReceipt.paidAt)}</span>
              </div>
              <div className="flex justify-between py-2">
                <span className="text-[var(--fg-muted)]">Status:</span>
                <Badge tone="green">{selectedReceipt.status}</Badge>
              </div>
            </div>

            <div className="flex justify-end pt-3">
              <button onClick={() => setSelectedReceipt(null)} className={buttonGhost}>
                Close
              </button>
            </div>
          </div>
        </Dialog>
      )}
    </div>
  );
}
