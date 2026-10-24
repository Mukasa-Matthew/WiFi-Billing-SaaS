"use client";
import React, { useState } from "react";
import { useResource, Table, Badge, Notice, FilterTabs, date } from "@/components/BillingUI";
import { Wallet, Money, CalendarBlank, Eye, EyeSlash } from "@phosphor-icons/react";

interface Payment {
  reference: string; method: string; amount: number; status: string;
  phone: string; voucher: string | null; packageName: string; router: string;
  createdAt: string; notes: string | null;
}

const ONLINE = ["MTN", "AIRTEL"];
const isToday = (d: string) => new Date(d).toDateString() === new Date().toDateString();
const isThisWeek = (d: string) => { const t = Date.now() - new Date(d).getTime(); return t >= 0 && t < 7 * 86400000; };
const isThisMonth = (d: string) => { const x = new Date(d), n = new Date(); return x.getMonth() === n.getMonth() && x.getFullYear() === n.getFullYear(); };

export default function Page() {
  const { data, loading, error } = useResource<Payment>("/api/finance/payments", true);
  const [tab, setTab] = useState("COMPLETED");
  const [hidden, setHidden] = useState(false);

  const ok = (p: Payment) => p.status === "SUCCESSFUL";
  const sum = (rows: Payment[]) => rows.filter(ok).reduce((s, p) => s + Number(p.amount), 0);
  const money = (v: number) => hidden ? "••••••" : `UGX ${v.toLocaleString()}`;

  const todayOnline = sum(data.filter(p => isToday(p.createdAt) && ONLINE.includes(p.method)));
  const todayWalkin = sum(data.filter(p => isToday(p.createdAt) && !ONLINE.includes(p.method)));
  const weekTotal = sum(data.filter(p => isThisWeek(p.createdAt)));
  const monthTotal = sum(data.filter(p => isThisMonth(p.createdAt)));

  const completed = data.filter(ok);
  const pending = data.filter(p => p.status !== "SUCCESSFUL");

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-[var(--fg)]">Payments</h1>
          <p className="mt-1 text-sm text-[var(--fg-muted)]">Online payments and manually recorded walk-in or binding sales.</p>
        </div>
        <button onClick={() => setHidden(!hidden)} aria-label={hidden ? "Show revenue" : "Hide revenue"}
          className="rounded-lg border border-[var(--border-strong)] p-2.5 text-[var(--fg-muted)] hover:text-[var(--fg)]">
          {hidden ? <EyeSlash size={16} /> : <Eye size={16} />}
        </button>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <div className="rounded-xl border border-[var(--border)] bg-[var(--surface)] p-5 shadow-sm">
          <p className="text-xs font-semibold uppercase tracking-wide text-[var(--fg-muted)]">Today's revenue (online)</p>
          <p className="mt-2 text-2xl font-bold tabular-nums text-[var(--fg)]">{money(todayOnline)}</p>
          <p className="mt-1 flex items-center gap-1.5 text-xs text-emerald-700"><Money size={12} />Paid via mobile money</p>
        </div>
        <div className="rounded-xl border border-[var(--border)] bg-[var(--surface)] p-5 shadow-sm">
          <p className="text-xs font-semibold uppercase tracking-wide text-[var(--fg-muted)]">Today's revenue (walk-in)</p>
          <p className="mt-2 text-2xl font-bold tabular-nums text-[var(--fg)]">{money(todayWalkin)}</p>
          <p className="mt-1 flex items-center gap-1.5 text-xs text-amber-700"><Ticket2 />Cash / manual sales</p>
        </div>
        <div className="rounded-xl border border-[var(--border)] bg-[var(--surface)] p-5 shadow-sm">
          <p className="text-xs font-semibold uppercase tracking-wide text-[var(--fg-muted)]">Weekly revenue</p>
          <p className="mt-2 text-2xl font-bold tabular-nums text-[var(--fg)]">{money(weekTotal)}</p>
          <p className="mt-1 flex items-center gap-1.5 text-xs text-[var(--accent-text)]"><CalendarBlank size={12} />Last 7 days</p>
        </div>
        <div className="rounded-xl border border-[var(--border)] bg-[var(--surface)] p-5 shadow-sm">
          <p className="text-xs font-semibold uppercase tracking-wide text-[var(--fg-muted)]">Monthly revenue</p>
          <p className="mt-2 text-2xl font-bold tabular-nums text-[var(--fg)]">{money(monthTotal)}</p>
          <p className="mt-1 flex items-center gap-1.5 text-xs text-[var(--accent-text)]"><CalendarBlank size={12} />This month</p>
        </div>
      </div>

      <Notice text={error} />

      <div className="flex items-center justify-between gap-3">
        <FilterTabs
          options={[
            { key: "COMPLETED", label: "Completed", count: completed.length },
            { key: "PENDING", label: "Pending / failed", count: pending.length },
          ]}
          value={tab}
          onChange={setTab}
        />
      </div>

      <Table
        emptyTitle="No payments" emptyDescription="Transactions will appear here once payments are recorded."
        rows={tab === "COMPLETED" ? completed : pending}
        loading={loading}
        columns={[
          { label: "Reference", render: (p: Payment) => <span className="font-mono text-xs">{p.reference}</span> },
          { label: "Phone", render: p => <span className="font-medium">{p.phone}</span> },
          { label: "Voucher", render: p => p.voucher ? <Badge tone="green">{p.voucher}</Badge> : "—" },
          { label: "Package", render: p => p.packageName },
          { label: "Method", render: p => <Badge tone="slate">{p.method}</Badge> },
          { label: "Amount", render: p => <span className="font-semibold">UGX {Number(p.amount).toLocaleString()}</span> },
          { label: "Status", render: p => <Badge tone={p.status === "SUCCESSFUL" ? "green" : p.status === "FAILED" ? "red" : "amber"}>{p.status}</Badge> },
          { label: "Router", render: p => p.router },
          { label: "Recorded", render: p => <span className="text-[var(--fg-muted)]">{date(p.createdAt)}</span> },
        ]}
      />
    </div>
  );
}

function Ticket2() {
  return <Wallet size={12} />;
}
