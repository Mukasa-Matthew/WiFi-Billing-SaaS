"use client";
import React, { useCallback, useEffect, useState } from "react";
import api from "@/lib/api";
import { Table, Badge, ErrorNotice, date, panel, button, buttonGhost, field, label, hint, message, PageHeader } from "@/components/BillingUI";
import { Dialog } from "@/components/ui/Dialog";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";
import { ArrowDown, WarningCircle, ArrowsClockwise } from "@/components/icons";

interface Overview {
  walletCount: number; tenantLiabilities: number; reservedFunds: number;
  totalCollected: number; totalFees: number; totalWithdrawn: number;
  pendingWithdrawals: number; providerBalance: number; providerError: string;
  platformConfigured: boolean;
}
interface WalletRow {
  tenantId: number; businessName: string; available: number; pending: number;
  reserved: number; totalCollected: number; totalWithdrawn: number;
}
interface WithdrawalRow {
  id: number; tenantId: number; businessName: string; amount: number; fee: number;
  totalDeduction: number; phone: string; status: string;
  failureReason: string | null; createdAt: string;
}

interface PlatformView {
  settledEarnings: number; externalEarnings: number; reservedEarnings: number;
  totalEarned: number; totalFees: number; totalWithdrawn: number;
  providerBalance: number; lastSyncedAt: string;
  reconcileStatus: string; reconcileNote: string; balanceStale: boolean;
  tenantAvailable: number; tenantReserved: number;
  safeWithdrawable: number; safetyBuffer: number; payoutsEnabled: boolean;
}
interface Payout {
  id: number; amount: number; fee: number; totalDeduction: number; phone: string;
  status: string; payoutRef: string; providerTransactionId: string | null;
  failureReason: string | null; createdAt: string; completedAt: string | null;
}

const STATUS_TONE: Record<string, string> = {
  COMPLETED: "green", PROCESSING: "blue", PENDING_APPROVAL: "amber",
  REQUESTED: "amber", FAILED: "red", CANCELLED: "slate",
};
const money = (v?: number | null) => v == null || v < 0 ? "—" : `UGX ${v.toLocaleString()}`;

