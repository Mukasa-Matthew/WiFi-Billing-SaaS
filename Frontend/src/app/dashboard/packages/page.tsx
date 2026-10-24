"use client";
import React, { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import api from "@/lib/api";
import { Plus, Search, RefreshCw, Zap, BookOpen, Trash2 } from "lucide-react";
interface Plan { id: number; name: string; price: number; rateLimit: string; sessionTimeout: number; sharedUsers: number; availability: string; featured: boolean; routerName?: string | null }
const statuses = ["ALL", "ACTIVE", "HIDDEN", "DISABLED"];
function duration(seconds: number) { const unit = seconds % 86400 === 0 ? [86400, "days"] : seconds % 3600 === 0 ? [3600, "hours"] : [60, "minutes"]; return `${seconds / Number(unit[0])} ${unit[1]}`; }
export default function PackagesPage() {
  const [plans, setPlans] = useState<Plan[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("ALL");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [templates, setTemplates] = useState(false);
  const [guide, setGuide] = useState(false);
  const [saving, setSaving] = useState<number | null>(null);
  const load = useCallback(async () => { setLoading(true); try { setPlans((await api.get<Plan[]>("/api/packages")).data); setError(""); } catch { setError("Could not load packages. Please refresh to retry."); } finally { setLoading(false); } }, []);
  useEffect(() => { void load(); }, [load]);
  const filtered = plans.filter(p => (status === "ALL" || p.availability === status) && p.name.toLowerCase().includes(search.toLowerCase()));
  const pages = Math.max(1, Math.ceil(filtered.length / size));
  const current = Math.min(page, pages);
  const change = async (plan: Plan, availability: string) => {
    setSaving(plan.id);
    try { await api.patch(`/api/packages/${plan.id}/availability`, { availability }); await load(); }
    catch { setError("Could not update availability. Please try again."); }
    finally { setSaving(null); }
  };
  const remove = async (plan: Plan) => {
    if (!window.confirm(`Delete "${plan.name}"? It disappears from the portal immediately.`)) return;
    setSaving(plan.id);
    try { await api.delete(`/api/packages/${plan.id}`); await load(); }
    catch { setError("Could not delete this package. Vouchers may still reference it."); }
    finally { setSaving(null); }
  };
  return <div className="mx-auto max-w-7xl space-y-6">
    <div className="flex flex-wrap items-center justify-between gap-4"><div><h1 className="text-xl font-bold tracking-tight text-slate-900">Packages</h1><p className="mt-2 text-sm text-slate-500">Manage internet plans, pricing, speeds, and customer access.</p></div><div className="flex flex-wrap gap-2">
      <button onClick={() => setTemplates(!templates)} className="flex items-center gap-2 rounded-lg border border-slate-300 bg-white px-3 py-2 text-xs text-blue-700"><Zap size={14} />Quick templates</button>
      <button onClick={() => setGuide(!guide)} className="flex items-center gap-2 rounded-lg border border-slate-300 px-3 py-2 text-xs text-slate-700"><BookOpen size={14} />Package guide</button>
      <Link href="/dashboard/packages/new" className="flex items-center gap-2 rounded-lg bg-blue-600 px-3 py-2 text-xs font-semibold text-white hover:bg-blue-500"><Plus size={14} />Create package</Link>
    </div></div>
    {templates && <div className="grid gap-3 rounded-xl border border-slate-200 bg-white p-4 sm:grid-cols-4">{["hourly", "daily", "weekly", "monthly"].map(t => <Link key={t} href={`/dashboard/packages/new?template=${t}`} className="rounded-lg border border-slate-300 p-3 text-sm capitalize text-blue-700 hover:bg-slate-100">{t} plan<small className="mt-1 block text-xs normal-case text-slate-500">Review settings before saving</small></Link>)}</div>}
    {guide && <div className="rounded-xl border border-slate-200 bg-white p-5 text-sm leading-7 text-slate-500">Active plans appear on the customer portal. Hidden plans stay off the portal and support voucher-only distribution. Disabled plans are removed from the portal and cannot be purchased. Existing vouchers keep their issued access. Duration is the session time allowance; upload and download are per-user speeds. Data volume is currently unlimited.</div>}
    {error && <p role="alert" className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-300">{error}</p>}
    <div className="flex flex-wrap gap-2">{statuses.map(s => <button key={s} onClick={() => { setStatus(s); setPage(1); }} className={`rounded-lg border px-4 py-2 text-xs capitalize ${status === s ? "border-blue-500/40 bg-blue-50 text-blue-700" : "border-slate-200 bg-white text-slate-500"}`}>{s === "ALL" ? "All packages" : s.toLowerCase()} <span className="ml-2 rounded border border-slate-300 px-1.5">{plans.filter(p => s === "ALL" || p.availability === s).length}</span></button>)}</div>
    <section className="overflow-hidden rounded-xl border border-slate-200 bg-white">
      <div className="flex justify-end gap-3 border-b border-slate-200 p-3"><label className="flex items-center gap-2 rounded-lg border border-slate-300 bg-slate-100 px-3"><Search size={14} className="text-slate-500" /><input aria-label="Search packages" placeholder="Search packages" value={search} onChange={e => { setSearch(e.target.value); setPage(1); }} className="w-40 bg-transparent py-2 text-xs text-slate-900 outline-none sm:w-56" /></label><button onClick={load} aria-label="Refresh packages" className="p-2 text-slate-500"><RefreshCw size={16} className={loading ? "animate-spin" : ""} /></button></div>
      <div className="overflow-x-auto"><table className="w-full whitespace-nowrap text-left text-xs"><thead className="bg-slate-100 text-slate-500"><tr>{["Package name", "Price", "Validity", "Download", "Upload", "Data policy", "Customers", "Availability"].map(h => <th key={h} className="px-5 py-4 font-medium">{h}</th>)}</tr></thead><tbody className="divide-y divide-slate-100">
        {!loading && filtered.slice((current - 1) * size, current * size).map(p => <tr key={p.id} className="hover:bg-slate-100/40"><td className="px-5 py-4 font-medium text-slate-700">{p.name}{p.featured && <span className="ml-2 text-[10px] text-amber-700">Popular</span>}{p.routerName && <span className="ml-2 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500">{p.routerName}</span>}</td><td className="px-5 py-4 text-blue-700">UGX {Number(p.price).toLocaleString()}</td><td className="px-5 py-4 text-amber-700">{duration(p.sessionTimeout)}</td><td className="px-5 py-4 text-slate-500">{p.rateLimit?.split("/")[1] || "—"}</td><td className="px-5 py-4 text-slate-500">{p.rateLimit?.split("/")[0] || "—"}</td><td className="px-5 py-4 text-slate-500">Unlimited</td><td className="px-5 py-4 text-slate-700">{p.sharedUsers}</td><td className="px-5 py-4"><select aria-label={`Availability for ${p.name}`} value={p.availability} disabled={saving === p.id} onChange={e => change(p, e.target.value)} className={`rounded border border-slate-300 bg-slate-100 p-1.5 ${p.availability === "ACTIVE" ? "text-emerald-700" : p.availability === "DISABLED" ? "text-red-600" : "text-slate-500"}`}>{statuses.slice(1).map(s => <option key={s} value={s}>{s.toLowerCase()}</option>)}</select><button aria-label={`Delete ${p.name}`} disabled={saving === p.id} onClick={() => remove(p)} className="ml-3 align-middle text-slate-400 hover:text-red-600 disabled:opacity-30"><Trash2 size={15} /></button></td></tr>)}
        {(loading || !filtered.length) && <tr><td colSpan={8} className="py-16 text-center text-slate-500">{loading ? "Loading packages…" : plans.length ? "No packages match your filters." : "No packages yet. Create your first internet plan."}</td></tr>}
      </tbody></table></div>
      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 p-4 text-xs text-slate-500"><span>Showing {filtered.length ? (current - 1) * size + 1 : 0}–{Math.min(current * size, filtered.length)} of {filtered.length}</span><div className="flex items-center gap-3"><select aria-label="Packages per page" value={size} onChange={e => { setSize(Number(e.target.value)); setPage(1); }} className="rounded border border-slate-300 bg-slate-100 p-2">{[10, 25, 50].map(n => <option key={n} value={n}>{n} per page</option>)}</select><button disabled={current === 1} onClick={() => setPage(current - 1)} className="disabled:opacity-30">Previous</button><span>{current} / {pages}</span><button disabled={current === pages} onClick={() => setPage(current + 1)} className="disabled:opacity-30">Next</button></div></div>
    </section>
  </div>;
}
