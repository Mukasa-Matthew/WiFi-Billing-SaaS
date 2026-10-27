"use client";

import React, { useMemo, useState } from "react";
import { useResource, Table, Badge, ErrorNotice, FilterTabs, date, PageHeader, StatCard, buttonGhost, humanize, ugx, useNow } from "@/components/BillingUI";
import { Wallet, Money, CalendarBlank, Eye, EyeSlash, DownloadSimple, ArrowsClockwise, CreditCard, Phone } from "@/components/icons";
import { useToast } from "@/components/ui/Toast";

interface Payment {
  id: number; reference: string; method: string; amount: number; status: string;
  phone: string; voucher: string | null; packageName: string | null; router: string | null;
  createdAt: string; notes: string | null;
}

type PaymentFilter = "ALL" | "SUCCESSFUL" | "PENDING" | "FAILED";
const ONLINE = ["MTN", "AIRTEL"];
const successful = (payment: Payment) => payment.status === "SUCCESSFUL";
const sum = (rows: Payment[]) => rows.filter(successful).reduce((total, payment) => total + Number(payment.amount), 0);
const statusTone = (status: string) => status === "SUCCESSFUL" ? "green" : status === "FAILED" ? "red" : "amber";

export default function PaymentsPage() {
  const { data, loading, refreshing, error, load } = useResource<Payment>("/api/finance/payments", true);
  const [filter, setFilter] = useState<PaymentFilter>("ALL");
  const [hidden, setHidden] = useState(false);
  const now = useNow(60000);
  const toast = useToast();

  const metrics = useMemo(() => {
    const current = new Date(now || 0);
    const startToday = new Date(current.getFullYear(), current.getMonth(), current.getDate()).getTime();
    const startMonth = new Date(current.getFullYear(), current.getMonth(), 1).getTime();
    const startWeek = current.getTime() - (7 * 86400000);
    const today = data.filter((payment) => new Date(payment.createdAt).getTime() >= startToday);
    return {
      todayOnline: sum(today.filter((payment) => ONLINE.includes(payment.method))),
      todayWalkIn: sum(today.filter((payment) => !ONLINE.includes(payment.method))),
      week: sum(data.filter((payment) => new Date(payment.createdAt).getTime() >= startWeek)),
      month: sum(data.filter((payment) => new Date(payment.createdAt).getTime() >= startMonth)),
    };
  }, [data, now]);

  const counts = useMemo(() => ({
    ALL: data.length,
    SUCCESSFUL: data.filter((payment) => payment.status === "SUCCESSFUL").length,
    PENDING: data.filter((payment) => !["SUCCESSFUL", "FAILED"].includes(payment.status)).length,
    FAILED: data.filter((payment) => payment.status === "FAILED").length,
  }), [data]);

  const visible = useMemo(() => filter === "ALL" ? data : filter === "PENDING"
    ? data.filter((payment) => !["SUCCESSFUL", "FAILED"].includes(payment.status))
    : data.filter((payment) => payment.status === filter), [data, filter]);

  const money = (value: number) => hidden ? "UGX ••••••" : ugx(value);
  const exportCsv = () => {
    const rows = [["Reference", "Date", "Status", "Method", "Amount UGX", "Phone", "Voucher", "Package", "Router", "Notes"], ...visible.map((payment) => [payment.reference, payment.createdAt, payment.status, payment.method, String(payment.amount), payment.phone, payment.voucher || "", payment.packageName || "", payment.router || "", payment.notes || ""])];
    const csv = rows.map((row) => row.map((value) => `"${value.replace(/"/g, '""').replace(/^[=+@-]/, "'")}"`).join(",")).join("\r\n");
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    const anchor = document.createElement("a"); anchor.href = url; anchor.download = `payments-${filter.toLowerCase()}.csv`; anchor.click(); URL.revokeObjectURL(url);
    toast("success", `${visible.length} transaction${visible.length === 1 ? "" : "s"} exported.`);
  };

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <PageHeader title="Payments & transactions" description="Track mobile-money collections and manually recorded sales across your hotspot business."
        action={<div className="flex w-full gap-2 sm:w-auto">
          <button onClick={() => setHidden((value) => !value)} aria-label={hidden ? "Show revenue" : "Hide revenue"} className={`${buttonGhost} px-3`}>{hidden ? <EyeSlash className="h-4 w-4" /> : <Eye className="h-4 w-4" />}<span className="hidden sm:inline">{hidden ? "Show totals" : "Hide totals"}</span></button>
          <button onClick={() => void load()} disabled={refreshing} className={`${buttonGhost} px-3`}><ArrowsClockwise className={`h-4 w-4 ${refreshing ? "animate-spin" : ""}`} /><span className="hidden sm:inline">Refresh</span></button>
        </div>}
      />

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Online revenue today" value={loading ? "—" : money(metrics.todayOnline)} icon={<CreditCard />} hintText="Successful MTN and Airtel payments" tone="green" />
        <StatCard label="Walk-in revenue today" value={loading ? "—" : money(metrics.todayWalkIn)} icon={<Wallet />} hintText="Cash, bank, and manually recorded sales" />
        <StatCard label="Last 7 days" value={loading ? "—" : money(metrics.week)} icon={<CalendarBlank />} hintText="Successful revenue in the rolling week" />
        <StatCard label="This month" value={loading ? "—" : money(metrics.month)} icon={<Money />} hintText="Successful revenue since month start" />
      </div>

      <ErrorNotice text={error} />

      <Table rows={visible} loading={loading} rowKey={(payment) => payment.id || payment.reference}
        searchPlaceholder="Search reference, phone, voucher or package…" emptyIcon={<CreditCard />}
        emptyTitle="No transactions in this view" emptyDescription={data.length ? "Choose another status or adjust your search." : "Payments will appear here as customers buy access or staff record walk-in sales."}
        toolbar={<FilterTabs options={(["ALL", "SUCCESSFUL", "PENDING", "FAILED"] as PaymentFilter[]).map((key) => ({ key, label: key === "ALL" ? "All" : humanize(key), count: counts[key] }))} value={filter} onChange={(key) => setFilter(key as PaymentFilter)} />}
        columns={[
          { label: "Transaction", render: (payment) => <span className="block min-w-44"><span className="num block font-medium text-[var(--fg)]">{payment.reference}</span><span className="block text-[11px] text-[var(--fg-muted)]">{date(payment.createdAt)}</span></span> },
          { label: "Customer", render: (payment) => <span>{payment.phone ? <span className="num inline-flex items-center gap-1.5"><Phone className="h-3.5 w-3.5 text-[var(--fg-faint)]" />{payment.phone}</span> : <span className="text-[var(--fg-faint)]">Not captured</span>}{payment.voucher && <span className="num mt-1 block text-[11px] text-[var(--fg-muted)]">Voucher {payment.voucher}</span>}</span> },
          { label: "Package", hideBelow: "md", render: (payment) => <span><span className="block font-medium text-[var(--fg)]">{payment.packageName || "—"}</span><span className="block text-[11px] text-[var(--fg-muted)]">{payment.router || "Router unavailable"}</span></span> },
          { label: "Channel", render: (payment) => <Badge tone="slate">{humanize(payment.method)}</Badge> },
          { label: "Amount", align: "right", render: (payment) => <span className="num font-semibold text-[var(--fg)]">{hidden ? "UGX ••••" : ugx(payment.amount)}</span> },
          { label: "Status", render: (payment) => <Badge dot tone={statusTone(payment.status)}>{humanize(payment.status)}</Badge> },
          { label: "Notes", hideBelow: "lg", render: (payment) => payment.notes ? <span className="block max-w-48 truncate text-xs text-[var(--fg-muted)]" title={payment.notes}>{payment.notes}</span> : <span className="text-[var(--fg-faint)]">—</span> },
        ]}
      />

      <div className="flex justify-end"><button onClick={exportCsv} disabled={!visible.length} className={buttonGhost}><DownloadSimple className="h-4 w-4" />Export current view</button></div>
    </div>
  );
}
