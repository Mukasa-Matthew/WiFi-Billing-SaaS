"use client";

import React, { useState } from "react";
import Link from "next/link";
import api from "@/lib/api";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import { Dialog } from "@/components/ui/Dialog";
import { useToast } from "@/components/ui/Toast";
import { useResource, Table, Badge, ErrorNotice, button, buttonGhost, field, date, message, FilterTabs, PageHeader, label } from "@/components/BillingUI";
import { Plus, DownloadSimple } from "@/components/icons";

interface Voucher { id: number; code: string; status: string; packageName: string; price: number; customerId: number | null; customerName: string | null; customerPhone: string | null; batchReference: string | null; saleType: string; delivery: string; archived: boolean; createdAt: string; firstLogin: string | null; expiresAt: string | null }
interface Customer { id: number; name: string }
interface Router { id: number; name: string }

const tabs = ["AVAILABLE", "ONLINE", "WALK_IN", "EXPIRED", "ARCHIVED", "GIVEAWAY", "ALL"];
const matches = (voucher: Voucher, tab: string) => tab === "ALL" ? true : tab === "ARCHIVED" ? voucher.archived : voucher.archived ? false : tab === "AVAILABLE" ? voucher.status === "unused" && voucher.saleType !== "GIVEAWAY" : tab === "EXPIRED" ? ["expired", "revoked"].includes(voucher.status) : voucher.saleType === tab;

