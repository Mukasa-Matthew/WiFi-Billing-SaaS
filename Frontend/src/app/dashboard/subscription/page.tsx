"use client";

import React, { useCallback, useEffect, useState } from "react";
import api from "@/lib/api";
import { Table, Badge, Notice, panel, button, buttonGhost, field, label, message, PageHeader, ugx, shortDate, date } from "@/components/BillingUI";
import { Dialog } from "@/components/ui/Dialog";
import { BusyLabel, DashboardLoading } from "@/components/LoadingUI";
import { CreditCard, WarningCircle, CheckCircle, X } from "@/components/icons";

interface PlanOption {
  id: number;
  name: string;
  price: number;
  durationUnit: string;
  durationValue: number;
  description: string;
}

interface PaymentHistoryItem {
  id: number;
  paidAt: string;
  reference: string;
  planName: string;
  amount: number;
  phone: string;
  method: string; // MOBILE_MONEY | WALLET
  status: string; // SUCCESSFUL, PENDING, FAILED, CANCELLED
}

interface TenantSubView {
  status: string;
  displayStatus: string;
  planId?: number | null;
  planName?: string;
  price?: number;
  startDate?: string;
  nextRenewalDate?: string;
  expiresAt?: string;
  graceUntil?: string;
  daysRemaining: number;
  hoursRemaining: number;
  inTrial: boolean;
  inGrace: boolean;
  isExpired: boolean;
  isSuspended: boolean;
  canAcceptCustomerPayments: boolean;
  availablePlans: PlanOption[];
  paymentHistory: PaymentHistoryItem[];
}

const STATUS_TONE: Record<string, string> = {
  ACTIVE: "green", TRIALING: "blue", GRACE_PERIOD: "amber", SUSPENDED: "red", CANCELLED: "slate",
};
const PAYMENT_STATUS: Record<string, { label: string; tone: string }> = {
  SUCCESSFUL: { label: "Paid", tone: "green" },
  PENDING: { label: "Pending", tone: "amber" },
  FAILED: { label: "Failed", tone: "red" },
  CANCELLED: { label: "Cancelled", tone: "slate" },
};

const period = (p: PlanOption) => `${p.durationValue} ${p.durationUnit.toLowerCase().replace(/s$/, "")}${p.durationValue === 1 ? "" : "s"}`;
const paidWith = (p: PaymentHistoryItem) => (p.method === "WALLET" ? "Wallet" : `Mobile money${p.phone && p.phone !== "WALLET" ? ` · ${p.phone}` : ""}`);
const timeLeft = (s: TenantSubView) =>
  s.daysRemaining >= 1 ? `${s.daysRemaining} day${s.daysRemaining === 1 ? "" : "s"} left`
    : s.hoursRemaining >= 1 ? `${s.hoursRemaining} hour${s.hoursRemaining === 1 ? "" : "s"} left`
    : "Less than an hour left";
const newKey = () =>
  globalThis.crypto?.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;

type Msg = { text: string; tone: "info" | "success" | "danger" };

