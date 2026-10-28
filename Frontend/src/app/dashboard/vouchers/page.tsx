"use client";
import { BusyLabel, useSingleFlight } from "@/components/LoadingUI";
import { mutateCache } from "@/lib/cacheInvalidation";

import React, { useMemo, useState } from "react";
import Link from "next/link";
import api from "@/lib/api";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import { Dialog } from "@/components/ui/Dialog";
import { useToast } from "@/components/ui/Toast";
import { useResource, Table, Badge, ErrorNotice, button, buttonGhost, field, date, message, FilterTabs, PageHeader, label, StatCard, ugx, humanize } from "@/components/BillingUI";
import { Plus, DownloadSimple, Ticket, CheckCircle, Clock, Money, Copy, Archive } from "@/components/icons";

interface Voucher { id: number; code: string; status: string; packageId?: number; packageName: string; price: number; customerId: number | null; customerName: string | null; customerPhone: string | null; batchReference: string | null; saleType: string; delivery: string; archived: boolean; createdAt: string; firstLogin: string | null; expiresAt: string | null }
interface Customer { id: number; name: string }
interface Router { id: number; name: string }

const tabs = ["AVAILABLE", "ONLINE", "WALK_IN", "EXPIRED", "ARCHIVED", "GIVEAWAY", "ALL"];
const matches = (voucher: Voucher, tab: string) => tab === "ALL" ? true : tab === "ARCHIVED" ? voucher.archived : voucher.archived ? false : tab === "AVAILABLE" ? voucher.status === "unused" && voucher.saleType !== "GIVEAWAY" : tab === "EXPIRED" ? ["expired", "revoked"].includes(voucher.status) : voucher.saleType === tab;
const statusTone = (status: string) => status === "active" ? "blue" : status === "unused" ? "green" : status === "revoked" ? "red" : "amber";

