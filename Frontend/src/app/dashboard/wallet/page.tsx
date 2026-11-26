"use client";
import { BusyLabel, useSingleFlight } from "@/components/LoadingUI";
import React, { useCallback, useEffect, useState } from "react";
import api from "@/lib/api";
import { useResource, Table, Badge, ErrorNotice, date, field, button, buttonGhost, panel, label, hint, message, PageHeader } from "@/components/BillingUI";
import { Dialog } from "@/components/ui/Dialog";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";
import { Bank, ArrowDown, ShieldCheck } from "@/components/icons";
import "./withdrawal-loader.css";

interface WalletSummary {
  businessName: string;
  available: number; pending: number; reserved: number;
  totalCollected: number; totalFees: number; totalWithdrawn: number;
  currency: string; openWithdrawals: number;
  limits: { min: number; max: number; approvalRequired: boolean; livePayouts: boolean; feeReservePercent: number; feeReserveMin: number };
  maxWithdrawable: number;
  platformFeePercent: number;
}
interface LedgerEntry {
  id: number; type: string; amount: number;
  availableAfter: number; pendingAfter: number; reservedAfter: number;
  externalRef: string; note: string | null; createdAt: string;
}
interface Withdrawal {
  id: number; amount: number; fee: number; totalDeduction: number;
  phone: string; reason: string | null; status: string; failureReason: string | null; payoutRef: string;
  createdAt: string; completedAt: string | null;
}

const LEDGER_LABEL: Record<string, { name: string; tone: string }> = {
  COLLECTION_CREDIT: { name: "Collection", tone: "green" },
  FEE_DEBIT: { name: "Gateway fee", tone: "slate" },
  PLATFORM_FEE: { name: "Gateway fee", tone: "slate" },
  WITHDRAWAL_RESERVE: { name: "Reserved", tone: "amber" },
  WITHDRAWAL_DEBIT: { name: "Paid out", tone: "blue" },
  WITHDRAWAL_RELEASE: { name: "Released", tone: "slate" },
  ADJUSTMENT_CREDIT: { name: "Adjustment +", tone: "green" },
  ADJUSTMENT_DEBIT: { name: "Adjustment −", tone: "red" },
  EXTERNAL_WITHDRAWAL: { name: "Paid out (recorded by admin)", tone: "blue" },
  SUBSCRIPTION_DEBIT: { name: "AirPairy subscription", tone: "blue" },
  RESERVE_CORRECTION: { name: "Reserve correction", tone: "slate" },
};
const STATUS_TONE: Record<string, string> = {
  COMPLETED: "green", PROCESSING: "blue", PENDING_APPROVAL: "amber",
  REQUESTED: "amber", FAILED: "red", CANCELLED: "slate",
};
const money = (v?: number | null) => `UGX ${(v ?? 0).toLocaleString()}`;
const OPEN_STATUSES = ["REQUESTED", "PENDING_APPROVAL", "PROCESSING"];
// Mirrors WithdrawalService.feeReserve: held for the gateway fee, unused part returned.
const feeReserve = (amount: number, percent: number, min: number) => Math.max(min, Math.ceil((amount * percent) / 100));
/** Largest amount whose amount + fee reserve still fits in the available balance. */
const maxWithdrawable = (available: number, limit: number, percent: number, min: number) => {
  let a = Math.min(limit, Math.floor((available * 100) / (100 + percent)));
  while (a > 0 && a + feeReserve(a, percent, min) > available) a--;
  return Math.max(0, a);
};
/** Opaque per-attempt key: the server returns the original withdrawal for a repeat. */
const newIdempotencyKey = () =>
  globalThis.crypto?.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
const shortRef = (ref: string) => `WD-${ref.replaceAll("-", "").slice(0, 8).toUpperCase()}`;

