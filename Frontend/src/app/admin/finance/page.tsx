"use client";
import React, { useEffect, useState } from "react";
import api from "@/lib/api";
import { Table, Badge, Notice, date, panel, button, buttonGhost, message } from "@/components/BillingUI";

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

  const load = async () => {
    try {
      const [o, w, wd] = await Promise.all([
        api.get<Overview>("/api/admin/finance/overview"),
        api.get<WalletRow[]>("/api/admin/finance/wallets"),
        api.get<WithdrawalRow[]>(`/api/admin/finance/withdrawals${filter ? `?status=${filter}` : ""}`),
      ]);
      setOverview(o.data); setWallets(w.data); setWithdrawals(wd.data);
    } catch (e) { setErr(message(e)); }
  };
  useEffect(() => { void load(); }, [filter]);

  const act = async (id: number, action: "approve" | "fail") => {
    if (action === "fail" && !window.confirm("Mark this payout as failed and release the reserved funds back to the tenant?")) return;
    setBusyId(id); setErr("");
    try {
      await api.post(`/api/admin/finance/withdrawals/${id}/${action}`, action === "fail" ? { reason: "Rejected by administrator" } : {});
      await load();
    } catch (e) { setErr(message(e)); }
    finally { setBusyId(null); }
  };

  const cards = [
    { label: "Provider balance (MarzPay)", value: overview?.providerBalance, note: overview?.platformConfigured ? "Actual platform account" : "Not configured" },
    { label: "Owed to tenants", value: overview?.tenantLiabilities, note: `${overview?.walletCount ?? 0} wallets` },
    { label: "Reserved (in-flight payouts)", value: overview?.reservedFunds, note: `${overview?.pendingWithdrawals ?? 0} open requests` },
    { label: "Total collected (net)", value: overview?.totalCollected, note: `Fees: ${money(overview?.totalFees)}` },
    { label: "Total paid out", value: overview?.totalWithdrawn, note: "Completed withdrawals" },
  ];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-bold tracking-tight text-slate-900">Finance</h1>
        <p className="mt-1 text-sm text-slate-500">
          Platform-managed collections, tenant wallet liabilities, and withdrawal review.
          Funds in tenants' own MarzPay accounts are not shown here — only money the platform holds.
        </p>
      </div>

      <Notice text={err || (overview?.providerError ? `Provider balance unavailable: ${overview.providerError}` : "")} />

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        {cards.map((c) => (
          <div key={c.label} className={`${panel} p-5`}>
            <div className="text-xs font-semibold text-slate-500">{c.label}</div>
            <div className="mt-2 text-xl font-bold text-slate-900">{money(c.value)}</div>
            <p className="mt-1 text-xs text-slate-400">{c.note}</p>
          </div>
        ))}
      </div>

      <div className={panel}>
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <h2 className="text-sm font-bold text-slate-900">Withdrawal requests</h2>
          <select value={filter} onChange={(e) => setFilter(e.target.value)}
            className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs">
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
            { label: "Date", render: (w: WithdrawalRow) => <span className="text-slate-600">{date(w.createdAt)}</span> },
            { label: "Tenant", render: (w: WithdrawalRow) => <span className="font-medium">{w.businessName}</span> },
            { label: "Phone", render: (w: WithdrawalRow) => <span className="font-mono text-xs">{w.phone}</span> },
            { label: "Amount", render: (w: WithdrawalRow) => <span className="font-semibold">{money(w.amount)}</span> },
            { label: "Fee", render: (w: WithdrawalRow) => <span className="text-slate-600">{money(w.fee)}</span> },
            { label: "Status", render: (w: WithdrawalRow) => (
              <span>
                <Badge tone={STATUS_TONE[w.status] || "slate"}>{w.status.replaceAll("_", " ")}</Badge>
                {w.failureReason && <div className="mt-1 text-[11px] text-red-500">{w.failureReason}</div>}
              </span>
            )},
            { label: "", render: (w: WithdrawalRow) => (
              (w.status === "PENDING_APPROVAL" || w.status === "REQUESTED") && (
                <span className="flex gap-2">
                  <button className={button} disabled={busyId === w.id} onClick={() => act(w.id, "approve")}>Approve</button>
                  <button className={buttonGhost} disabled={busyId === w.id} onClick={() => act(w.id, "fail")}>Reject</button>
                </span>
              )
            )},
          ]}
        />
      </div>

      <div className={panel}>
        <div className="border-b border-slate-100 px-5 py-4">
          <h2 className="text-sm font-bold text-slate-900">Tenant wallets</h2>
          <p className="mt-0.5 text-xs text-slate-500">Internal liabilities — money the platform owes each tenant.</p>
        </div>
        <Table
          rows={wallets}
          emptyTitle="No wallets yet."
          columns={[
            { label: "Tenant", render: (w: WalletRow) => <span className="font-medium">{w.businessName}</span> },
            { label: "Available", render: (w: WalletRow) => <span className="font-semibold">{money(w.available)}</span> },
            { label: "Pending", render: (w: WalletRow) => <span className="text-slate-600">{money(w.pending)}</span> },
            { label: "Reserved", render: (w: WalletRow) => <span className="text-slate-600">{money(w.reserved)}</span> },
            { label: "Collected", render: (w: WalletRow) => <span className="text-slate-600">{money(w.totalCollected)}</span> },
            { label: "Withdrawn", render: (w: WalletRow) => <span className="text-slate-600">{money(w.totalWithdrawn)}</span> },
          ]}
        />
      </div>
    </div>
  );
}
