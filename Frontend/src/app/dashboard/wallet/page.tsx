"use client";
import { BusyLabel, useSingleFlight } from "@/components/LoadingUI";
import React, { useCallback, useEffect, useState } from "react";
import api from "@/lib/api";
import { useResource, Table, Badge, ErrorNotice, date, field, button, buttonGhost, panel, label, hint, message, PageHeader } from "@/components/BillingUI";
import { Dialog } from "@/components/ui/Dialog";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";
import { Wallet, Bank, ArrowDown, ShieldCheck } from "@/components/icons";

interface WalletSummary {
  available: number; pending: number; reserved: number;
  totalCollected: number; totalFees: number; totalWithdrawn: number;
  currency: string; openWithdrawals: number;
  limits: { min: number; max: number; approvalRequired: boolean; livePayouts: boolean; feePercent: number };
  maxWithdrawable: number;
}
interface LedgerEntry {
  id: number; type: string; amount: number;
  availableAfter: number; pendingAfter: number; reservedAfter: number;
  externalRef: string; note: string | null; createdAt: string;
}
interface Withdrawal {
  id: number; amount: number; fee: number; totalDeduction: number;
  phone: string; status: string; failureReason: string | null; payoutRef: string;
  createdAt: string; completedAt: string | null;
}

const LEDGER_LABEL: Record<string, { name: string; tone: string }> = {
  COLLECTION_CREDIT: { name: "Collection", tone: "green" },
  FEE_DEBIT: { name: "Gateway fee", tone: "slate" },
  WITHDRAWAL_RESERVE: { name: "Reserved", tone: "amber" },
  WITHDRAWAL_DEBIT: { name: "Paid out", tone: "blue" },
  WITHDRAWAL_RELEASE: { name: "Released", tone: "slate" },
  ADJUSTMENT_CREDIT: { name: "Adjustment +", tone: "green" },
  ADJUSTMENT_DEBIT: { name: "Adjustment −", tone: "red" },
  EXTERNAL_WITHDRAWAL: { name: "Paid out (recorded by admin)", tone: "blue" },
  RESERVE_CORRECTION: { name: "Reserve correction", tone: "slate" },
};
const STATUS_TONE: Record<string, string> = {
  COMPLETED: "green", PROCESSING: "blue", PENDING_APPROVAL: "amber",
  REQUESTED: "amber", FAILED: "red", CANCELLED: "slate",
};
const money = (v?: number | null) => `UGX ${(v ?? 0).toLocaleString()}`;
const OPEN_STATUSES = ["REQUESTED", "PENDING_APPROVAL", "PROCESSING"];
// Mirrors WithdrawalService.estimateFee — actual charge is set by the provider and reconciled.
const estimateFee = (amount: number, percent: number) => Math.max(500, Math.ceil((amount * percent) / 100));
/** Largest amount whose amount + fee still fits in the available balance. */
const maxWithdrawable = (available: number, limit: number, percent: number) => {
  let a = Math.min(limit, Math.floor((available * 100) / (100 + percent)));
  while (a > 0 && a + estimateFee(a, percent) > available) a--;
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
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState("");
  const [otp, setOtp] = useState("");
  const [otpSent, setOtpSent] = useState(false);
  const [otpBusy, setOtpBusy] = useState(false);
  const toast = useToast();
  const { confirm, dialog } = useConfirmDialog();
  // One key per withdrawal attempt: a retry after a dropped response reuses it, so it
  // can never create a second payout; it is replaced once the request succeeds.
  const [idempotencyKey, setIdempotencyKey] = useState("");
  const feePercent = summary?.limits.feePercent ?? 3;
  const available = Math.max(0, summary?.available ?? 0);
  const maxAmount = summary?.maxWithdrawable ?? maxWithdrawable(available, summary?.limits.max ?? 0, feePercent);
  const requested = Number(amount) || 0;
  const requestedFee = requested > 0 ? estimateFee(requested, feePercent) : 0;
  const overBalance = requested > 0 && requested + requestedFee > available;
  const underMinimum = requested > 0 && requested < (summary?.limits.min ?? 0);
  const canWithdraw = !!summary && maxAmount >= summary.limits.min;
  const openWithdraw = () => { setIdempotencyKey(newIdempotencyKey()); setFormError(""); setOpen(true); };

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
      setOtpSent(true);
    } catch (err) { setFormError(message(err)); }
    finally { setOtpBusy(false); }
  });

  const submit = singleFlight(async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true); setFormError("");
    try {
      await api.post("/api/wallet/withdrawals", { amount: requested, phone, otp },
        { headers: { "Idempotency-Key": idempotencyKey } });
      setIdempotencyKey("");
      setOpen(false); setAmount(""); setPhone(""); setOtp(""); setOtpSent(false);
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
          title={summary && !canWithdraw ? `Your balance must cover at least ${money(summary.limits.min)} plus the ${feePercent}% gateway fee.` : undefined}>
          <ArrowDown size={15} /> Withdraw
        </button>
      } />

      <ErrorNotice text={formError || actErr} />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        <div className={`${panel} p-5`}>
          <div className="flex items-center gap-2 text-xs font-semibold text-[var(--fg-muted)]"><Wallet size={15} /> Available balance</div>
          <div className="mt-2 text-2xl font-bold text-[var(--fg)]">{money(summary?.available)}</div>
          <p className="mt-1 text-xs text-[var(--fg-faint)]">Ready to withdraw</p>
        </div>
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
            { label: "Amount", render: (w: Withdrawal) => <span className="font-semibold">{money(w.amount)}</span> },
            { label: "Fee", render: (w: Withdrawal) => <span className="text-[var(--fg-muted)]">{money(w.fee)}</span> },
            { label: "Paid", render: (w: Withdrawal) => <span className="text-[var(--fg-muted)]">{w.completedAt ? date(w.completedAt) : "—"}</span> },
            { label: "Status", render: (w: Withdrawal) => (
              <span>
                <Badge tone={STATUS_TONE[w.status] || "slate"}>{w.status.replaceAll("_", " ")}</Badge>
                {w.failureReason && <div className="mt-1 text-[11px] text-red-700">{w.failureReason}</div>}
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
        <Dialog title="Withdraw earnings" description="Verify the amount and recipient before submitting this request." onClose={() => { if (!busy) setOpen(false); }}>
          <form onSubmit={submit}>
            <div className="mt-4 rounded-xl bg-[var(--subtle)] px-4 py-3 text-center">
              <p className="text-[11px] font-semibold uppercase tracking-wider text-[var(--fg-faint)]">Available balance</p>
              <p className="mt-0.5 text-xl font-bold text-[var(--fg)]">{money(summary?.available)}</p>
            </div>

            <div className="mt-5">
              <label className={label}>Withdrawal amount (UGX)</label>
              <input className={field} type="number" required min={summary?.limits.min}
                value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="e.g. 50000"
                aria-invalid={overBalance || underMinimum} aria-describedby="withdraw-amount-help" />
              {overBalance ? (
                <p id="withdraw-amount-help" role="alert" className="mt-1.5 text-xs text-red-700">
                  {money(requested)} plus the {money(requestedFee)} gateway fee comes to {money(requested + requestedFee)}, which is more than your available {money(available)}.
                  {maxAmount >= (summary?.limits.min ?? 0) && <> You can withdraw up to <button type="button" className="font-semibold underline" onClick={() => setAmount(String(maxAmount))}>{money(maxAmount)}</button>.</>}
                </p>
              ) : underMinimum ? (
                <p id="withdraw-amount-help" role="alert" className="mt-1.5 text-xs text-red-700">The minimum withdrawal is {money(summary?.limits.min)}.</p>
              ) : (
                <p id="withdraw-amount-help" className={hint}>Between {money(summary?.limits.min)} and {money(maxAmount)}. A {feePercent}% gateway fee is taken from your balance on top of the amount.</p>
              )}
            </div>
            <div className="mt-4">
              <label className={label}>Mobile money number</label>
              <input className={field} type="tel" required value={phone} onChange={(e) => setPhone(e.target.value)}
                placeholder="07XX XXX XXX" />
              <p className={hint}>MTN or Airtel number that will receive the payout.</p>
            </div>
            {Number(amount) >= (summary?.limits.min ?? Infinity) && (
              <dl className="mt-4 space-y-1.5 rounded-xl border border-[var(--border-soft)] bg-[var(--subtle)] px-4 py-3 text-xs">
                <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">Withdrawal amount</dt><dd className="font-semibold text-[var(--fg)]">{money(Number(amount))}</dd></div>
                <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">Estimated gateway fee</dt><dd className="text-[var(--fg-muted)]">-{money(estimateFee(Number(amount), feePercent))}</dd></div>
                <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">Deducted from wallet</dt><dd className="text-[var(--fg-muted)]">{money(Number(amount) + estimateFee(Number(amount), feePercent))}</dd></div>
                <div className="flex justify-between border-t border-[var(--border-soft)] pt-1.5"><dt className="font-semibold text-[var(--fg)]">You will receive</dt><dd className="font-bold text-emerald-700">{money(Number(amount))}</dd></div>
              </dl>
            )}
            {/* Email verification — a code must be issued before the request can be submitted. */}
            <div className="mt-4">
              <label className={label}>Verification code</label>
              <div className="flex flex-col gap-2 sm:flex-row">
                <input className={field} inputMode="numeric" maxLength={6} value={otp}
                  onChange={(e) => setOtp(e.target.value.replace(/\D/g, ""))}
                  placeholder={otpSent ? "6-digit code" : "Request a code first"} required />
                <button type="button" className={`${buttonGhost} shrink-0`} disabled={otpBusy || busy}
                  onClick={() => void sendOtp()}>
                  <BusyLabel busy={otpBusy} label="Sending…">{otpSent ? "Resend" : "Send code"}</BusyLabel>
                </button>
              </div>
              <p className={hint}>
                {otpSent
                  ? "Code sent to your account email — valid for 10 minutes. Check spam if it doesn't arrive."
                  : "For your security, we email a verification code to your account email before any withdrawal."}
              </p>
            </div>
            {summary?.limits.approvalRequired && (
              <p className="mt-4 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-700">
                Withdrawals are reviewed by the platform before funds are sent.
              </p>
            )}
            <div className="mt-6 flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
              <button type="button" className={buttonGhost} disabled={busy} onClick={() => setOpen(false)}>Cancel</button>
              <button type="submit" className={button} disabled={busy || otpBusy || otp.length !== 6 || overBalance || underMinimum || requested <= 0}>
                <BusyLabel busy={busy} label="Submitting…">Request withdrawal</BusyLabel>
              </button>
            </div>
          </form>
        </Dialog>
      )}
    </div>
  );
}
