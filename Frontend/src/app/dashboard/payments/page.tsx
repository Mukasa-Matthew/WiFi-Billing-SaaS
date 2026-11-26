"use client";

import React, { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import api from "@/lib/api";
import { useApi, Table, Badge, ErrorNotice, FilterTabs, Pager, date, PageHeader, StatCard, button, buttonGhost, field, panel, humanize, ugx, message } from "@/components/BillingUI";
import { Wallet, Money, Eye, EyeSlash, DownloadSimple, ArrowsClockwise, CreditCard, Phone, MagnifyingGlass, X, Receipt } from "@/components/icons";
import { useToast } from "@/components/ui/Toast";
import { RANGE_LABEL, DEFAULT_RANGE, presetRange, reportUrl, exportUrl, describeRange, kampalaToday, type RangeKey, type StatusGroup, type DateRange } from "@/lib/paymentRange";

interface Payment {
  id: number; reference: string; method: string; amount: number; status: string;
  phone: string; voucher: string | null; packageName: string | null; router: string | null;
  createdAt: string; notes: string | null; mobileMoneyId: string | null;
}

interface PhoneSearch {
  phone: string; payments: Payment[]; page: number; totalPages: number; totalElements: number;
  summary: { payments: number; successful: number; totalPaid: number; firstPaidAt: string | null; lastPaidAt: string | null };
}

interface Report {
  from: string; to: string;
  summary: { collected: number; mobileMoney: number; walkIn: number; total: number; successful: number; pending: number; failed: number };
  payments: Payment[]; page: number; totalPages: number; totalElements: number;
}

const statusTone = (status: string) => status === "SUCCESSFUL" ? "green" : status === "FAILED" ? "red" : "amber";
const statusLabel = (status: string) => status === "SUCCESSFUL" ? "Paid" : humanize(status);
const CHANNEL: Record<string, string> = { MTN: "MTN MoMo", AIRTEL: "Airtel Money", MOMO: "Mobile money", CASH: "Cash", BANK: "Bank", OTHER: "Other" };
const channelLabel = (method: string) => CHANNEL[method] ?? humanize(method);

export default function PaymentsPage() {
  const [rangeKey, setRangeKey] = useState<RangeKey>(DEFAULT_RANGE);
  const [custom, setCustom] = useState<DateRange>(() => presetRange("7d"));
  const [status, setStatus] = useState<StatusGroup>("ALL");
  const [page, setPage] = useState(0);
  const [hidden, setHidden] = useState(false);
  const [exporting, setExporting] = useState(false);
  const toast = useToast();

  const range = rangeKey === "custom" ? custom : presetRange(rangeKey);
  const validRange = range.from <= range.to;
  const report = useApi<Report>(validRange ? reportUrl(range, status, page) : null, 0, { keepPrevious: true });
  const data = report.data;
  const sum = data?.summary;
  const money = (value: number) => ugx(value, { hidden });
  const firstLoad = report.loading && !data;

  const pickRange = (key: RangeKey) => { setRangeKey(key); setPage(0); };
  const pickStatus = (key: string) => { setStatus(key as StatusGroup); setPage(0); };

  const exportCsv = async () => {
    setExporting(true);
    try {
      const res = await api.get<Blob>(exportUrl(range, status), { responseType: "blob" });
      const name = /filename="([^"]+)"/.exec(String(res.headers["content-disposition"] ?? ""))?.[1] ?? `payments-${range.from}-to-${range.to}.csv`;
      const url = URL.createObjectURL(res.data);
      const a = document.createElement("a"); a.href = url; a.download = name; a.click(); URL.revokeObjectURL(url);
      toast("success", "Export downloaded.");
    } catch (err) {
      // Blob error bodies need reading before message() can show the server's reason.
      const body = (err as { response?: { data?: unknown } })?.response?.data;
      if (body instanceof Blob) {
        try { toast("error", (JSON.parse(await body.text()) as { message?: string }).message || "Export failed."); return; } catch { /* fall through */ }
      }
      toast("error", message(err));
    } finally { setExporting(false); }
  };

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <PageHeader title="Payments" description="Every sale, from mobile money and walk-in."
        action={<div className="flex w-full gap-2 sm:w-auto">
          <button onClick={() => setHidden((v) => !v)} aria-label={hidden ? "Show amounts" : "Hide amounts"} title={hidden ? "Show amounts" : "Hide amounts"} className={`${buttonGhost} px-3`}>{hidden ? <EyeSlash className="h-4 w-4" /> : <Eye className="h-4 w-4" />}</button>
          <button onClick={() => void report.load()} disabled={report.refreshing} aria-label="Refresh" title="Refresh" className={`${buttonGhost} px-3`}><ArrowsClockwise className={`h-4 w-4 ${report.refreshing ? "animate-spin motion-reduce:animate-none" : ""}`} /></button>
          <button onClick={() => void exportCsv()} disabled={exporting || !validRange || !data?.totalElements} className={`${buttonGhost} flex-1 sm:flex-none`}>
            <DownloadSimple className="h-4 w-4" />{exporting ? "Exporting…" : "Export CSV"}
          </button>
        </div>}
      />

      {/* Date range */}
      <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <FilterTabs value={rangeKey} onChange={(k) => pickRange(k as RangeKey)}
          options={(Object.keys(RANGE_LABEL) as RangeKey[]).map((key) => ({ key, label: RANGE_LABEL[key] }))} />
        {rangeKey === "custom" ? (
          <div className="flex items-center gap-2 text-xs text-[var(--fg-muted)]">
            <label className="sr-only" htmlFor="pay-from">Start date</label>
            <input id="pay-from" type="date" value={custom.from} max={kampalaToday()} onChange={(e) => { setCustom((c) => ({ ...c, from: e.target.value })); setPage(0); }} className={`${field} mt-0 min-h-10 w-auto py-1.5`} />
            <span aria-hidden>to</span>
            <label className="sr-only" htmlFor="pay-to">End date</label>
            <input id="pay-to" type="date" value={custom.to} max={kampalaToday()} onChange={(e) => { setCustom((c) => ({ ...c, to: e.target.value })); setPage(0); }} className={`${field} mt-0 min-h-10 w-auto py-1.5`} />
          </div>
        ) : (
          <p className="text-sm font-medium text-[var(--fg-muted)]">{describeRange(range)}</p>
        )}
      </div>
      {!validRange && <ErrorNotice text="The start date must be on or before the end date." />}

      <div className="grid grid-cols-2 gap-3 xl:grid-cols-4">
        <StatCard label="Collected" loading={firstLoad} value={money(sum?.collected ?? 0)} icon={<Money />} tone="green" hintText={`${(sum?.successful ?? 0).toLocaleString()} paid sale${sum?.successful === 1 ? "" : "s"}`} />
        <StatCard label="Mobile money" loading={firstLoad} value={money(sum?.mobileMoney ?? 0)} icon={<CreditCard />} hintText="MTN, Airtel and other networks" />
        <StatCard label="Walk-in" loading={firstLoad} value={money(sum?.walkIn ?? 0)} icon={<Wallet />} hintText="Cash and other recorded sales" />
        <StatCard label="Failed or pending" loading={firstLoad} value={((sum?.failed ?? 0) + (sum?.pending ?? 0)).toLocaleString()} icon={<Receipt />}
          tone={(sum?.failed ?? 0) > 0 ? "amber" : "default"} hintText={`${(sum?.failed ?? 0).toLocaleString()} failed · ${(sum?.pending ?? 0).toLocaleString()} pending`} />
      </div>

      <ErrorNotice text={report.error} />

      <section className={`${panel} overflow-hidden`}>
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-[var(--border)] p-3">
          <FilterTabs value={status} onChange={pickStatus} loading={firstLoad} options={[
            { key: "ALL", label: "All", count: sum?.total },
            { key: "SUCCESSFUL", label: "Paid", count: sum?.successful },
            { key: "PENDING", label: "Pending", count: sum?.pending },
            { key: "FAILED", label: "Failed", count: sum?.failed },
          ]} />
          {report.refreshing && <span className="text-xs text-[var(--fg-faint)]">Updating…</span>}
        </div>
        <Table bare searchable={false} paginated={false} rows={data?.payments ?? []} loading={firstLoad}
          rowKey={(payment) => payment.id || payment.reference} emptyIcon={<CreditCard />}
          emptyTitle="No payments in this period" emptyDescription="Try a different date range or status."
          columns={[
            { label: "Date", render: (payment) => (
              <span className="block whitespace-nowrap">
                <span className="text-[var(--fg)]">{date(payment.createdAt)}</span>
                <span className="num block text-[11px] text-[var(--fg-faint)]">{payment.reference}</span>
                <span className="mt-1 block sm:hidden"><Badge dot tone={statusTone(payment.status)}>{statusLabel(payment.status)}</Badge></span>
              </span>
            )},
            { label: "Customer", hideBelow: "md", render: (payment) => <span>{payment.phone ? <span className="num inline-flex items-center gap-1.5"><Phone className="h-3.5 w-3.5 text-[var(--fg-faint)]" />{payment.phone}</span> : <span className="text-[var(--fg-faint)]">—</span>}{payment.voucher && <span className="num mt-1 block text-[11px] text-[var(--fg-muted)]">Voucher {payment.voucher}</span>}</span> },
            { label: "Plan", hideBelow: "lg", render: (payment) => <span><span className="block text-[var(--fg)]">{payment.packageName || "—"}</span>{payment.router && <span className="block text-[11px] text-[var(--fg-muted)]">{payment.router}</span>}</span> },
            { label: "Channel", hideBelow: "md", render: (payment) => <Badge tone="slate">{channelLabel(payment.method)}</Badge> },
            { label: "Status", hideBelow: "sm", render: (payment) => <Badge dot tone={statusTone(payment.status)}>{statusLabel(payment.status)}</Badge> },
            { label: "Amount", align: "right", render: (payment) => <span className="num font-semibold text-[var(--fg)]">{money(payment.amount)}</span> },
          ]}
        />
        {data && <Pager page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onPage={setPage} busy={report.refreshing} />}
      </section>

      <Suspense><PaymentLookup money={money} /></Suspense>
    </div>
  );
}