export default function VouchersPage() {
  const { data, loading, error, load } = useResource<Voucher>("/api/vouchers");
  const customers = useResource<Customer>("/api/customers");
  const routers = useResource<Router>("/api/routers");
  const [tab, setTab] = useState("AVAILABLE");
  const [selectedPackage, setSelectedPackage] = useState<string>("ALL");
  const [selected, setSelected] = useState<string[]>([]);
  const [customer, setCustomer] = useState("");
  const [note, setNote] = useState("");
  const [sale, setSale] = useState(false);
  const [router, setRouter] = useState("");
  const [amount, setAmount] = useState("");
  const [method, setMethod] = useState("CASH");
  const [busy, setBusy] = useState<"sale" | "assign" | "">("");
  const toast = useToast();
  const { confirm, dialog } = useConfirmDialog();

  const singleFlight = useSingleFlight();
  const sell = singleFlight(async (event: React.FormEvent) => {
    event.preventDefault();
    if (selected.length !== 1 || busy) return;
    setBusy("sale"); setNote("");
    try {
      await api.post(`/api/vouchers/${selected[0]}/sale`, { routerId: Number(router), amount: Number(amount), method });
      setSale(false); setSelected([]); toast("success", "Walk-in payment recorded."); await load();
    } catch (err) { setNote(message(err)); }
    finally { setBusy(""); }
  });

  const revoke = (voucher: Voucher) => confirm({
    title: "Revoke voucher?",
    body: `Revoke ${voucher.code}? The voucher will stop working and this cannot be undone.`,
    confirmLabel: "Revoke voucher",
    onConfirm: async () => { try { await api.post(`/api/vouchers/${voucher.code}/revoke`); toast("success", `Voucher ${voucher.code} revoked.`); mutateCache.voucherChanged(); await load(); } catch (err) { toast("error", message(err)); } },
  });

  const archiveVoucher = (voucher: Voucher) => confirm({
    title: "Archive voucher?",
    body: `Move ${voucher.code} out of the active voucher lists? You can still find it under Archived.`,
    confirmLabel: "Archive voucher",
    onConfirm: async () => { try { await api.post(`/api/vouchers/${voucher.code}/archive`); setSelected((codes) => codes.filter((code) => code !== voucher.code)); toast("success", `Voucher ${voucher.code} archived.`); mutateCache.voucherChanged(); await load(); } catch (err) { toast("error", message(err)); } },
  });

  const assign = singleFlight(async () => {
    if (!selected.length || !customer || busy) return;
    setBusy("assign"); setNote("");
    try { await api.post("/api/vouchers/assign", { codes: selected, customerId: Number(customer) }); setSelected([]); toast("success", "Vouchers assigned to customer."); mutateCache.voucherChanged(); await load(); }
    catch (err) { setNote(message(err)); }
    finally { setBusy(""); }
  });

  const exportCsv = () => {
    const rows = data.filter((voucher) => selected.includes(voucher.code));
    const csv = [["Voucher", "Package", "Price UGX", "Batch"], ...rows.map((voucher) => [voucher.code, voucher.packageName, String(voucher.price), voucher.batchReference || ""])]
      .map((row) => row.map((value) => `"${value.replace(/"/g, '""').replace(/^[=+@-]/, "'")}"`).join(",")).join("\r\n");
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
    const anchor = document.createElement("a"); anchor.href = url; anchor.download = "vouchers.csv"; anchor.click(); URL.revokeObjectURL(url);
    toast("success", `${rows.length} voucher${rows.length === 1 ? "" : "s"} exported.`);
  };

  const packageCounts = useMemo(() => {
    // Counts how many vouchers belong to each package in the current status tab
    const map = new Map<string, number>();
    for (const v of data) {
      if (matches(v, tab)) {
        const name = v.packageName || "Unassigned";
        map.set(name, (map.get(name) || 0) + 1);
      }
    }
    return map;
  }, [data, tab]);

  const packageOptions = useMemo(() => {
    const names = Array.from(packageCounts.keys()).sort();
    return names;
  }, [packageCounts]);

  const visible = useMemo(() => {
    return data.filter((voucher) => {
      if (!matches(voucher, tab)) return false;
      if (selectedPackage !== "ALL" && (voucher.packageName || "Unassigned") !== selectedPackage) return false;
      return true;
    });
  }, [data, tab, selectedPackage]);
  const selectedVoucher = data.find((voucher) => voucher.code === selected[0]);
  const totals = useMemo(() => ({
    available: data.filter((voucher) => matches(voucher, "AVAILABLE")).length,
    inUse: data.filter((voucher) => voucher.status === "active" && !voucher.archived).length,
    sold: data.filter((voucher) => ["ONLINE", "WALK_IN"].includes(voucher.saleType) && !voucher.archived).length,
    value: data.filter((voucher) => matches(voucher, "AVAILABLE")).reduce((sum, voucher) => sum + Number(voucher.price), 0),
  }), [data]);

  return <div className="mx-auto max-w-7xl space-y-6">
    {dialog}
    <PageHeader title="Vouchers" description="Generate access codes, organize batches, and track customer use." action={<Link href="/dashboard/vouchers/new" className={`${button} w-full sm:w-auto`}><Plus size={15} aria-hidden="true" /> Generate vouchers</Link>} />
    <ErrorNotice text={error || note} />

    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
      <StatCard label="Available inventory" loading={loading} value={totals.available.toLocaleString()} icon={<Ticket />} hintText="Ready to sell or assign" tone="green" />
      <StatCard label="Currently in use" loading={loading} value={totals.inUse.toLocaleString()} icon={<Clock />} hintText="Activated hotspot sessions" />
      <StatCard label="Sold vouchers" loading={loading} value={totals.sold.toLocaleString()} icon={<CheckCircle />} hintText="Online and walk-in sales" />
      <StatCard label="Available value" loading={loading} value={ugx(totals.value)} icon={<Money />} hintText="Face value of unsold inventory" />
    </div>

    <div className="overflow-x-auto pb-1"><FilterTabs loading={loading} value={tab} onChange={(next) => { setTab(next); setSelected([]); setSelectedPackage("ALL"); }} options={tabs.map((key) => ({ key, label: key === "WALK_IN" ? "Walk-in" : humanize(key), count: data.filter((voucher) => matches(voucher, key)).length }))} /></div>

    {packageOptions.length > 0 && (
      <div className="flex flex-wrap items-center gap-1.5 rounded-xl border border-[var(--border)] bg-[var(--surface)] p-2">
        <span className="px-2 text-xs font-semibold uppercase tracking-wider text-[var(--fg-muted)]">Package:</span>
        <button
          type="button"
          onClick={() => setSelectedPackage("ALL")}
          className={"inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1 text-xs font-medium transition " + (selectedPackage === "ALL" ? "bg-[var(--primary)] text-white shadow-sm" : "text-[var(--fg)] hover:bg-[var(--fill)]")}
        >
          All Packages
          <span className={"rounded-full px-1.5 py-0.2 text-[10px] font-bold " + (selectedPackage === "ALL" ? "bg-white/20 text-white" : "bg-[var(--fill)] text-[var(--fg-muted)]")}>
            {data.filter((v) => matches(v, tab)).length}
          </span>
        </button>
        {packageOptions.map((pkgName) => {
          const count = packageCounts.get(pkgName) || 0;
          const active = selectedPackage === pkgName;
          return (
            <button
              key={pkgName}
              type="button"
              onClick={() => setSelectedPackage(pkgName)}
              className={"inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1 text-xs font-medium transition " + (active ? "bg-[var(--primary)] text-white shadow-sm" : "text-[var(--fg)] hover:bg-[var(--fill)]")}
            >
              {pkgName}
              <span className={"rounded-full px-1.5 py-0.2 text-[10px] font-bold " + (active ? "bg-white/20 text-white" : "bg-[var(--fill)] text-[var(--fg-muted)]")}>
                {count}
              </span>
            </button>
          );
        })}
      </div>
    )}

    {selected.length > 0 && <section className="panel-shadow flex flex-col gap-3 rounded-xl border border-blue-200 bg-blue-50/60 p-4 lg:flex-row lg:items-center">
      <div className="min-w-28"><p className="text-xs font-semibold text-blue-900">{selected.length} selected</p><button onClick={() => setSelected([])} className="mt-0.5 text-[11px] text-blue-700 hover:underline">Clear selection</button></div>
      <select aria-label="Customer for voucher assignment" value={customer} onChange={(event) => setCustomer(event.target.value)} className={`${field} !mt-0 bg-white lg:max-w-xs`}><option value="">Choose customer to assign</option>{customers.data.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select>
      <button disabled={!customer || !!busy} onClick={() => void assign()} className={button}><BusyLabel busy={busy === "assign"} label="Assigning…">Assign selected</BusyLabel></button>
      <button disabled={!!busy} onClick={exportCsv} className={buttonGhost}><DownloadSimple size={15} aria-hidden="true" /> Export CSV</button>
      {selected.length === 1 && selectedVoucher?.status === "unused" && selectedVoucher.saleType === "INVENTORY" && <button onClick={() => { setAmount(String(selectedVoucher.price || "")); setSale(true); }} className={`${buttonGhost} lg:ml-auto`}>Record walk-in sale</button>}
    </section>}

    <Table emptyTitle="No vouchers in this state" emptyDescription="Generate vouchers or adjust the filter to see records." rows={visible} loading={loading} columns={[
      { label: "", className: "w-10", render: (voucher) => <input aria-label={`Select ${voucher.code}`} type="checkbox" checked={selected.includes(voucher.code)} onChange={(event) => setSelected(event.target.checked ? [...new Set([...selected, voucher.code])] : selected.filter((code) => code !== voucher.code))} /> },
      { label: "Voucher", render: (voucher) => <div className="flex min-w-36 items-center gap-2"><button title="Copy voucher code" aria-label={`Copy ${voucher.code}`} onClick={() => { void navigator.clipboard.writeText(voucher.code); toast("success", `Copied ${voucher.code}.`); }} className="rounded-md p-1 text-[var(--fg-faint)] hover:bg-[var(--fill)] hover:text-[var(--fg)]"><Copy className="h-3.5 w-3.5" /></button><span className="num font-semibold tracking-wide text-[var(--fg)]">{voucher.code}</span></div> },
      { label: "Package", render: (voucher) => <span><span className="block font-medium text-[var(--fg)]">{voucher.packageName}</span><span className="num text-[11px] text-[var(--fg-muted)]">{ugx(voucher.price)}</span></span> },
      { label: "Status", render: (voucher) => <Badge dot tone={statusTone(voucher.status)}>{humanize(voucher.status)}</Badge> },
      { label: "Channel", hideBelow: "md", render: (voucher) => <Badge tone="slate">{humanize(voucher.saleType)}</Badge> },
      { label: "Customer", render: (voucher) => voucher.customerName ? <span><span className="block">{voucher.customerName}</span>{voucher.customerPhone && <span className="num text-[11px] text-[var(--fg-muted)]">{voucher.customerPhone}</span>}</span> : <span className="text-[var(--fg-faint)]">Unassigned</span> },
      { label: "Batch", hideBelow: "lg", render: (voucher) => <span className="num text-xs text-[var(--fg-muted)]">{voucher.batchReference || "—"}</span> },
      { label: "Timeline", hideBelow: "lg", render: (voucher) => <span className="text-xs"><span className="block text-[var(--fg-muted)]">Created {date(voucher.createdAt)}</span><span className="block text-[var(--fg-faint)]">{voucher.expiresAt ? `Expires ${date(voucher.expiresAt)}` : voucher.firstLogin ? `First used ${date(voucher.firstLogin)}` : "Not activated"}</span></span> },
      { label: "", align: "right", className: "w-24", render: (voucher) => ["expired", "revoked"].includes(voucher.status) && !voucher.archived ? <button onClick={() => archiveVoucher(voucher)} className="inline-flex min-h-9 items-center gap-1.5 rounded-lg px-2.5 text-xs font-semibold text-[var(--fg-muted)] hover:bg-[var(--fill)]"><Archive className="h-3.5 w-3.5" />Archive</button> : <button disabled={["expired", "revoked"].includes(voucher.status)} onClick={() => revoke(voucher)} className="min-h-9 rounded-lg px-2.5 text-xs font-semibold text-red-600 transition hover:bg-red-50 disabled:opacity-30">Revoke</button> },
    ]} />

    {sale && selectedVoucher && <Dialog title="Record walk-in sale" description={`Voucher ${selectedVoucher.code} · ${selectedVoucher.packageName}`} onClose={() => { if (!busy) setSale(false); }}>
      <form onSubmit={sell} className="space-y-4">
        <div><label className={label}>Router</label><select required value={router} onChange={(event) => setRouter(event.target.value)} className={field}><option value="">Select router</option>{routers.data.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></div>
        <div><label className={label}>Amount received (UGX)</label><input type="number" required min="0.01" step="0.01" value={amount} onChange={(event) => setAmount(event.target.value)} className={field} /></div>
        <div><label className={label}>Payment method</label><select value={method} onChange={(event) => setMethod(event.target.value)} className={field}>{["CASH", "MTN", "AIRTEL", "BANK", "OTHER"].map((item) => <option key={item}>{item}</option>)}</select></div>
        <div className="flex flex-col-reverse gap-3 sm:flex-row sm:justify-end"><button type="button" disabled={!!busy} onClick={() => setSale(false)} className={buttonGhost}>Cancel</button><button disabled={!!busy} className={button}><BusyLabel busy={busy === "sale"} label="Recording…">Record payment</BusyLabel></button></div>
      </form>
    </Dialog>}
  </div>;
}
