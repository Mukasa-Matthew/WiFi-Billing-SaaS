"use client";
import React, { useCallback, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import api from "@/lib/api";
import { Badge, ErrorNotice, PageHeader, Table, buttonGhost, date, field, hint, label, message, panel } from "@/components/BillingUI";
import { Dialog } from "@/components/ui/Dialog";
import { useToast } from "@/components/ui/Toast";
import { MagnifyingGlass, Phone, ArrowsClockwise } from "@/components/icons";

interface TxRow {
  id: number; tenantId: number; businessName: string; routerName: string | null;
  phone: string; amount: number; currency: string; network: string;
  status: string; voucherCode: string | null; reference: string;
  externalReference: string | null; providerTransactionId: string | null;
  gatewayFee: number | null; netAmount: number | null; platformFee: number | null;
  createdAt: string;
}
interface TxPage {
  rows: TxRow[]; total: number; page: number; pages: number;
  successful: number; pending: number; failed: number;
}

const TONE: Record<string, string> = { SUCCESSFUL: "green", PENDING: "amber", FAILED: "red" };
const money = (v?: number | null) => v == null ? "—" : `UGX ${v.toLocaleString()}`;

export default function AdminTransactionsPage() {
  const toast = useToast();
  // A support lookup started from the command center arrives as ?q=…
  const searchParams = useSearchParams();
  const [query, setQuery] = useState(() => searchParams.get("q") ?? "");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<TxPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [detail, setDetail] = useState<TxRow | null>(null);

  const load = useCallback(async (q: string, st: string, pg: number) => {
    setLoading(true);
    setError("");
    try {
      const params = new URLSearchParams();
      if (q.trim()) params.set("query", q.trim());
      if (st) params.set("status", st);
      params.set("page", String(pg));
      params.set("size", "25");
      const res = await api.get<TxPage>(`/api/admin/transactions?${params}`);
      setData(res.data);
    } catch (err) {
      setError(message(err));
    } finally {
      setLoading(false);
    }
  }, []);

  // Search-as-you-type: 350ms after they stop typing the query hits the API.
  useEffect(() => {
    const t = setTimeout(() => { setPage(0); void load(query, status, 0); }, 350);
    return () => clearTimeout(t);
  }, [query, status, load]);

  const goPage = (pg: number) => { setPage(pg); void load(query, status, pg); };

  return (
    <div className="space-y-5">
      <PageHeader
        title="Transactions"
        description="Every payment across every business. A client says they paid — type their number and see exactly what happened."
        action={<button onClick={() => void load(query, status, page)} className={buttonGhost}><ArrowsClockwise className="h-4 w-4" />Refresh</button>}
      />

      <section className={`${panel} p-4 sm:p-5`}>
        <label className={label} htmlFor="tx-search">Search by phone number, voucher code, or reference</label>
        <div className="relative mt-1.5">
          <MagnifyingGlass className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-[var(--fg-muted)]" />
          <input id="tx-search" autoFocus value={query} onChange={(e) => setQuery(e.target.value)}
            placeholder="e.g. 0789 123 456, FAWM4ETR, or a MarzPay reference"
            className={`${field} pl-10`} />
        </div>
        <p className={hint}>Matches any part of the phone number — paste it however the client gave it to you.</p>

        <div className="mt-4 flex flex-wrap items-center gap-2">
          {[["", "All"], ["SUCCESSFUL", "Successful"], ["PENDING", "Pending"], ["FAILED", "Failed"]].map(([v, lbl]) => (
            <button key={v} onClick={() => setStatus(v)}
              className={`rounded-full px-3.5 py-1.5 text-xs font-semibold transition ${status === v ? "bg-blue-700 text-white" : "border border-[var(--border-strong)] text-[var(--fg-body)] hover:bg-[var(--subtle)]"}`}>
              {lbl}{v && data ? ` (${v === "SUCCESSFUL" ? data.successful : v === "PENDING" ? data.pending : data.failed})` : ""}
            </button>
          ))}
          {data && <span className="ml-auto text-xs text-[var(--fg-muted)]">{data.total.toLocaleString()} transaction{data.total === 1 ? "" : "s"}</span>}
        </div>
      </section>

      {error && <ErrorNotice text={error} />}

      <Table<TxRow>
        rows={data?.rows ?? []}
        loading={loading}
        searchable={false}
        paginated={false}
        rowKey={(r) => r.id}
        emptyTitle={query ? "No payment matches that." : "No transactions yet."}
        emptyDescription={query ? "Check the number — or try the voucher code or MarzPay reference instead." : "Payments appear here as customers buy vouchers."}
        emptyIcon={<Phone className="h-6 w-6" />}
        columns={[
          { label: "When", render: (r) => <span className="text-xs">{date(r.createdAt)}</span>, hideBelow: "sm" },
          { label: "Business", render: (r) => <Link href={`/admin/tenants/${r.tenantId}`} className="font-medium text-blue-700 hover:underline">{r.businessName}</Link> },
          { label: "Phone", render: (r) => <span className="num">{r.phone}</span> },
          { label: "Amount", render: (r) => <span className="num font-medium">{money(r.amount)}</span> },
          { label: "Network", render: (r) => r.network, hideBelow: "md" },
          { label: "Status", render: (r) => <Badge tone={TONE[r.status] || "slate"}>{r.status.toLowerCase()}</Badge> },
          { label: "Voucher", render: (r) => r.voucherCode ? <span className="num font-semibold">{r.voucherCode}</span> : <span className="text-[var(--fg-muted)]">—</span>, hideBelow: "lg" },
          { label: "", render: (r) => <button onClick={() => setDetail(r)} className="text-xs font-semibold text-blue-700 hover:underline">Details</button> },
        ]}
      />

      {data && data.pages > 1 && (
        <div className="flex items-center justify-center gap-2">
          <button disabled={page === 0} onClick={() => goPage(page - 1)} className={buttonGhost}>← Newer</button>
          <span className="text-xs text-[var(--fg-muted)]">Page {page + 1} of {data.pages}</span>
          <button disabled={page + 1 >= data.pages} onClick={() => goPage(page + 1)} className={buttonGhost}>Older →</button>
        </div>
      )}

      {detail && (
        <Dialog title={`Payment ${money(detail.amount)}`} description={detail.businessName} onClose={() => setDetail(null)}>
          <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2.5 text-sm">
            <dt className="text-[var(--fg-muted)]">Status</dt><dd><Badge tone={TONE[detail.status] || "slate"}>{detail.status.toLowerCase()}</Badge></dd>
            <dt className="text-[var(--fg-muted)]">Phone</dt><dd className="num">{detail.phone}</dd>
            <dt className="text-[var(--fg-muted)]">Amount</dt><dd className="num">{money(detail.amount)}</dd>
            <dt className="text-[var(--fg-muted)]">Business received</dt><dd className="num">{money(detail.netAmount)}</dd>
            <dt className="text-[var(--fg-muted)]">AirPairy earned</dt><dd className="num">{money(detail.platformFee)}</dd>
            <dt className="text-[var(--fg-muted)]">Router</dt><dd>{detail.routerName || "—"}</dd>
            <dt className="text-[var(--fg-muted)]">Voucher</dt><dd className="num">{detail.voucherCode || "—"}</dd>
            <dt className="text-[var(--fg-muted)]">Time</dt><dd>{date(detail.createdAt)}</dd>
            <dt className="text-[var(--fg-muted)]">Our reference</dt><dd className="num break-all text-xs">{detail.reference}</dd>
            {detail.externalReference && <><dt className="text-[var(--fg-muted)]">Gateway ref</dt><dd className="num break-all text-xs">{detail.externalReference}</dd></>}
            {detail.providerTransactionId && <><dt className="text-[var(--fg-muted)]">Telco ref</dt><dd className="num break-all text-xs">{detail.providerTransactionId}</dd></>}
          </dl>
          {detail.status === "PENDING" && <p className="mt-4 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800">Payment was initiated but never confirmed. If the client was charged, check the telco ref with MarzPay — the voucher wasn't issued.</p>}
          {detail.status === "FAILED" && <p className="mt-4 rounded-lg bg-red-50 px-3 py-2 text-xs text-red-800">Payment failed — no voucher was issued and the client should not have been charged.</p>}
        </Dialog>
      )}
    </div>
  );
}