export default function VouchersPage() {
  const { data, loading, error, load } = useResource<Voucher>("/api/vouchers");
  const customers = useResource<Customer>("/api/customers");
  const routers = useResource<Router>("/api/routers");
  const [tab, setTab] = useState("AVAILABLE");
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

  const sell = async (event: React.FormEvent) => {
    event.preventDefault();
    if (selected.length !== 1 || busy) return;
    setBusy("sale"); setNote("");
    try {
      await api.post(`/api/vouchers/${selected[0]}/sale`, { routerId: Number(router), amount: Number(amount), method });
      setSale(false); setSelected([]); toast("success", "Walk-in payment recorded."); await load();
    } catch (err) { setNote(message(err)); }
    finally { setBusy(""); }
  };

  const revoke = (voucher: Voucher) => confirm({
    title: "Revoke voucher?",
    body: `Revoke ${voucher.code}? The voucher will stop working and this cannot be undone.`,
    confirmLabel: "Revoke voucher",
    onConfirm: async () => { try { await api.post(`/api/vouchers/${voucher.code}/revoke`); toast("success", `Voucher ${voucher.code} revoked.`); await load(); } catch (err) { toast("error", message(err)); } },
  });

  const assign = async () => {
    if (!selected.length || !customer || busy) return;
    setBusy("assign"); setNote("");
    try { await api.post("/api/vouchers/assign", { codes: selected, customerId: Number(customer) }); setSelected([]); toast("success", "Vouchers assigned to customer."); await load(); }
    catch (err) { setNote(message(err)); }
    finally { setBusy(""); }
  };

  const exportCsv = () => {
    const rows = data.filter((voucher) => selected.includes(voucher.code));
    const csv = [["Voucher", "Package", "Price UGX", "Batch"], ...rows.map((voucher) => [voucher.code, voucher.packageName, String(voucher.price), voucher.batchReference || ""])]
      .map((row) => row.map((value) => `"${value.replace(/"/g, '""').replace(/^[=+@-]/, "'")}"`).join(",")).join("\r\n");
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
    const anchor = document.createElement("a"); anchor.href = url; anchor.download = "vouchers.csv"; anchor.click(); URL.revokeObjectURL(url);
    toast("success", `${rows.length} voucher${rows.length === 1 ? "" : "s"} exported.`);
  };

  const visible = data.filter((voucher) => matches(voucher, tab));
  const selectedVoucher = data.find((voucher) => voucher.code === selected[0]);

  return <div className="mx-auto max-w-7xl space-y-6">
    {dialog}
    <PageHeader title="Vouchers" description="Generate access codes, organize batches, and track customer use." action={<Link href="/dashboard/vouchers/new" className={`${button} w-full sm:w-auto`}><Plus size={15} aria-hidden="true" /> Generate vouchers</Link>} />
    <ErrorNotice text={error || note} />
    <div className="overflow-x-auto pb-1"><FilterTabs value={tab} onChange={setTab} options={tabs.map((key) => ({ key, label: key.replaceAll("_", " "), count: data.filter((voucher) => matches(voucher, key)).length }))} /></div>

    <section className="panel-shadow flex flex-col gap-3 rounded-xl border border-[var(--border)] bg-[var(--surface)] p-4 lg:flex-row lg:items-center">
      <span className="shrink-0 text-xs font-medium text-[var(--fg-muted)]">{selected.length} selected</span>
      <select aria-label="Customer for voucher assignment" value={customer} onChange={(event) => setCustomer(event.target.value)} className={`${field} !mt-0 lg:max-w-xs`}><option value="">Assign to customer</option>{customers.data.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select>
      <button disabled={!selected.length || !customer || !!busy} onClick={() => void assign()} className={button}>{busy === "assign" ? "Assigning…" : "Bulk assign"}</button>
      <button disabled={!selected.length || !!busy} onClick={exportCsv} className={buttonGhost}><DownloadSimple size={15} aria-hidden="true" /> Export CSV</button>
      {selected.length === 1 && <button onClick={() => { setAmount(String(selectedVoucher?.price || "")); setSale(true); }} className={`${buttonGhost} lg:ml-auto`}>Record walk-in sale</button>}
    </section>

    <Table emptyTitle="No vouchers in this state" emptyDescription="Generate vouchers or adjust the filter to see records." rows={visible} loading={loading} columns={[
      { label: "Select", render: (voucher) => <input aria-label={`Select ${voucher.code}`} type="checkbox" checked={selected.includes(voucher.code)} onChange={(event) => setSelected(event.target.checked ? [...selected, voucher.code] : selected.filter((code) => code !== voucher.code))} /> },
      { label: "Voucher code", render: (voucher) => <Badge tone="green">{voucher.code}</Badge> }, { label: "Package", render: (voucher) => voucher.packageName },
      { label: "Status", render: (voucher) => <Badge tone={["expired", "revoked"].includes(voucher.status) ? "red" : "green"}>{voucher.status}</Badge> }, { label: "Sale type", render: (voucher) => voucher.saleType },
      { label: "Batch", render: (voucher) => voucher.batchReference || "—" }, { label: "Customer", render: (voucher) => voucher.customerName || "—" }, { label: "Phone", render: (voucher) => voucher.customerPhone || "—" },
      { label: "Generated", render: (voucher) => date(voucher.createdAt) }, { label: "First login", render: (voucher) => date(voucher.firstLogin) }, { label: "Expires", render: (voucher) => date(voucher.expiresAt) },
      { label: "Action", render: (voucher) => <button disabled={["expired", "revoked"].includes(voucher.status)} onClick={() => revoke(voucher)} className="min-h-10 rounded-lg px-2 text-xs font-semibold text-red-600 transition hover:bg-red-50 disabled:opacity-30">Revoke</button> },
    ]} />

    {sale && selectedVoucher && <Dialog title="Record walk-in sale" description={`Voucher ${selectedVoucher.code} · ${selectedVoucher.packageName}`} onClose={() => { if (!busy) setSale(false); }}>
      <form onSubmit={sell} className="space-y-4">
        <div><label className={label}>Router</label><select required value={router} onChange={(event) => setRouter(event.target.value)} className={field}><option value="">Select router</option>{routers.data.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></div>
        <div><label className={label}>Amount received (UGX)</label><input type="number" required min="0.01" step="0.01" value={amount} onChange={(event) => setAmount(event.target.value)} className={field} /></div>
        <div><label className={label}>Payment method</label><select value={method} onChange={(event) => setMethod(event.target.value)} className={field}>{["CASH", "MTN", "AIRTEL", "BANK", "OTHER"].map((item) => <option key={item}>{item}</option>)}</select></div>
        <div className="flex flex-col-reverse gap-3 sm:flex-row sm:justify-end"><button type="button" disabled={!!busy} onClick={() => setSale(false)} className={buttonGhost}>Cancel</button><button disabled={!!busy} className={button}>{busy === "sale" ? "Recording…" : "Record payment"}</button></div>
      </form>
    </Dialog>}
  </div>;
}
