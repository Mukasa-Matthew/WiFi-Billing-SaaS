"use client";
import React, { useEffect, useState } from "react";
import api from "@/lib/api";
import { useResource, Table, Badge, Notice, date, field, button, buttonGhost, panel, label, hint, message } from "@/components/BillingUI";
import { Wallet, Bank, ArrowDown, X, ShieldCheck } from "@phosphor-icons/react";

interface WalletSummary {
  available: number; pending: number; reserved: number;
  totalCollected: number; totalFees: number; totalWithdrawn: number;
  currency: string; openWithdrawals: number;
  limits: { min: number; max: number; approvalRequired: boolean; livePayouts: boolean };
}
interface LedgerEntry {
  id: number; type: string; amount: number;
  availableAfter: number; pendingAfter: number; reservedAfter: number;
  externalRef: string; note: string | null; createdAt: string;
}
interface Withdrawal {
  id: number; amount: number; fee: number; totalDeduction: number;
  phone: string; status: string; failureReason: string | null;
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
};
const STATUS_TONE: Record<string, string> = {
  COMPLETED: "green", PROCESSING: "blue", PENDING_APPROVAL: "amber",
  REQUESTED: "amber", FAILED: "red", CANCELLED: "slate",
};
const money = (v?: number | null) => `UGX ${(v ?? 0).toLocaleString()}`;

export default function WalletPage() {
  const [summary, setSummary] = useState<WalletSummary | null>(null);
  const { data: activity, loading, error: actErr, load: loadActivity } = useResource<LedgerEntry>("/api/wallet/activity", true);
  const { data: withdrawals, load: loadWithdrawals } = useResource<Withdrawal>("/api/wallet/withdrawals", true);
  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState("");
  const [phone, setPhone] = useState("");
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState("");

  const loadSummary = async () => {
    try { setSummary((await api.get<WalletSummary>("/api/wallet")).data); } catch { /* keep stale */ }
  };
  useEffect(() => { void loadSummary(); const t = setInterval(loadSummary, 30000); return () => clearInterval(t); }, []);
  const refreshAll = () => { void loadSummary(); loadActivity(); loadWithdrawals(); };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true); setFormError("");
    try {
      await api.post("/api/wallet/withdrawals", { amount: Number(amount), phone });
      setOpen(false); setAmount(""); setPhone("");
      refreshAll();
    } catch (err) { setFormError(message(err)); }
    finally { setBusy(false); }
  };

  const cancel = async (id: number) => {
    try { await api.post(`/api/wallet/withdrawals/${id}/cancel`); refreshAll(); }
    catch (err) { setFormError(message(err)); }
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-[var(--fg)]">Wallet</h1>
          <p className="mt-1 text-sm text-[var(--fg-muted)]">
            Earnings collected through the platform payment gateway. Payments made through your own MarzPay account settle directly to your wallet there.
          </p>
        </div>
        <button className={button} onClick={() => setOpen(true)}
          disabled={!summary || summary.available <= 0}>
          <ArrowDown size={15} weight="bold" /> Withdraw
        </button>
      </div>

      <Notice text={formError || actErr} />

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
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
          <div className="flex items-center gap-2 text-xs font-semibold text-[var(--fg-muted)]"><Bank size={15} /> Lifetime earnings</div>
          <div className="mt-2 text-2xl font-bold text-[var(--fg)]">{money(summary?.totalCollected)}</div>
          <p className="mt-1 text-xs text-[var(--fg-faint)]">All you&apos;ve ever collected, net of gateway fees</p>
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
            { label: "Date", render: (w: Withdrawal) => <span className="text-[var(--fg-muted)]">{date(w.createdAt)}</span> },
            { label: "Phone", render: (w: Withdrawal) => <span className="font-mono text-xs">{w.phone}</span> },
            { label: "Amount", render: (w: Withdrawal) => <span className="font-semibold">{money(w.amount)}</span> },
            { label: "Fee", render: (w: Withdrawal) => <span className="text-[var(--fg-muted)]">{money(w.fee)}</span> },
            { label: "Status", render: (w: Withdrawal) => (
              <span>
                <Badge tone={STATUS_TONE[w.status] || "slate"}>{w.status.replaceAll("_", " ")}</Badge>
                {w.failureReason && <div className="mt-1 text-[11px] text-red-500">{w.failureReason}</div>}
              </span>
            )},
            { label: "", render: (w: Withdrawal) => (
              (w.status === "PENDING_APPROVAL" || w.status === "REQUESTED") &&
                <button onClick={() => cancel(w.id)} className="text-xs font-semibold text-red-600 hover:underline">Cancel</button>
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
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4" onClick={() => setOpen(false)}>
          <form onSubmit={submit} onClick={(e) => e.stopPropagation()}
            className="w-full max-w-md rounded-2xl bg-[var(--surface)] p-6 shadow-xl">
            <div className="flex items-center justify-between">
              <h3 className="text-base font-bold text-[var(--fg)]">Withdraw earnings</h3>
              <button type="button" onClick={() => setOpen(false)} className="text-[var(--fg-faint)] hover:text-[var(--fg-muted)]"><X size={18} /></button>
            </div>
            <p className="mt-1 text-xs text-[var(--fg-muted)]">Available: <b>{money(summary?.available)}</b></p>

            <div className="mt-5">
              <label className={label}>Amount (UGX)</label>
              <input className={field} type="number" required min={summary?.limits.min} max={Math.min(summary?.limits.max || 0, summary?.available || 0)}
                value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="e.g. 50000" />
              <p className={hint}>Between {money(summary?.limits.min)} and {money(Math.min(summary?.limits.max || 0, summary?.available || 0))}. A payout fee is deducted by the provider.</p>
            </div>
            <div className="mt-4">
              <label className={label}>Mobile money number</label>
              <input className={field} type="tel" required value={phone} onChange={(e) => setPhone(e.target.value)}
                placeholder="07XX XXX XXX" />
              <p className={hint}>MTN or Airtel number that will receive the payout.</p>
            </div>
            {summary?.limits.approvalRequired && (
              <p className="mt-4 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-700">
                Withdrawals are reviewed by the platform before funds are sent.
              </p>
            )}
            <div className="mt-6 flex justify-end gap-3">
              <button type="button" className={buttonGhost} onClick={() => setOpen(false)}>Cancel</button>
              <button type="submit" className={button} disabled={busy}>
                {busy ? "Submitting…" : "Request withdrawal"}
              </button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}