export default function SubscriptionPage() {
  const [sub, setSub] = useState<TenantSubView | null>(null);
  const [err, setErr] = useState("");
  const [ok, setOk] = useState("");
  const [loading, setLoading] = useState(true);

  const [checkoutOpen, setCheckoutOpen] = useState(false);
  const [phone, setPhone] = useState("");
  const [busy, setBusy] = useState(false);
  const [checkoutMsg, setCheckoutMsg] = useState<Msg | null>(null);
  const [activeReference, setActiveReference] = useState<string | null>(null);
  const [payMethod, setPayMethod] = useState<"MOMO" | "WALLET">("MOMO");
  const [walletBalance, setWalletBalance] = useState<number | null>(null);
  // One key per wallet payment attempt: a double tap or a retry can never charge twice.
  const [walletKey, setWalletKey] = useState("");
  const [receipt, setReceipt] = useState<PaymentHistoryItem | null>(null);

  const load = useCallback(async () => {
    try { setSub((await api.get<TenantSubView>("/api/subscription")).data); }
    catch (e) { setErr(message(e)); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { const t = window.setTimeout(load, 0); return () => window.clearTimeout(t); }, [load]);

  const plan = sub?.availablePlans.find((p) => p.id === sub.planId) ?? null;

  // Poll this checkout's own outcome; the server asks Mobile Money directly while pending.
  useEffect(() => {
    if (!activeReference || !checkoutOpen) return;
    const startedAt = Date.now();
    const interval = setInterval(async () => {
      try {
        const res = await api.get<{ status: string; message: string }>(`/api/subscription/checkout/${encodeURIComponent(activeReference)}`);
        if (res.data.status === "SUCCESSFUL") {
          setActiveReference(null);
          setCheckoutMsg({ text: "Payment received. Your plan is renewed.", tone: "success" });
          setOk("Subscription renewed.");
          void load();
        } else if (res.data.status === "FAILED") {
          setActiveReference(null);
          setCheckoutMsg({ text: res.data.message || "The payment didn’t go through. Try again.", tone: "danger" });
        } else if (Date.now() - startedAt > 5 * 60 * 1000) {
          setActiveReference(null);
          setCheckoutMsg({ text: "Still waiting for Mobile Money. If you approved it, your plan will update in a few minutes.", tone: "info" });
        }
      } catch { /* keep polling */ }
    }, 3500);
    return () => clearInterval(interval);
  }, [activeReference, checkoutOpen, load]);

  const loadWallet = async (price?: number) => {
    try {
      const available = Number((await api.get<{ available: number }>("/api/wallet")).data.available || 0);
      setWalletBalance(available);
      if (price !== undefined) setPayMethod(available >= price ? "WALLET" : "MOMO");
    } catch { setWalletBalance(null); setPayMethod("MOMO"); }
  };

  const openCheckout = () => {
    setCheckoutMsg(null); setActiveReference(null); setWalletKey(newKey()); setCheckoutOpen(true);
    void loadWallet(plan?.price);
  };

  const walletCovers = walletBalance !== null && !!plan && walletBalance >= plan.price;

  const pay = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!plan || busy) return;
    setBusy(true);
    if (payMethod === "WALLET") {
      setCheckoutMsg(null);
      try {
        await api.post("/api/subscription/pay-from-wallet", { planId: plan.id }, { headers: { "Idempotency-Key": walletKey } });
        setCheckoutMsg({ text: "Paid from your wallet. Your plan is renewed.", tone: "success" });
        setOk("Subscription renewed.");
        setWalletKey(newKey());
        void load(); void loadWallet();
      } catch (err) {
        // With no answer the payment may have gone through: keep the key so a retry can't pay twice.
        const answered = !!(err as { response?: unknown })?.response;
        if (answered) { setWalletKey(newKey()); void loadWallet(); }
        setCheckoutMsg({ text: message(err), tone: "danger" });
      } finally { setBusy(false); }
      return;
    }
    if (phone.trim().length < 9) {
      setCheckoutMsg({ text: "Enter a valid mobile money number.", tone: "danger" });
      setBusy(false);
      return;
    }
    setCheckoutMsg(null);
    try {
      const res = await api.post<{ reference: string }>("/api/subscription/checkout", { planId: plan.id, phone: phone.trim() });
      setActiveReference(res.data.reference);
      setCheckoutMsg({ text: `Check your phone and enter your PIN to approve ${ugx(plan.price)}.`, tone: "info" });
    } catch (err) { setCheckoutMsg({ text: message(err), tone: "danger" }); }
    finally { setBusy(false); }
  };

  if (loading) return <DashboardLoading label="Loading your subscription…" />;
  if (err) return <Notice text={err} />;
  if (!sub) return null;

  const endingSoon = !sub.isExpired && sub.daysRemaining < 3;
  const endsOn = sub.expiresAt || sub.nextRenewalDate;
  const done = checkoutMsg?.tone === "success";

  return (
    <div className="space-y-6 pb-12">
      <PageHeader title="Subscription" description="Your AirPairy plan and payments."
        action={plan && (
          <button onClick={openCheckout} className={`${button} w-full sm:w-auto`}>
            <CreditCard size={15} /> {sub.isExpired ? "Renew now" : "Renew"}
          </button>
        )} />

      {ok && (
        <div role="status" className="flex items-center justify-between gap-3 rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm font-medium text-emerald-800">
          <span className="flex items-center gap-2"><CheckCircle size={16} /> {ok}</span>
          <button onClick={() => setOk("")} aria-label="Dismiss" className="rounded p-1 text-emerald-700 hover:bg-emerald-100"><X size={14} /></button>
        </div>
      )}

      {/* Expired/suspended states are covered by the workspace-wide red banner. */}
      {endingSoon && plan && (
        <div role="status" className="flex items-start gap-3 rounded-xl border border-amber-200 bg-amber-50 p-4 text-amber-900">
          <WarningCircle size={20} className="mt-0.5 shrink-0 text-amber-600" />
          <div>
            <p className="text-sm font-semibold">Your plan ends in {timeLeft(sub).replace(" left", "")}</p>
            <p className="mt-0.5 text-sm opacity-90">Renew now to avoid interruption. You won’t lose any remaining days.</p>
          </div>
        </div>
      )}

      {/* Current plan */}
      <section className={`${panel} p-6`} aria-label="Current plan">
        {plan ? (
          <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)] lg:items-center">
            <div>
              <div className="flex flex-wrap items-center gap-2">
                <h2 className="text-xl font-semibold tracking-tight text-[var(--fg)]">{sub.planName || plan.name}</h2>
                <Badge tone={STATUS_TONE[sub.status] || "slate"} dot>{sub.displayStatus}</Badge>
              </div>
              <p className="mt-1 text-sm text-[var(--fg-muted)]">
                <span className="num font-semibold text-[var(--fg)]">{ugx(sub.price ?? plan.price)}</span> every {period(plan)}
              </p>
            </div>
            <dl className="grid gap-4 sm:grid-cols-3">
              <div>
                <dt className="text-xs font-medium text-[var(--fg-muted)]">{sub.isExpired ? "Ended on" : sub.inTrial ? "Trial ends" : "Renews on"}</dt>
                <dd className="mt-1 text-sm font-semibold text-[var(--fg)]">{shortDate(endsOn)}</dd>
                {!sub.isExpired && <dd className={`text-xs ${endingSoon ? "font-medium text-amber-700" : "text-[var(--fg-faint)]"}`}>{timeLeft(sub)}</dd>}
                {sub.inGrace && sub.graceUntil && <dd className="text-xs font-medium text-red-600">Renew by {shortDate(sub.graceUntil)}</dd>}
              </div>
              <div>
                <dt className="text-xs font-medium text-[var(--fg-muted)]">Customer payments</dt>
                <dd className={`mt-1 text-sm font-semibold ${sub.canAcceptCustomerPayments ? "text-emerald-700 dark:text-emerald-400" : "text-red-600"}`}>
                  {sub.canAcceptCustomerPayments ? "On" : "Paused"}
                </dd>
                <dd className="text-xs text-[var(--fg-faint)]">{sub.canAcceptCustomerPayments ? "Customers can buy packages" : "Renew to turn on"}</dd>
              </div>
              <div>
                <dt className="text-xs font-medium text-[var(--fg-muted)]">Member since</dt>
                <dd className="mt-1 text-sm font-semibold text-[var(--fg)]">{shortDate(sub.startDate)}</dd>
              </div>
            </dl>
          </div>
        ) : (
          <div className="py-4 text-center">
            <p className="text-sm font-semibold text-[var(--fg)]">No plan assigned yet</p>
            <p className="mt-1 text-sm text-[var(--fg-muted)]">Contact AirPairy support to get a plan for your workspace.</p>
          </div>
        )}
      </section>

      {/* Payments */}
      <section className={`${panel} overflow-hidden`}>
        <div className="border-b border-[var(--border)] px-5 py-4">
          <h2 className="text-[14.5px] font-semibold text-[var(--fg)]">Payments</h2>
        </div>
        <Table
          bare
          searchable={false}
          rows={sub.paymentHistory || []}
          rowKey={(p: PaymentHistoryItem) => p.id}
          emptyTitle="No payments yet"
          emptyDescription="Your subscription payments will show here."
          columns={[
            { label: "Date", render: (p: PaymentHistoryItem) => (
              <span className="whitespace-nowrap text-[var(--fg)]">
                {date(p.paidAt)}
                {p.status === "SUCCESSFUL" && <button onClick={() => setReceipt(p)} className="mt-0.5 block text-xs font-semibold text-[var(--accent-text)] sm:hidden">View receipt</button>}
              </span>
            )},
            { label: "Plan", hideBelow: "md", render: (p: PaymentHistoryItem) => <span className="text-[var(--fg-muted)]">{p.planName}</span> },
            { label: "Paid with", hideBelow: "lg", render: (p: PaymentHistoryItem) => <span className="text-[var(--fg-muted)]">{paidWith(p)}</span> },
            { label: "Status", hideBelow: "sm", render: (p: PaymentHistoryItem) => {
              const st = PAYMENT_STATUS[p.status] ?? { label: p.status, tone: "slate" };
              return <Badge tone={st.tone} dot>{st.label}</Badge>;
            }},
            { label: "Amount", align: "right", render: (p: PaymentHistoryItem) => <span className="num font-semibold text-[var(--fg)]">{ugx(p.amount)}</span> },
            { label: "", align: "right", hideBelow: "sm", render: (p: PaymentHistoryItem) => p.status === "SUCCESSFUL" && (
              <button onClick={() => setReceipt(p)} className="min-h-9 rounded-lg px-2 text-xs font-semibold text-[var(--accent-text)] hover:underline">Receipt</button>
            )},
          ]}
        />
      </section>

      {checkoutOpen && plan && (
        <Dialog onClose={() => { if (!busy) setCheckoutOpen(false); }} title="Renew subscription">
          <form onSubmit={pay} className="space-y-5">
            <div className="flex items-center justify-between rounded-lg bg-[var(--subtle)] px-4 py-3">
              <div>
                <p className="text-sm font-semibold text-[var(--fg)]">{plan.name}</p>
                <p className="text-xs text-[var(--fg-muted)]">{period(plan)}</p>
              </div>
              <p className="num text-base font-semibold text-[var(--fg)]">{ugx(plan.price)}</p>
            </div>

            {!done && <>
              <fieldset>
                <legend className={label}>Pay with</legend>
                <div className="mt-1 grid grid-cols-2 gap-2">
                  {([
                    { key: "WALLET", title: "Wallet", sub: walletBalance === null ? "Checking…" : walletCovers ? ugx(walletBalance) : "Not enough balance", disabled: !walletCovers },
                    { key: "MOMO", title: "Mobile money", sub: "MTN or Airtel", disabled: false },
                  ] as const).map((o) => (
                    <button key={o.key} type="button" disabled={o.disabled || busy || !!activeReference} aria-pressed={payMethod === o.key}
                      onClick={() => setPayMethod(o.key)}
                      className={`rounded-lg border px-3 py-2.5 text-left transition disabled:cursor-not-allowed disabled:opacity-55 ${payMethod === o.key ? "border-blue-500 bg-blue-50 ring-2 ring-blue-500/25 dark:bg-blue-500/10" : "border-[var(--border)] hover:border-[var(--border-strong)]"}`}>
                      <span className="block text-sm font-semibold text-[var(--fg)]">{o.title}</span>
                      <span className="block text-xs text-[var(--fg-muted)]">{o.sub}</span>
                    </button>
                  ))}
                </div>
              </fieldset>

              {payMethod === "MOMO" && (
                <div>
                  <label className={label} htmlFor="sub-phone">Phone number</label>
                  <input id="sub-phone" type="tel" value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="07XX XXX XXX"
                    className={field} required disabled={busy || !!activeReference} autoComplete="tel" />
                </div>
              )}
            </>}

            {checkoutMsg && (
              <p role={checkoutMsg.tone === "danger" ? "alert" : "status"} className={`rounded-lg border px-3.5 py-3 text-sm ${
                checkoutMsg.tone === "danger" ? "border-red-200 bg-red-50 text-red-800"
                  : checkoutMsg.tone === "success" ? "border-emerald-200 bg-emerald-50 text-emerald-800"
                  : "border-blue-200 bg-blue-50 text-blue-800"}`}>
                {activeReference && <span className="mr-2 inline-block h-3 w-3 animate-spin rounded-full border-2 border-current border-t-transparent align-[-1px] motion-reduce:animate-none" aria-hidden />}
                {checkoutMsg.text}
              </p>
            )}

            {!done && !sub.isExpired && <p className="text-xs text-[var(--fg-faint)]">The new period starts when your current one ends.</p>}

            <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <button type="button" onClick={() => setCheckoutOpen(false)} className={buttonGhost} disabled={busy}>{done ? "Done" : "Cancel"}</button>
              {!done && (
                <button type="submit" className={button} disabled={busy || !!activeReference || (payMethod === "WALLET" && !walletCovers)}>
                  <BusyLabel busy={busy} label={payMethod === "WALLET" ? "Paying…" : "Sending…"}>Pay {ugx(plan.price)}</BusyLabel>
                </button>
              )}
            </div>
          </form>
        </Dialog>
      )}

      {receipt && (
        <Dialog onClose={() => setReceipt(null)} title="Receipt">
          <div className="text-center">
            <CheckCircle size={28} className="mx-auto text-emerald-600" />
            <p className="num mt-2 text-2xl font-semibold text-[var(--fg)]">{ugx(receipt.amount)}</p>
            <p className="text-sm text-[var(--fg-muted)]">Paid {date(receipt.paidAt)}</p>
          </div>
          <dl className="mt-5 divide-y divide-[var(--border-soft)] rounded-lg border border-[var(--border-soft)] px-4 text-sm">
            {[
              ["Plan", receipt.planName],
              ["Paid with", paidWith(receipt)],
              ["Reference", <span key="r" className="font-mono text-xs">{receipt.reference}</span>],
            ].map(([k, v]) => (
              <div key={String(k)} className="flex justify-between gap-4 py-2.5">
                <dt className="text-[var(--fg-muted)]">{k}</dt>
                <dd className="text-right font-medium text-[var(--fg)]">{v}</dd>
              </div>
            ))}
          </dl>
          <div className="mt-5 flex justify-end gap-2">
            <button onClick={() => setReceipt(null)} className={button}>Close</button>
          </div>
        </Dialog>
      )}
    </div>
  );
}