/**
 * Every payment a phone number has ever made, searched on the server across all
 * records (any date or status), so a customer's "I paid" can be checked.
 */
function PaymentLookup({ money }: { money: (value: number) => string }) {
  // Opened from a customer's history as /dashboard/payments?phone=…
  const initial = useSearchParams().get("phone") ?? "";
  const [text, setText] = useState(initial);
  const [phone, setPhone] = useState(initial);
  const [page, setPage] = useState(0);
  const url = phone ? `/api/finance/payments/search?phone=${encodeURIComponent(phone)}&page=${page}&size=25` : null;
  const result = useApi<PhoneSearch>(url, 0, { keepPrevious: true });
  const found = phone ? result.data : null;
  const digits = text.replace(/\D/g, "");
  const submit = (event: React.FormEvent) => { event.preventDefault(); if (digits.length >= 7) { setPhone(text.trim()); setPage(0); } };
  const clear = () => { setText(""); setPhone(""); setPage(0); };

  return (
    <section className={panel}>
      <form onSubmit={submit} className="flex flex-col gap-3 p-4 sm:flex-row sm:items-end">
        <label className="min-w-0 flex-1 text-xs font-semibold text-[var(--fg-muted)]">
          Look up a customer’s payments
          <input className={field} type="tel" inputMode="tel" value={text} onChange={(event) => setText(event.target.value)} placeholder="Phone number, e.g. 0772 123456" />
        </label>
        <div className="flex gap-2">
          <button type="submit" className={button} disabled={digits.length < 7 || (result.refreshing && !!phone)}><MagnifyingGlass className="h-4 w-4" />Search</button>
          {phone && <button type="button" onClick={clear} className={`${buttonGhost} px-3`} aria-label="Clear search"><X className="h-4 w-4" /></button>}
        </div>
      </form>
      {phone && <div className="border-t border-[var(--border)]">
        <ErrorNotice text={result.error} />
        {result.loading && <p className="px-4 py-6 text-center text-[13px] text-[var(--fg-muted)]">Searching…</p>}
        {found && <>
          <dl className="grid grid-cols-2 gap-x-6 gap-y-3 bg-[var(--subtle)] px-4 py-3 sm:grid-cols-4">
            <div><dt className="text-xs text-[var(--fg-muted)]">Number</dt><dd className="num text-[13px] font-semibold text-[var(--fg)]">{found.phone}</dd></div>
            <div><dt className="text-xs text-[var(--fg-muted)]">Total paid</dt><dd className="num text-[13px] font-semibold text-[var(--fg)]">{money(found.summary.totalPaid)}</dd></div>
            <div><dt className="text-xs text-[var(--fg-muted)]">Successful / all attempts</dt><dd className="num text-[13px] font-semibold text-[var(--fg)]">{found.summary.successful.toLocaleString()} / {found.summary.payments.toLocaleString()}</dd></div>
            <div><dt className="text-xs text-[var(--fg-muted)]">Last successful payment</dt><dd className="num text-[13px] font-semibold text-[var(--fg)]">{found.summary.lastPaidAt ? date(found.summary.lastPaidAt) : "Never"}</dd></div>
          </dl>
          <Table bare searchable={false} paginated={false} rows={found.payments} rowKey={(payment) => payment.id}
            emptyIcon={<Phone />} emptyTitle="No payments from this number" emptyDescription="Nothing was paid from this number. Check it with the customer."
            columns={[
              { label: "Date", render: (payment) => <span className="num">{date(payment.createdAt)}</span> },
              { label: "Amount", align: "right", render: (payment) => <span className="num font-semibold text-[var(--fg)]">{money(Number(payment.amount))}</span> },
              { label: "Status", render: (payment) => <Badge dot tone={statusTone(payment.status)}>{humanize(payment.status)}</Badge> },
              { label: "Channel", render: (payment) => <Badge tone="slate">{channelLabel(payment.method)}</Badge> },
              { label: "MoMo transaction ID", hideBelow: "lg", render: (payment) => payment.mobileMoneyId ? <span className="num text-xs">{payment.mobileMoneyId}</span> : <span className="text-[var(--fg-faint)]">—</span> },
              { label: "Voucher & package", render: (payment) => <span>{payment.voucher ? <span className="num block text-xs font-semibold">{payment.voucher}</span> : <span className="text-[var(--fg-faint)]">No voucher</span>}<span className="block text-[11px] text-[var(--fg-muted)]">{payment.packageName || "—"}</span></span> },
              { label: "Reference", hideBelow: "md", render: (payment) => <span className="num text-[11px] text-[var(--fg-muted)]">{payment.reference}</span> },
            ]}
          />
          <Pager page={found.page} totalPages={found.totalPages} totalElements={found.totalElements} onPage={setPage} busy={result.refreshing} />
        </>}
      </div>}
    </section>
  );
}