export default function WalletPage() {
  const [summary, setSummary] = useState<WalletSummary | null>(null);
  const { data: activity, loading, error: actErr, load: loadActivity } = useResource<LedgerEntry>("/api/wallet/activity", true);
  const { data: withdrawals, load: loadWithdrawals } = useResource<Withdrawal>("/api/wallet/withdrawals", true);
  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState("");
  const [phone, setPhone] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState("");
  const [otp, setOtp] = useState("");
  const [otpSent, setOtpSent] = useState(false);
  const [otpBusy, setOtpBusy] = useState(false);
  const [step, setStep] = useState<"details" | "verify">("details");
  const toast = useToast();
  const { confirm, dialog } = useConfirmDialog();
  // One key per withdrawal attempt: a retry after a dropped response reuses it, so it
  // can never create a second payout; it is replaced once the request succeeds.
  const [idempotencyKey, setIdempotencyKey] = useState("");
  const feePercent = summary?.limits.feeReservePercent ?? 10;
  const feeMin = summary?.limits.feeReserveMin ?? 1000;
  const available = Math.max(0, summary?.available ?? 0);
  const maxAmount = summary?.maxWithdrawable ?? maxWithdrawable(available, summary?.limits.max ?? 0, feePercent, feeMin);
  const requested = Number(amount) || 0;
  const requestedFee = requested > 0 ? feeReserve(requested, feePercent, feeMin) : 0;
  const overBalance = requested > 0 && requested + requestedFee > available;
  const underMinimum = requested > 0 && requested < (summary?.limits.min ?? 0);
  const canWithdraw = !!summary && maxAmount >= summary.limits.min;
  const openWithdraw = () => { setIdempotencyKey(newIdempotencyKey()); setFormError(""); setOtp(""); setOtpSent(false); setStep("details"); setOpen(true); };

  const loadSummary = useCallback(async () => {
    try { setSummary((await api.get<WalletSummary>("/api/wallet")).data); } catch { /* keep stale */ }
  }, []);
  useEffect(() => { const initial = window.setTimeout(loadSummary, 0); const timer = window.setInterval(loadSummary, 30000); return () => { window.clearTimeout(initial); window.clearInterval(timer); }; }, [loadSummary]);
  const refreshAll = () => { void loadSummary(); loadActivity(); loadWithdrawals(); };

  const singleFlight = useSingleFlight();
  const sendOtp = singleFlight(async () => {
    setOtpBusy(true); setFormError("");
    try {
      await api.post("/api/wallet/withdrawals/otp");
      setOtp(""); setOtpSent(true); setStep("verify");
    } catch (err) { setFormError(message(err)); }
    finally { setOtpBusy(false); }
  });

  const submit = singleFlight(async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true); setFormError("");
    try {
      await api.post("/api/wallet/withdrawals", { amount: requested, phone, reason: reason.trim(), otp },
        { headers: { "Idempotency-Key": idempotencyKey } });
      setIdempotencyKey("");
      setOpen(false); setAmount(""); setPhone(""); setReason(""); setOtp(""); setOtpSent(false);
      toast("success", "Withdrawal request submitted for processing.");
      refreshAll();
    } catch (err) { setFormError(message(err)); }
    finally { setBusy(false); }
  });

  const cancel = (id: number) => confirm({
    title: "Cancel withdrawal request?",
    body: "The reserved funds will be released back to your available wallet balance after the server confirms cancellation.",
    confirmLabel: "Cancel withdrawal",
    onConfirm: async () => {
      try { await api.post(`/api/wallet/withdrawals/${id}/cancel`); toast("success", "Withdrawal request cancelled."); refreshAll(); }
      catch (err) { toast("error", message(err)); }
    },
  });

  return (
    <div className="space-y-6">
      {dialog}
      <PageHeader title="Wallet" description="Earnings collected through the platform payment gateway. Payments made through your own payment gateway account settle directly to your wallet there." action={
        <button className={`${button} w-full sm:w-auto`} onClick={openWithdraw} disabled={!canWithdraw}
          title={summary && !canWithdraw ? `Your balance must cover at least ${money(summary.limits.min)} plus the gateway fee reserve.` : undefined}>
          <ArrowDown size={15} /> Withdraw
        </button>
      } />

      <ErrorNotice text={formError || actErr} />

      {/* Virtual wallet card */}
      <div className="relative overflow-hidden rounded-2xl bg-gradient-to-br from-blue-600 via-blue-700 to-blue-950 p-6 text-white shadow-lg sm:p-7">
        <div className="pointer-events-none absolute -right-14 -top-20 h-64 w-64 rounded-full bg-white/10" />
        <div className="pointer-events-none absolute -bottom-24 -left-12 h-64 w-64 rounded-full bg-white/5" />
        <div className="relative flex items-start justify-between gap-4">
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.22em] text-blue-200">AirPairy Wallet</p>
            <p className="mt-1 text-sm font-bold">{summary?.businessName || "Your business"}</p>
          </div>
          {/* chip */}
          <div className="h-8 w-11 shrink-0 rounded-md bg-gradient-to-br from-amber-200 via-amber-300 to-amber-500 p-1 shadow-inner">
            <div className="h-full w-full rounded-sm border border-amber-600/40 bg-gradient-to-br from-amber-100 to-amber-300" />
          </div>
        </div>
        <p className="relative mt-7 font-mono text-sm tracking-[0.35em] text-blue-100/90">••••&nbsp;&nbsp;••••&nbsp;&nbsp;••••&nbsp;&nbsp;••••</p>
        <div className="relative mt-7 flex flex-wrap items-end justify-between gap-4">
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-wider text-blue-200">Available balance</p>
            <p className="mt-1 text-3xl font-bold tracking-tight">{money(summary?.available)}</p>
          </div>
          <div className="space-y-1 text-right text-xs text-blue-100">
            <p>Reserved: <span className="font-semibold text-white">{money(summary?.reserved)}</span></p>
            <p>Withdrawable now: <span className="font-semibold text-white">{money(maxAmount)}</span></p>
          </div>
        </div>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <div className={`${panel} p-5`}>
          <div className="flex items-center gap-2 text-xs font-semibold text-[var(--fg-muted)]"><ShieldCheck size={15} /> Reserved</div>
          <div className="mt-2 text-2xl font-bold text-[var(--fg)]">{money(summary?.reserved)}</div>
          <p className="mt-1 text-xs text-[var(--fg-faint)]">Held for in-flight withdrawals</p>
        </div>
        <div className={`${panel} p-5`}>
          <div className="flex items-center gap-2 text-xs font-semibold text-[var(--fg-muted)]"><ArrowDown size={15} /> Pending payouts</div>
          <div className="mt-2 text-2xl font-bold text-[var(--fg)]">
            {money((withdrawals ?? []).filter((w) => OPEN_STATUSES.includes(w.status)).reduce((s, w) => s + w.amount, 0))}
          </div>
          <p className="mt-1 text-xs text-[var(--fg-faint)]">{summary?.openWithdrawals ?? 0} in progress</p>
        </div>
        <div className={`${panel} p-5`}>
          <div className="flex items-center gap-2 text-xs font-semibold text-[var(--fg-muted)]"><Bank size={15} /> Lifetime earnings</div>
          <div className="mt-2 text-2xl font-bold text-[var(--fg)]">{money(summary?.totalCollected)}</div>
          <p className="mt-1 text-xs text-[var(--fg-faint)]">All lifetime collections, net of gateway fees</p>
        </div>
        <div className={`${panel} p-5`}>
          <div className="flex items-center gap-2 text-xs font-semibold text-[var(--fg-muted)]"><ArrowDown size={15} /> Total withdrawn</div>
          <div className="mt-2 text-2xl font-bold text-[var(--fg)]">{money(summary?.totalWithdrawn)}</div>
          <p className="mt-1 text-xs text-[var(--fg-faint)]">Fees paid: {money(summary?.totalFees)}</p>
        </div>
      </div>

      <div className={panel}>
        <div className="border-b border-[var(--border-soft)] px-5 py-4">
          <h2 className="text-sm font-bold text-[var(--fg)]">Withdrawals</h2>
          <p className="mt-0.5 text-xs text-[var(--fg-muted)]">
            {summary?.limits.approvalRequired ? "Withdrawals are reviewed before payout. " : ""}
            Min {money(summary?.limits.min)} · Max {money(summary?.limits.max)} per request.
          </p>
        </div>
        <Table
          loading={loading}
          rows={withdrawals}
          emptyTitle="No withdrawals yet."
          columns={[
            { label: "Reference", render: (w: Withdrawal) => <span className="font-mono text-xs font-semibold">{shortRef(w.payoutRef)}</span> },
            { label: "Requested", render: (w: Withdrawal) => <span className="text-[var(--fg-muted)]">{date(w.createdAt)}</span> },
            { label: "Method", render: () => <span className="text-[var(--fg-muted)]">Mobile money</span> },
            { label: "Phone", render: (w: Withdrawal) => <span className="font-mono text-xs">{w.phone}</span> },
            { label: "Reason", render: (w: Withdrawal) => <span className="text-[var(--fg-muted)]">{w.reason || "—"}</span> },
            { label: "Amount", render: (w: Withdrawal) => <span className="font-semibold">{money(w.amount)}</span> },
            { label: "Fee", render: (w: Withdrawal) => <span className="text-[var(--fg-muted)]">{money(w.fee)}</span> },
            { label: "Paid", render: (w: Withdrawal) => <span className="text-[var(--fg-muted)]">{w.completedAt ? date(w.completedAt) : "—"}</span> },
            { label: "Status", render: (w: Withdrawal) => (
              <span>
                <Badge tone={STATUS_TONE[w.status] || "slate"}>{w.status.replaceAll("_", " ")}</Badge>
                {w.failureReason && <div className="mt-1 text-[11px] text-red-500">{w.failureReason}</div>}
              </span>
            )},
            { label: "", render: (w: Withdrawal) => (
              (w.status === "PENDING_APPROVAL" || w.status === "REQUESTED") &&
                <button onClick={() => cancel(w.id)} className="min-h-10 rounded-lg px-2 text-xs font-semibold text-red-600 transition hover:bg-red-50">Cancel</button>
            )},
          ]}
        />
      </div>

      <div className={panel}>
        <div className="border-b border-[var(--border-soft)] px-5 py-4">
          <h2 className="text-sm font-bold text-[var(--fg)]">Wallet activity</h2>
          <p className="mt-0.5 text-xs text-[var(--fg-muted)]">Every balance change is recorded here and traceable to a payment or payout.</p>
        </div>
        <Table
          loading={loading}
          rows={activity}
          emptyTitle="No wallet activity yet."
          columns={[
            { label: "Date", render: (e: LedgerEntry) => <span className="text-[var(--fg-muted)]">{date(e.createdAt)}</span> },
            { label: "Event", render: (e: LedgerEntry) => (
              <span>
                <Badge tone={LEDGER_LABEL[e.type]?.tone || "slate"}>{LEDGER_LABEL[e.type]?.name || e.type}</Badge>
                {e.note && <div className="mt-1 text-[11px] text-[var(--fg-faint)]">{e.note}</div>}
              </span>
            )},
            { label: "Amount", render: (e: LedgerEntry) => <span className="font-semibold">{money(e.amount)}</span> },
            { label: "Balance after", render: (e: LedgerEntry) => <span className="text-[var(--fg-muted)]">{money(e.availableAfter)}</span> },
            { label: "Reference", render: (e: LedgerEntry) => <span className="font-mono text-[11px] text-[var(--fg-faint)]">{e.externalRef}</span> },
          ]}
        />
      </div>

      {open && (
        <Dialog title={step === "details" ? "Withdraw earnings" : "Confirm withdrawal"} description={step === "details" ? "Enter your withdrawal details." : "Enter the code sent to your account email."} onClose={() => { if (!busy && !otpBusy) setOpen(false); }}>
          <form onSubmit={step === "details" ? (e) => { e.preventDefault(); void sendOtp(); } : submit}>
            <div className="withdrawal-steps" aria-label={`Step ${step === "details" ? 1 : 2} of 2`}>
              <span className="withdrawal-step withdrawal-step-active"><span>1</span> Details</span>
              <span className="withdrawal-step-line" />
              <span className={`withdrawal-step ${step === "verify" ? "withdrawal-step-active" : ""}`}><span>2</span> Verify</span>
            </div>
            {(otpBusy || busy) && <div className="withdrawal-loading" role="status"><div className="withdrawal-spinner" aria-hidden="true">{Array.from({ length: 8 }, (_, i) => <span key={i} />)}</div><span>{otpBusy ? "Sending your code…" : "Submitting your request…"}</span></div>}
            {formError && <div className="mt-4" role="alert"><ErrorNotice text={formError} /></div>}
            {step === "details" && <div className="withdrawal-step-content">
            <div className="mt-4 grid grid-cols-2 gap-3">
              <div className="rounded-xl bg-[var(--subtle)] px-4 py-3">
                <p className="text-[11px] font-semibold uppercase tracking-wider text-[var(--fg-faint)]">Available balance</p>
                <p className="mt-0.5 text-lg font-bold text-[var(--fg)]">{money(summary?.available)}</p>
              </div>
              <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3">
                <p className="text-[11px] font-semibold uppercase tracking-wider text-emerald-800">Available to withdraw</p>
                <p className="mt-0.5 text-lg font-bold text-emerald-800">{money(maxAmount)}</p>
                {canWithdraw && <button type="button" className="mt-0.5 text-xs font-semibold text-emerald-800 underline" onClick={() => setAmount(String(maxAmount))}>Use maximum</button>}
              </div>
            </div>
            <p className="mt-2 text-xs text-[var(--fg-muted)]">
              Fee reserve: up to {feePercent}% (minimum {money(feeMin)}). Unused funds return to your wallet.
            </p>

            <div className="mt-5">
              <label className={label} htmlFor="withdraw-amount">Amount (UGX)</label>
              <input id="withdraw-amount" className={field} type="number" required min={summary?.limits.min} max={maxAmount}
                value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="e.g. 50000"
                aria-invalid={overBalance || underMinimum} aria-describedby="withdraw-amount-help" />
              {overBalance ? (
                <p id="withdraw-amount-help" role="alert" className="mt-1.5 text-xs text-red-700">
                  Amount and fee reserve exceed your balance. Maximum: {money(maxAmount)}.
                </p>
              ) : underMinimum ? (
                <p id="withdraw-amount-help" role="alert" className="mt-1.5 text-xs text-red-700">The minimum withdrawal is {money(summary?.limits.min)}.</p>
              ) : (
                <p id="withdraw-amount-help" className={hint}>Between {money(summary?.limits.min)} and {money(maxAmount)}.</p>
              )}
            </div>
            <div className="mt-4">
              <label className={label} htmlFor="withdraw-phone">Mobile money number</label>
              <input id="withdraw-phone" className={field} type="tel" required value={phone} onChange={(e) => setPhone(e.target.value)}
                placeholder="07XX XXX XXX" />
            </div>
            <div className="mt-4">
              <label className={label} htmlFor="withdraw-reason">Reason</label>
              <input id="withdraw-reason" className={field} type="text" required minLength={3} maxLength={255} value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="e.g. Business expenses" />
            </div>
            {requested >= (summary?.limits.min ?? Infinity) && !overBalance && (
              <dl className="mt-4 space-y-1.5 rounded-xl border border-[var(--border-soft)] bg-[var(--subtle)] px-4 py-3 text-xs">
                <div className="flex justify-between"><dt className="font-semibold text-[var(--fg)]">Sent to your mobile money</dt><dd className="font-bold text-emerald-700">{money(requested)}</dd></div>
                <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">Held for the gateway fee</dt><dd className="text-[var(--fg-muted)]">up to {money(requestedFee)}</dd></div>
                <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">Taken from your wallet now</dt><dd className="text-[var(--fg-muted)]">{money(requested + requestedFee)}</dd></div>
                <div className="flex justify-between border-t border-[var(--border-soft)] pt-1.5"><dt className="text-[var(--fg-muted)]">Left in your wallet</dt><dd className="font-semibold text-[var(--fg)]">{money(available - requested - requestedFee)}</dd></div>
              </dl>
            )}
            </div>}
            {step === "verify" && <div className="withdrawal-step-content">
            <div className="mt-5 rounded-xl bg-[var(--subtle)] p-4 text-sm">
              <div className="flex justify-between"><span>Amount</span><strong>{money(requested)}</strong></div>
              <div className="mt-2 flex justify-between"><span>Mobile money</span><strong>{phone}</strong></div>
              <div className="mt-2 flex justify-between"><span>Fee reserve</span><strong>Up to {money(requestedFee)}</strong></div>
            </div>
            <div className="mt-4">
              <label className={label} htmlFor="withdraw-otp">Verification code</label>
              <div className="flex flex-col gap-2 sm:flex-row">
                <input id="withdraw-otp" className={`${field} withdrawal-code`} inputMode="numeric" autoComplete="one-time-code" autoFocus maxLength={6} value={otp}
                  onChange={(e) => setOtp(e.target.value.replace(/\D/g, ""))}
                  placeholder={otpSent ? "6-digit code" : "Request a code first"} required />
                <button type="button" className={`${buttonGhost} shrink-0`} disabled={otpBusy || busy}
                  onClick={() => void sendOtp()}>
                  <BusyLabel busy={otpBusy} label="Sending…">{otpSent ? "Resend" : "Send code"}</BusyLabel>
                </button>
              </div>
              <p className={hint}>
                {otpSent
                  ? "Valid for 10 minutes. Check spam if it doesn’t arrive."
                  : "For your security, we email a verification code to your account email before any withdrawal."}
              </p>
            </div>
            </div>}
            {summary?.limits.approvalRequired && (
              <p className="mt-4 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-700">
                Withdrawals are reviewed by the platform before funds are sent.
              </p>
            )}
            <div className="mt-6 flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
              <button type="button" className={buttonGhost} disabled={busy || otpBusy} onClick={() => step === "verify" ? setStep("details") : setOpen(false)}>{step === "verify" ? "Back" : "Cancel"}</button>
              <button type="submit" className={button} disabled={busy || otpBusy || (step === "verify" && (!otpSent || otp.length !== 6)) || overBalance || underMinimum || requested <= 0 || reason.trim().length < 3}>
                <BusyLabel busy={busy || otpBusy} label={otpBusy ? "Sending code…" : "Submitting…"}>{step === "details" ? "Send verification code" : "Confirm withdrawal"}</BusyLabel>
              </button>
            </div>
          </form>
        </Dialog>
      )}
    </div>
  );
}
