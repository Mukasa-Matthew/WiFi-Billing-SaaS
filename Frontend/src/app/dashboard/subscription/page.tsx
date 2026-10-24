"use client";
import React, { useEffect, useState } from "react";
import api from "@/lib/api";
import { Table, Badge, Notice, panel, message } from "@/components/BillingUI";
import { CalendarCheck, CreditCard, WarningCircle } from "@phosphor-icons/react";

interface SubView {
  subscribed: boolean;
  status?: string;
  monthlyFee?: number;
  currency?: string;
  startDate?: string;
  nextRenewalDate?: string;
  daysToRenewal?: number;
  outstanding?: number;
  reminder?: string;
  invoices?: { id: number; periodStart: string; periodEnd: string; amountDue: number; status: string }[];
  payments?: { id: number; amount: number; method: string; paidAt: string }[];
}

const TONE: Record<string, string> = {
  ACTIVE: "green", PAYMENT_DUE: "amber", PAST_DUE: "red",
  SUSPENDED: "slate", CANCELLED: "slate", PAID: "green", UNPAID: "amber",
};
const STATUS_NAME: Record<string, string> = {
  ACTIVE: "Active", PAYMENT_DUE: "Payment due", PAST_DUE: "Past due",
  SUSPENDED: "Suspended", CANCELLED: "Cancelled",
};
const money = (v?: number | null) => `UGX ${(v ?? 0).toLocaleString()}`;
const d = (s?: string | null) => s ? new Date(s).toLocaleDateString() : "—";

export default function SubscriptionPage() {
  const [sub, setSub] = useState<SubView | null>(null);
  const [err, setErr] = useState("");

  useEffect(() => {
    api.get<SubView>("/api/subscription")
      .then((r) => setSub(r.data))
      .catch((e) => setErr(message(e)));
  }, []);

  if (err) return <Notice text={err} />;
  if (!sub) return null;

  if (!sub.subscribed) {
    return (
      <div className={panel + " p-8 text-center"}>
        <CreditCard size={32} className="mx-auto text-slate-300" />
        <h1 className="mt-3 text-lg font-bold text-[var(--fg)]">No subscription yet</h1>
        <p className="mt-1 text-sm text-[var(--fg-muted)]">Your subscription has not been activated. Please contact support.</p>
      </div>
    );
  }

  const overdue = sub.status === "PAST_DUE";

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-bold tracking-tight text-[var(--fg)]">Subscription &amp; Billing</h1>
        <p className="mt-1 text-sm text-[var(--fg-muted)]">Your monthly SaaS subscription to this platform.</p>
      </div>

      {sub.reminder && (
        <p className={`flex items-center gap-2 rounded-lg border p-3 text-xs font-medium ${
          overdue ? "border-red-200 bg-red-50 text-red-700" : "border-amber-200 bg-amber-50 text-amber-700"}`}>
          <WarningCircle size={15} /> {sub.reminder}
          {sub.outstanding ? ` Outstanding: ${money(sub.outstanding)}.` : ""} Contact your provider to settle.
        </p>
      )}

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <div className={`${panel} p-5`}>
          <div className="text-xs font-semibold text-[var(--fg-muted)]">Monthly subscription</div>
          <div className="mt-2 text-2xl font-bold text-[var(--fg)]">{money(sub.monthlyFee)}</div>
          <p className="mt-1 text-xs text-[var(--fg-faint)]">per month</p>
        </div>
        <div className={`${panel} p-5`}>
          <div className="text-xs font-semibold text-[var(--fg-muted)]">Status</div>
          <div className="mt-2"><Badge tone={TONE[sub.status!] || "slate"}>{STATUS_NAME[sub.status!] || sub.status}</Badge></div>
          <p className="mt-2 text-xs text-[var(--fg-faint)]">Managed by your provider</p>
        </div>
        <div className={`${panel} p-5`}>
          <div className="flex items-center gap-1.5 text-xs font-semibold text-[var(--fg-muted)]"><CalendarCheck size={14} /> Next renewal</div>
          <div className="mt-2 text-2xl font-bold text-[var(--fg)]">{d(sub.nextRenewalDate)}</div>
          <p className="mt-1 text-xs text-[var(--fg-faint)]">
            {sub.daysToRenewal !== undefined && sub.daysToRenewal >= 0 ? `in ${sub.daysToRenewal} day${sub.daysToRenewal === 1 ? "" : "s"}` : "period ended"}
          </p>
        </div>
        <div className={`${panel} p-5`}>
          <div className="text-xs font-semibold text-[var(--fg-muted)]">Amount due</div>
          <div className={`mt-2 text-2xl font-bold ${sub.outstanding! > 0 ? "text-red-600" : "text-[var(--fg)]"}`}>
            {money(sub.outstanding)}
          </div>
          <p className="mt-1 text-xs text-[var(--fg-faint)]">{sub.outstanding! > 0 ? "Payment required" : "Nothing owed"}</p>
        </div>
      </div>

      <div className={panel}>
        <div className="border-b border-[var(--border-soft)] px-5 py-4">
          <h2 className="text-sm font-bold text-[var(--fg)]">Billing periods</h2>
        </div>
        <Table
          rows={sub.invoices || []}
          emptyTitle="No billing periods yet."
          columns={[
            { label: "Period", render: (i) => <span className="text-[var(--fg-body)]">{d(i.periodStart)} → {d(i.periodEnd)}</span> },
            { label: "Amount due", render: (i) => <span className="font-semibold">{money(i.amountDue)}</span> },
            { label: "Status", render: (i) => <Badge tone={TONE[i.status] || "slate"}>{i.status === "PAID" ? "Paid" : "Unpaid"}</Badge> },
          ]}
        />
      </div>

      <div className={panel}>
        <div className="border-b border-[var(--border-soft)] px-5 py-4">
          <h2 className="text-sm font-bold text-[var(--fg)]">Payment history</h2>
        </div>
        <Table
          rows={sub.payments || []}
          emptyTitle="No payments recorded yet."
          columns={[
            { label: "Date", render: (p) => <span className="text-[var(--fg-muted)]">{d(p.paidAt)}</span> },
            { label: "Amount", render: (p) => <span className="font-semibold">{money(p.amount)}</span> },
            { label: "Method", render: (p) => <span className="text-[var(--fg-muted)]">{p.method}</span> },
          ]}
        />
      </div>
    </div>
  );
}