export default function AdminFinancePage() {
  const [overview, setOverview] = useState<Overview | null>(null);
  const [wallets, setWallets] = useState<WalletRow[]>([]);
  const [withdrawals, setWithdrawals] = useState<WithdrawalRow[]>([]);
  const [filter, setFilter] = useState("");
  const [err, setErr] = useState("");
  const [busyId, setBusyId] = useState<number | null>(null);

  const [plat, setPlat] = useState<PlatformView | null>(null);
  const [payouts, setPayouts] = useState<Payout[]>([]);
  const [wdOpen, setWdOpen] = useState(false);
  const [wdAmount, setWdAmount] = useState("");
  const [wdPhone, setWdPhone] = useState("");
  const [wdPassword, setWdPassword] = useState("");
  const [wdBusy, setWdBusy] = useState(false);
  const [wdErr, setWdErr] = useState("");
  const [reconBusy, setReconBusy] = useState(false);
  const toast = useToast();
  const { confirm, dialog: confirmationDialog } = useConfirmDialog();

  const load = useCallback(async () => {
    try {
      const [o, w, wd, pl, po] = await Promise.all([
        api.get<Overview>("/api/admin/finance/overview"),
        api.get<WalletRow[]>("/api/admin/finance/wallets"),
        api.get<WithdrawalRow[]>(`/api/admin/finance/withdrawals${filter ? `?status=${filter}` : ""}`),
        api.get<PlatformView>("/api/admin/finance/platform"),
        api.get<Payout[]>("/api/admin/finance/platform/payouts"),
      ]);
      setOverview(o.data); setWallets(w.data); setWithdrawals(wd.data);
      setPlat(pl.data); setPayouts(po.data);
    } catch (e) { setErr(message(e)); }
  }, [filter]);
  useEffect(() => { const initial = window.setTimeout(load, 0); return () => window.clearTimeout(initial); }, [load]);

  const reconcile = async () => {
    setReconBusy(true); setErr("");
    try { await api.post("/api/admin/finance/platform/reconcile"); await load(); }
    catch (e) { setErr(message(e)); }
    finally { setReconBusy(false); }
  };

  const requestPayout = async (e: React.FormEvent) => {
    e.preventDefault();
    setWdBusy(true); setWdErr("");
    try {
      await api.post("/api/admin/finance/platform/payouts", {
        amount: Number(wdAmount), phone: wdPhone, password: wdPassword,
      });
      setWdOpen(false); setWdAmount(""); setWdPhone(""); setWdPassword("");
      toast("success", "Platform payout request submitted.");
      await load();
    } catch (err) { setWdErr(message(err)); }
    finally { setWdBusy(false); }
  };

  const act = (row: WithdrawalRow, action: "approve" | "fail") => confirm({
    title: action === "approve" ? "Approve withdrawal?" : "Reject withdrawal?",
    body: action === "approve"
      ? `Approve ${money(row.amount)} for ${row.businessName}? The provider payout will only be shown as complete after backend confirmation.`
      : `Reject ${money(row.amount)} for ${row.businessName}? Reserved funds will be released back to the tenant after confirmation.`,
    confirmLabel: action === "approve" ? "Approve withdrawal" : "Reject withdrawal",
    onConfirm: async () => {
      setBusyId(row.id); setErr("");
      try {
        await api.post(`/api/admin/finance/withdrawals/${row.id}/${action}`, action === "fail" ? { reason: "Rejected by administrator" } : {});
        toast("success", action === "approve" ? "Withdrawal approved." : "Withdrawal rejected and funds released.");
        await load();
      } catch (e) { toast("error", message(e)); }
      finally { setBusyId(null); }
    },
  });

  const cards = [
    { label: "Provider balance", value: overview?.providerBalance, note: overview?.platformConfigured ? "Actual platform account" : "Not configured" },
    { label: "Owed to tenants", value: overview?.tenantLiabilities, note: `${overview?.walletCount ?? 0} wallets` },
    { label: "Reserved (in-flight payouts)", value: overview?.reservedFunds, note: `${overview?.pendingWithdrawals ?? 0} open requests` },
    { label: "Total collected (net)", value: overview?.totalCollected, note: `Fees: ${money(overview?.totalFees)}` },
    { label: "Total paid out", value: overview?.totalWithdrawn, note: "Completed withdrawals" },
  ];

  return (
    <div className="space-y-6">
      {confirmationDialog}
      <PageHeader title="Finance" description="Platform-managed collections, tenant wallet liabilities, and withdrawal review. Funds in tenants' own provider accounts are not shown here—only money the platform holds." />

      <ErrorNotice text={err || (overview?.providerError ? `Provider balance unavailable: ${overview.providerError}` : "")} />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        {cards.map((c) => (
          <div key={c.label} className={`${panel} p-5`}>
            <div className="text-xs font-semibold text-[var(--fg-muted)]">{c.label}</div>
            <div className="mt-2 text-xl font-bold text-[var(--fg)]">{money(c.value)}</div>
            <p className="mt-1 text-xs text-[var(--fg-faint)]">{c.note}</p>
          </div>
        ))}
      </div>

      {/* ---- Platform subscription earnings ---- */}
      {plat && (
        <>
          {plat.reconcileStatus === "DISCREPANCY" && (
            <p className="flex items-start gap-2 rounded-lg border border-red-300 bg-red-50 p-4 text-sm font-medium text-red-800">
              <WarningCircle size={18} className="mt-0.5 shrink-0" />
              <span><b>Reconciliation discrepancy.</b> {plat.reconcileNote} Payouts are blocked until this is resolved.</span>
            </p>
          )}
          {plat.balanceStale && (
            <p className="flex items-center gap-2 rounded-lg border border-amber-200 bg-amber-50 p-3 text-xs font-medium text-amber-800">
              <WarningCircle size={15} /> Provider balance is stale—use Reconcile before reviewing payout safety.
            </p>
          )}
          <div className="grid gap-4 lg:grid-cols-3">
            <div className={panel}>
              <div className="border-b border-[var(--border-soft)] px-5 py-4">
                <h2 className="text-sm font-bold text-[var(--fg)]">Platform earnings</h2>
                <p className="mt-0.5 text-xs text-[var(--fg-muted)]">SaaS subscription revenue — owned by the platform.</p>
              </div>
              <dl className="space-y-2.5 px-5 py-4 text-sm">
                <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">Settled earnings (provider-backed)</dt><dd className="font-bold">{money(plat.settledEarnings)}</dd></div>
                <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">External earnings (cash/bank)</dt><dd className="font-semibold">{money(plat.externalEarnings)}</dd></div>
                <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">Reserved (in-flight payouts)</dt><dd>{money(plat.reservedEarnings)}</dd></div>
                <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">Total earned</dt><dd>{money(plat.totalEarned)}</dd></div>
                <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">Provider fees</dt><dd>{money(plat.totalFees)}</dd></div>
                <div className="flex justify-between border-t border-[var(--border-soft)] pt-2.5"><dt className="text-[var(--fg-muted)]">Total withdrawn</dt><dd className="font-bold">{money(plat.totalWithdrawn)}</dd></div>
              </dl>
            </div>

            <div className={panel}>
              <div className="flex items-center justify-between border-b border-[var(--border-soft)] px-5 py-4">
                <div>
                  <h2 className="text-sm font-bold text-[var(--fg)]">Provider account</h2>
                  <p className="mt-0.5 text-xs text-[var(--fg-muted)]">Actual provider balance.</p>
                </div>
                <button className={buttonGhost} onClick={reconcile} disabled={reconBusy}>
                  <ArrowsClockwise size={14} className={reconBusy ? "animate-spin" : ""} /> Reconcile
                </button>
              </div>
              <dl className="space-y-2.5 px-5 py-4 text-sm">
                <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">Available balance</dt><dd className="font-bold">{money(plat.providerBalance)}</dd></div>
                <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">Last synced</dt><dd className="text-xs text-[var(--fg-muted)]">{plat.lastSyncedAt ? date(plat.lastSyncedAt) : "Never"}</dd></div>
                <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">Reconciliation</dt>
                  <dd><Badge tone={plat.reconcileStatus === "CLEAN" ? "green" : plat.reconcileStatus === "DISCREPANCY" ? "red" : "amber"}>{plat.reconcileStatus}</Badge></dd></div>
                <div className="flex justify-between border-t border-[var(--border-soft)] pt-2.5"><dt className="text-[var(--fg-muted)]">Safe withdrawable</dt><dd className="font-bold text-emerald-700">{money(plat.safeWithdrawable)}</dd></div>
                <p className="text-[11px] leading-relaxed text-[var(--fg-faint)]">
                  Min of settled earnings and provider balance minus tenant liabilities
                  ({money(plat.tenantAvailable + plat.tenantReserved)}) and a {money(plat.safetyBuffer)} safety buffer.
                </p>
              </dl>
            </div>

            <div className={panel}>
              <div className="border-b border-[var(--border-soft)] px-5 py-4">
                <h2 className="text-sm font-bold text-[var(--fg)]">Withdraw earnings</h2>
                <p className="mt-0.5 text-xs text-[var(--fg-muted)]">Pay out platform revenue to mobile money.</p>
              </div>
              <div className="px-5 py-4">
                {!plat.payoutsEnabled && (
                  <p className="rounded-lg bg-[var(--subtle)] px-3 py-2 text-xs text-[var(--fg-muted)]">
                    Live payouts are disabled (PLATFORM_PAYOUTS_ENABLED=false). The form below is view-only until enabled.
                  </p>
                )}
                <button className={`${button} mt-3 w-full`} disabled={!plat.payoutsEnabled || plat.safeWithdrawable <= 0 || plat.reconcileStatus === "DISCREPANCY"}
                  onClick={() => setWdOpen(true)}>
                  <ArrowDown size={15} /> Withdraw earnings
                </button>
                <p className="mt-2 text-[11px] text-[var(--fg-faint)]">Requires your admin password. Max {money(plat.safeWithdrawable)} minus payout fee.</p>
              </div>
            </div>
          </div>

          <div className={panel}>
            <div className="border-b border-[var(--border-soft)] px-5 py-4">
              <h2 className="text-sm font-bold text-[var(--fg)]">Platform withdrawals</h2>
            </div>
            <Table
              rows={payouts}
              emptyTitle="No platform withdrawals yet."
              columns={[
                { label: "Date", render: (p: Payout) => <span className="text-[var(--fg-muted)]">{date(p.createdAt)}</span> },
                { label: "Phone", render: (p: Payout) => <span className="font-mono text-xs">{p.phone}</span> },
                { label: "Amount", render: (p: Payout) => <span className="font-semibold">{money(p.amount)}</span> },
                { label: "Fee", render: (p: Payout) => <span className="text-[var(--fg-muted)]">{money(p.fee)}</span> },
                { label: "Status", render: (p: Payout) => (
                  <span>
                    <Badge tone={STATUS_TONE[p.status] || "slate"}>{p.status}</Badge>
                    {p.failureReason && <div className="mt-1 text-[11px] text-red-500">{p.failureReason}</div>}
                  </span>
                )},
                { label: "Provider ref", render: (p: Payout) => <span className="font-mono text-[11px] text-[var(--fg-faint)]">{p.providerTransactionId || p.payoutRef.slice(0, 8)}</span> },
              ]}
            />
          </div>
        </>
      )}

      <div className={panel}>
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-[var(--border-soft)] px-5 py-4">
          <h2 className="text-sm font-bold text-[var(--fg)]">Withdrawal requests</h2>
          <select value={filter} onChange={(e) => setFilter(e.target.value)}
            className="rounded-lg border border-[var(--border-strong)] px-3 py-1.5 text-xs">
            <option value="">All</option>
            <option value="PENDING_APPROVAL,REQUESTED">Awaiting approval</option>
            <option value="PROCESSING">Processing</option>
            <option value="COMPLETED">Completed</option>
            <option value="FAILED,CANCELLED">Failed / cancelled</option>
          </select>
        </div>
        <Table
          rows={withdrawals}
          emptyTitle="No withdrawal requests."
          columns={[
            { label: "Date", render: (w: WithdrawalRow) => <span className="text-[var(--fg-muted)]">{date(w.createdAt)}</span> },
            { label: "Tenant", render: (w: WithdrawalRow) => <span className="font-medium">{w.businessName}</span> },
            { label: "Phone", render: (w: WithdrawalRow) => <span className="font-mono text-xs">{w.phone}</span> },
            { label: "Amount", render: (w: WithdrawalRow) => <span className="font-semibold">{money(w.amount)}</span> },
            { label: "Fee", render: (w: WithdrawalRow) => <span className="text-[var(--fg-muted)]">{money(w.fee)}</span> },
            { label: "Status", render: (w: WithdrawalRow) => (
              <span>
                <Badge tone={STATUS_TONE[w.status] || "slate"}>{w.status.replaceAll("_", " ")}</Badge>
                {w.failureReason && <div className="mt-1 text-[11px] text-red-500">{w.failureReason}</div>}
              </span>
            )},
            { label: "", render: (w: WithdrawalRow) => (
              (w.status === "PENDING_APPROVAL" || w.status === "REQUESTED") && (
                <span className="flex gap-2">
                  <button className={button} disabled={busyId === w.id} onClick={() => act(w, "approve")}>Approve</button>
                  <button className={buttonGhost} disabled={busyId === w.id} onClick={() => act(w, "fail")}>Reject</button>
                </span>
              )
            )},
          ]}
        />
      </div>

      <div className={panel}>
        <div className="border-b border-[var(--border-soft)] px-5 py-4">
          <h2 className="text-sm font-bold text-[var(--fg)]">Tenant wallets</h2>
          <p className="mt-0.5 text-xs text-[var(--fg-muted)]">Internal liabilities — money the platform owes each tenant.</p>
        </div>
        <Table
          rows={wallets}
          emptyTitle="No wallets yet."
          columns={[
            { label: "Tenant", render: (w: WalletRow) => <span className="font-medium">{w.businessName}</span> },
            { label: "Available", render: (w: WalletRow) => <span className="font-semibold">{money(w.available)}</span> },
            { label: "Pending", render: (w: WalletRow) => <span className="text-[var(--fg-muted)]">{money(w.pending)}</span> },
            { label: "Reserved", render: (w: WalletRow) => <span className="text-[var(--fg-muted)]">{money(w.reserved)}</span> },
            { label: "Collected", render: (w: WalletRow) => <span className="text-[var(--fg-muted)]">{money(w.totalCollected)}</span> },
            { label: "Withdrawn", render: (w: WalletRow) => <span className="text-[var(--fg-muted)]">{money(w.totalWithdrawn)}</span> },
          ]}
        />
      </div>

      {wdOpen && plat && (
        <Dialog title="Withdraw platform earnings" description="This payout is submitted to the configured provider after server validation." onClose={() => { if (!wdBusy) setWdOpen(false); }}>
          <form onSubmit={requestPayout}>
            <dl className="space-y-1.5 rounded-lg bg-[var(--subtle)] p-3 text-xs">
              <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">Settled earnings</dt><dd className="font-semibold">{money(plat.settledEarnings)}</dd></div>
              <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">Provider balance</dt><dd className="font-semibold">{money(plat.providerBalance)}</dd></div>
              <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">Max withdrawal</dt><dd className="font-bold text-emerald-700">{money(plat.safeWithdrawable)}</dd></div>
            </dl>
            <div className="mt-4">
              <label className={label}>Amount (UGX)</label>
              <input className={field} type="number" required min="1" max={plat.safeWithdrawable}
                value={wdAmount} onChange={(e) => setWdAmount(e.target.value)} />
              <p className={hint}>A provider payout fee is added on top and deducted from settled earnings.</p>
            </div>
            <div className="mt-4">
              <label className={label}>Recipient mobile money number</label>
              <input className={field} type="tel" required value={wdPhone} onChange={(e) => setWdPhone(e.target.value)}
                placeholder="07XX XXX XXX" />
              <p className={hint}>MTN or Airtel — paid out via the payment provider.</p>
            </div>
            <div className="mt-4">
              <label className={label}>Confirm your admin password</label>
              <input className={field} type="password" required value={wdPassword}
                onChange={(e) => setWdPassword(e.target.value)} autoComplete="current-password" />
            </div>
            {wdErr && <p className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-xs text-red-700">{wdErr}</p>}
            <div className="mt-6 flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
              <button type="button" className={buttonGhost} disabled={wdBusy} onClick={() => setWdOpen(false)}>Cancel</button>
              <button type="submit" className={button} disabled={wdBusy}>
                {wdBusy ? "Processing…" : "Withdraw"}
              </button>
            </div>
          </form>
        </Dialog>
      )}
    </div>
  );
}
