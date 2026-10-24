"use client";
import React, { useEffect, useState } from "react";
import api from "@/lib/api";
import { Table, Badge, Notice, panel, button, buttonGhost, field, label, hint, message } from "@/components/BillingUI";
import { X, Plus, CheckCircle } from "@phosphor-icons/react";

interface Overview {
  totalTenants: number; subscribed: number; active: number; dueSoon: number;
  overdue: number; suspended: number; monthRevenue: number; outstanding: number; defaultFee: number;
}
interface Row {
  id: number; tenantId: number; businessName: string; monthlyFee: number;
  startDate: string; nextRenewalDate: string; status: string; displayStatus: string; outstanding: number;
}
interface Invoice { id: number; periodStart: string; periodEnd: string; amountDue: number; status: string; paidAt: string | null; }
interface Payment { id: number; amount: number; method: string; externalRef: string | null; paidAt: string; note: string | null; }
interface Billing { subscription: Row; invoices: Invoice[]; payments: Payment[]; }
interface TenantOpt { id: number; name: string; }

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

export default function AdminSubscriptionsPage() {
  const [ov, setOv] = useState<Overview | null>(null);
  const [rows, setRows] = useState<Row[]>([]);
  const [tenants, setTenants] = useState<TenantOpt[]>([]);
  const [err, setErr] = useState("");
  const [ok, setOk] = useState("");
  const [dialog, setDialog] = useState<null | "create" | "fee" | "pay" | "billing" | "defaultFee">(null);
  const [sel, setSel] = useState<Row | null>(null);
  const [billing, setBilling] = useState<Billing | null>(null);
  const [busy, setBusy] = useState(false);

  // form fields
  const [fTenant, setFTenant] = useState("");
  const [fFee, setFFee] = useState("");
  const [fStart, setFStart] = useState("");
  const [fAmount, setFAmount] = useState("");
  const [fMethod, setFMethod] = useState("CASH");
  const [fRef, setFRef] = useState("");
  const [fNote, setFNote] = useState("");
  const [fChannel, setFChannel] = useState("EXTERNAL");

  const load = async () => {
    try {
      const [o, r, t] = await Promise.all([
        api.get<Overview>("/api/admin/subscriptions/overview"),
        api.get<Row[]>("/api/admin/subscriptions"),
        api.get<TenantOpt[]>("/api/admin/subscriptions/unsubscribed-tenants"),
      ]);
      setOv(o.data); setRows(r.data); setTenants(t.data);
    } catch (e) { setErr(message(e)); }
  };
  useEffect(() => { void load(); }, []);

  const act = async (fn: () => Promise<unknown>, done: string) => {
    setBusy(true); setErr(""); setOk("");
    try { await fn(); setOk(done); setDialog(null); await load(); }
    catch (e) { setErr(message(e)); }
    finally { setBusy(false); }
  };

  const openBilling = async (r: Row) => {
    setSel(r); setDialog("billing");
    try { setBilling((await api.get<Billing>(`/api/admin/subscriptions/${r.id}/billing`)).data); }
    catch (e) { setErr(message(e)); }
  };

  const statCards = [
    { label: "Tenants", value: ov?.totalTenants },
    { label: "Active subscriptions", value: ov?.active },
    { label: "Due soon", value: ov?.dueSoon },
    { label: "Past due", value: ov?.overdue },
    { label: "Suspended", value: ov?.suspended },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-slate-900">Subscriptions</h1>
          <p className="mt-1 text-sm text-slate-500">
            SaaS fees tenants pay to use this platform — separate from their customer WiFi revenue.
          </p>
        </div>
        <div className="flex gap-2">
          <button className={buttonGhost} onClick={() => { setFFee(String(ov?.defaultFee ?? 25000)); setDialog("defaultFee"); }}>
            Default fee
          </button>
          <button className={button} onClick={() => { setFTenant(""); setFFee(""); setFStart(new Date().toISOString().slice(0, 10)); setDialog("create"); }}>
            <Plus size={15} weight="bold" /> New subscription
          </button>
        </div>
      </div>

      <Notice text={err} />
      {ok && <p className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-xs font-medium text-emerald-800">{ok}</p>}

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        {statCards.map((c) => (
          <div key={c.label} className={`${panel} p-5`}>
            <div className="text-xs font-semibold text-slate-500">{c.label}</div>
            <div className="mt-2 text-2xl font-bold text-slate-900">{c.value ?? "—"}</div>
          </div>
        ))}
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <div className={`${panel} p-5`}>
          <div className="text-xs font-semibold text-slate-500">Subscription revenue this month</div>
          <div className="mt-2 text-2xl font-bold text-emerald-700">{money(ov?.monthRevenue)}</div>
          <p className="mt-1 text-xs text-slate-400">Verified payments recorded by administrators</p>
        </div>
        <div className={`${panel} p-5`}>
          <div className="text-xs font-semibold text-slate-500">Outstanding balances</div>
          <div className="mt-2 text-2xl font-bold text-red-600">{money(ov?.outstanding)}</div>
          <p className="mt-1 text-xs text-slate-400">Unpaid amounts across all billing periods</p>
        </div>
      </div>

      <div className={panel}>
        <Table
          rows={rows}
          emptyTitle="No subscriptions yet — create one above."
          columns={[
            { label: "Tenant", render: (r: Row) => <span className="font-medium">{r.businessName}</span> },
            { label: "Monthly fee", render: (r: Row) => <span className="font-semibold">{money(r.monthlyFee)}</span> },
            { label: "Started", render: (r: Row) => <span className="text-slate-600">{d(r.startDate)}</span> },
            { label: "Next renewal", render: (r: Row) => <span className="text-slate-600">{d(r.nextRenewalDate)}</span> },
            { label: "Status", render: (r: Row) => (
              <span>
                <Badge tone={TONE[r.displayStatus] || "slate"}>{STATUS_NAME[r.displayStatus] || r.displayStatus}</Badge>
                {r.outstanding > 0 && <div className="mt-1 text-[11px] text-red-500">Owes {money(r.outstanding)}</div>}
              </span>
            )},
            { label: "Actions", render: (r: Row) => (
              <span className="flex flex-wrap gap-1.5 text-xs">
                <button className="font-semibold text-blue-700 hover:underline" onClick={() => openBilling(r)}>Billing</button>
                {(r.displayStatus === "PAYMENT_DUE" || r.displayStatus === "PAST_DUE" || r.outstanding > 0) && (
                  <button className="font-semibold text-emerald-700 hover:underline"
                    onClick={() => { setSel(r); setFAmount(""); setFMethod("CASH"); setFRef(""); setFNote(""); setDialog("pay"); }}>
                    Record payment
                  </button>
                )}
                <button className="font-semibold text-slate-600 hover:underline"
                  onClick={() => { setSel(r); setFFee(String(r.monthlyFee)); setDialog("fee"); }}>
                  Edit fee
                </button>
                {r.status === "SUSPENDED"
                  ? <button className="font-semibold text-emerald-700 hover:underline"
                      onClick={() => act(() => api.post(`/api/admin/subscriptions/${r.id}/reactivate`), "Subscription reactivated.")}>
                      Reactivate
                    </button>
                  : <button className="font-semibold text-red-600 hover:underline"
                      onClick={() => { if (window.confirm(`Suspend ${r.businessName}'s subscription?`)) void act(() => api.post(`/api/admin/subscriptions/${r.id}/suspend`), "Subscription suspended."); }}>
                      Suspend
                    </button>}
              </span>
            )},
          ]}
        />
      </div>

      {/* ---- dialogs ---- */}
      {dialog && dialog !== "billing" && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4" onClick={() => setDialog(null)}>
          <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-xl" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between">
              <h3 className="text-base font-bold text-slate-900">
                {dialog === "create" && "New subscription"}
                {dialog === "fee" && `Edit monthly fee — ${sel?.businessName}`}
                {dialog === "pay" && `Record payment — ${sel?.businessName}`}
                {dialog === "defaultFee" && "Default monthly fee"}
              </h3>
              <button onClick={() => setDialog(null)} className="text-slate-400 hover:text-slate-600"><X size={18} /></button>
            </div>

            {dialog === "create" && (
              <form className="mt-5 space-y-4" onSubmit={(e) => { e.preventDefault(); void act(() => api.post("/api/admin/subscriptions", {
                tenantId: Number(fTenant), monthlyFee: fFee ? Number(fFee) : undefined, startDate: fStart || undefined,
              }), "Subscription created."); }}>
                <div>
                  <label className={label}>Tenant</label>
                  <select className={field} required value={fTenant} onChange={(e) => setFTenant(e.target.value)}>
                    <option value="">Select a tenant…</option>
                    {tenants.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
                  </select>
                  {tenants.length === 0 && <p className={hint}>Every tenant already has a subscription.</p>}
                </div>
                <div>
                  <label className={label}>Monthly fee (UGX)</label>
                  <input className={field} type="number" min="1" value={fFee} onChange={(e) => setFFee(e.target.value)}
                    placeholder={`Default: ${money(ov?.defaultFee)}`} />
                  <p className={hint}>Leave blank to use the default fee.</p>
                </div>
                <div>
                  <label className={label}>Start date</label>
                  <input className={field} type="date" value={fStart} onChange={(e) => setFStart(e.target.value)} />
                </div>
                <div className="flex justify-end gap-3">
                  <button type="button" className={buttonGhost} onClick={() => setDialog(null)}>Cancel</button>
                  <button type="submit" className={button} disabled={busy || !fTenant}>Create &amp; activate</button>
                </div>
              </form>
            )}

            {dialog === "fee" && (
              <form className="mt-5 space-y-4" onSubmit={(e) => { e.preventDefault(); void act(() => api.put(`/api/admin/subscriptions/${sel!.id}/fee`, { monthlyFee: Number(fFee) }), "Fee updated — applies from the next billing cycle."); }}>
                <div>
                  <label className={label}>Monthly fee (UGX)</label>
                  <input className={field} type="number" min="1" required value={fFee} onChange={(e) => setFFee(e.target.value)} />
                  <p className={hint}>Already-issued invoices keep their original amounts.</p>
                </div>
                <div className="flex justify-end gap-3">
                  <button type="button" className={buttonGhost} onClick={() => setDialog(null)}>Cancel</button>
                  <button type="submit" className={button} disabled={busy}>Save</button>
                </div>
              </form>
            )}

            {dialog === "defaultFee" && (
              <form className="mt-5 space-y-4" onSubmit={(e) => { e.preventDefault(); void act(() => api.put("/api/admin/subscriptions/default-fee", { fee: Number(fFee) }), "Default fee updated."); }}>
                <div>
                  <label className={label}>Default fee (UGX)</label>
                  <input className={field} type="number" min="1" required value={fFee} onChange={(e) => setFFee(e.target.value)} />
                  <p className={hint}>Used when a new subscription is created without a custom amount.</p>
                </div>
                <div className="flex justify-end gap-3">
                  <button type="button" className={buttonGhost} onClick={() => setDialog(null)}>Cancel</button>
                  <button type="submit" className={button} disabled={busy}>Save</button>
                </div>
              </form>
            )}

            {dialog === "pay" && (
              <form className="mt-5 space-y-4" onSubmit={(e) => { e.preventDefault(); void act(() => api.post(`/api/admin/subscriptions/${sel!.id}/payments`, {
                amount: Number(fAmount), method: fMethod, externalRef: fRef || undefined, note: fNote || undefined,
                settlementChannel: fChannel,
              }), "Payment recorded."); }}>
                {sel!.outstanding > 0 && (
                  <p className="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-700">
                    Outstanding balance: <b>{money(sel!.outstanding)}</b>
                  </p>
                )}
                <div>
                  <label className={label}>Amount paid (UGX)</label>
                  <input className={field} type="number" min="1" required value={fAmount} onChange={(e) => setFAmount(e.target.value)} />
                </div>
                <div>
                  <label className={label}>Method</label>
                  <select className={field} value={fMethod} onChange={(e) => setFMethod(e.target.value)}>
                    <option value="CASH">Cash</option>
                    <option value="MTN">MTN Mobile Money</option>
                    <option value="AIRTEL">Airtel Money</option>
                    <option value="BANK">Bank transfer</option>
                  </select>
                </div>
                <div>
                  <label className={label}>Where did the money settle?</label>
                  <select className={field} value={fChannel} onChange={(e) => setFChannel(e.target.value)}>
                    <option value="EXTERNAL">Outside MarzPay (cash, bank, other wallet)</option>
                    <option value="MARZPAY">Into the platform MarzPay account</option>
                  </select>
                  <p className={hint}>Only MarzPay-settled funds become withdrawable earnings — choose honestly, it affects reconciliation.</p>
                </div>
                <div>
                  <label className={label}>External reference <span className="font-normal text-slate-400">(optional)</span></label>
                  <input className={field} value={fRef} onChange={(e) => setFRef(e.target.value)} placeholder="e.g. MoMo transaction id" />
                </div>
                <div>
                  <label className={label}>Note <span className="font-normal text-slate-400">(optional)</span></label>
                  <input className={field} value={fNote} onChange={(e) => setFNote(e.target.value)} />
                </div>
                <div className="flex justify-end gap-3">
                  <button type="button" className={buttonGhost} onClick={() => setDialog(null)}>Cancel</button>
                  <button type="submit" className={button} disabled={busy}>
                    <CheckCircle size={14} /> {busy ? "Saving…" : "Record payment"}
                  </button>
                </div>
              </form>
            )}
          </div>
        </div>
      )}

      {/* billing history */}
      {dialog === "billing" && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4" onClick={() => { setDialog(null); setBilling(null); }}>
          <div className="w-full max-w-2xl rounded-2xl bg-white p-6 shadow-xl" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between">
              <h3 className="text-base font-bold text-slate-900">Billing history — {sel?.businessName}</h3>
              <button onClick={() => { setDialog(null); setBilling(null); }} className="text-slate-400 hover:text-slate-600"><X size={18} /></button>
            </div>
            {!billing ? <p className="mt-6 text-sm text-slate-400">Loading…</p> : (
              <div className="mt-5 space-y-6">
                <div>
                  <h4 className="text-xs font-bold uppercase tracking-wide text-slate-500">Billing periods</h4>
                  <table className="mt-2 w-full text-sm">
                    <thead><tr className="text-left text-xs text-slate-400">
                      <th className="py-1.5">Period</th><th>Due</th><th>Status</th><th>Paid</th>
                    </tr></thead>
                    <tbody>
                      {billing.invoices.map((i) => (
                        <tr key={i.id} className="border-t border-slate-100">
                          <td className="py-2 text-slate-700">{d(i.periodStart)} → {d(i.periodEnd)}</td>
                          <td className="font-semibold">{money(i.amountDue)}</td>
                          <td><Badge tone={TONE[i.status] || "slate"}>{i.status}</Badge></td>
                          <td className="text-slate-600">{d(i.paidAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div>
                  <h4 className="text-xs font-bold uppercase tracking-wide text-slate-500">Payments</h4>
                  <table className="mt-2 w-full text-sm">
                    <thead><tr className="text-left text-xs text-slate-400">
                      <th className="py-1.5">Date</th><th>Amount</th><th>Method</th><th>Reference</th>
                    </tr></thead>
                    <tbody>
                      {billing.payments.length === 0 && <tr><td colSpan={4} className="py-3 text-slate-400">No payments recorded.</td></tr>}
                      {billing.payments.map((p) => (
                        <tr key={p.id} className="border-t border-slate-100">
                          <td className="py-2 text-slate-700">{d(p.paidAt)}</td>
                          <td className="font-semibold">{money(p.amount)}</td>
                          <td className="text-slate-600">{p.method}</td>
                          <td className="font-mono text-[11px] text-slate-400">{p.externalRef || "—"}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
