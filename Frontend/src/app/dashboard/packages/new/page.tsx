"use client";
import React, { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import api from "@/lib/api";

const input = "mt-2 w-full rounded-lg border border-slate-700 bg-slate-800 px-3 py-2.5 text-sm text-white outline-none focus:border-blue-500";
const presets: Record<string, { name: string; duration: string; unit: string; price: string }> = {
  hourly: { name: "Hourly Internet", duration: "1", unit: "3600", price: "500" },
  daily: { name: "Daily Internet", duration: "1", unit: "86400", price: "2000" },
  weekly: { name: "Weekly Internet", duration: "7", unit: "86400", price: "12000" },
  monthly: { name: "Monthly Internet", duration: "30", unit: "86400", price: "30000" },
};
function Section({ title, hint, children }: { title: string; hint: string; children: React.ReactNode }) {
  return <section className="rounded-xl border border-slate-800 bg-slate-900"><div className="border-b border-slate-800 px-5 py-4"><h2 className="text-sm font-semibold text-white">{title}</h2><p className="mt-1 text-xs text-slate-500">{hint}</p></div><div className="space-y-5 p-5">{children}</div></section>;
}
export default function CreatePackagePage() {
  const router = useRouter();
  const [name, setName] = useState(""); const [duration, setDuration] = useState("1"); const [unit, setUnit] = useState("86400");
  const [description, setDescription] = useState(""); const [upload, setUpload] = useState("5"); const [download, setDownload] = useState("5");
  const [price, setPrice] = useState(""); const [devices, setDevices] = useState("1"); const [availability, setAvailability] = useState("ACTIVE");
  const [featured, setFeatured] = useState(false); const [order, setOrder] = useState("0"); const [saving, setSaving] = useState(false); const [error, setError] = useState("");
  useEffect(() => { const template = presets[new URLSearchParams(window.location.search).get("template") || ""]; if (template) { setName(template.name); setDuration(template.duration); setUnit(template.unit); setPrice(template.price); } }, []);
  const save = async (e: React.FormEvent) => {
    e.preventDefault(); setError("");
    const seconds = Number(duration) * Number(unit);
    if (!Number.isSafeInteger(seconds) || seconds < 60 || seconds > 2147483647) { setError("Choose a valid duration of at least one minute."); return; }
    setSaving(true);
    try { await api.post("/api/packages", { name: name.trim(), description: description.trim(), price: Number(price), sessionTimeout: seconds, rateLimit: `${upload}M/${download}M`, sharedUsers: Number(devices), availability, featured, displayOrder: Number(order) }); router.push("/dashboard/packages"); }
    catch { setError("Could not save the package. Check your entries and try again."); }
    finally { setSaving(false); }
  };
  return <div className="mx-auto max-w-6xl space-y-6"><div><Link href="/dashboard/packages" className="text-xs text-slate-400 hover:text-white">Packages / Create</Link><h1 className="mt-3 text-2xl font-semibold text-white">Create package</h1><p className="mt-2 text-sm text-slate-400">Set your internet plan's duration, speeds, price, and availability.</p></div>
    <form onSubmit={save} className="space-y-5">
      <Section title="Basic configuration" hint="Define the package's basic settings">
        <label className="block text-xs text-slate-300">Package name *<input required maxLength={80} value={name} onChange={e => setName(e.target.value)} placeholder="e.g. Daily Internet" className={input} /></label>
        <div className="grid gap-5 sm:grid-cols-2"><label className="text-xs text-slate-300">Duration *<input type="number" required min={1} step={1} value={duration} onChange={e => setDuration(e.target.value)} className={input} /></label><label className="text-xs text-slate-300">Unit *<select value={unit} onChange={e => setUnit(e.target.value)} className={input}><option value="60">Minutes</option><option value="3600">Hours</option><option value="86400">Days</option></select></label></div>
        <label className="block text-xs text-slate-300">Description<textarea maxLength={500} rows={3} value={description} onChange={e => setDescription(e.target.value)} placeholder="Optional description for this package" className={input} /></label>
      </Section>
      <Section title="Speed & performance" hint="Set the maximum upload and download speeds per user">
        <div className="grid gap-5 sm:grid-cols-2"><label className="text-xs text-slate-300">Upload speed (Mbps) *<input type="number" required min="0.1" max="10000" step="0.1" value={upload} onChange={e => setUpload(e.target.value)} className={input} /></label><label className="text-xs text-slate-300">Download speed (Mbps) *<input type="number" required min="0.1" max="10000" step="0.1" value={download} onChange={e => setDownload(e.target.value)} className={input} /></label></div>
        <p className="text-xs text-slate-500">Data volume is unlimited. The duration above controls the session time allowance.</p>
      </Section>
      <Section title="Pricing & devices" hint="Set the package price and simultaneous device limit">
        <div className="grid gap-5 sm:grid-cols-2"><label className="text-xs text-slate-300">Price (UGX) *<input type="number" required min="0" max="9999999999" step="0.01" value={price} onChange={e => setPrice(e.target.value)} placeholder="2000" className={input} /></label><label className="text-xs text-slate-300">Devices *<input type="number" required min="1" max="100" step="1" value={devices} onChange={e => setDevices(e.target.value)} className={input} /><span className="mt-2 block text-slate-500">Simultaneous devices allowed per voucher</span></label></div>
      </Section>
      <Section title="Availability & visibility" hint="Choose how customers can access this package">
        <fieldset><legend className="mb-3 text-xs text-slate-300">Package availability *</legend><div className="flex flex-wrap gap-3">{["ACTIVE", "HIDDEN", "DISABLED"].map(s => <label key={s} className={`cursor-pointer rounded-lg border px-4 py-2 text-xs capitalize ${availability === s ? "border-emerald-500/50 bg-emerald-500/10 text-emerald-400" : "border-slate-700 text-slate-400"}`}><input type="radio" name="availability" value={s} checked={availability === s} onChange={() => setAvailability(s)} className="mr-2 accent-emerald-500" />{s.toLowerCase()}</label>)}</div><p className="mt-3 text-xs leading-6 text-slate-500">Active: visible on the portal. Hidden: voucher-only. Disabled: unavailable for portal purchase. Existing vouchers keep their access.</p></fieldset>
        <label className="flex items-start gap-3 text-sm text-slate-300"><input type="checkbox" checked={featured} onChange={e => setFeatured(e.target.checked)} className="mt-1 accent-blue-500" /><span>Featured package<small className="mt-1 block text-xs text-slate-500">Appears first on the portal with a Popular badge.</small></span></label>
        <label className="block text-xs text-slate-300">Display order<input type="number" required min="0" step="1" max="2147483647" value={order} onChange={e => setOrder(e.target.value)} className={input} /><span className="mt-2 block text-slate-500">Breaks ties between packages with the same featured status and price.</span></label>
      </Section>
      {error && <p role="alert" className="rounded-lg border border-red-500/30 bg-red-500/10 p-4 text-sm text-red-300">{error}</p>}
      <div className="flex justify-end gap-3 pb-6"><Link href="/dashboard/packages" className="rounded-lg border border-slate-700 px-5 py-2.5 text-sm text-slate-300">Cancel</Link><button disabled={saving} className="rounded-lg bg-blue-600 px-5 py-2.5 text-sm font-semibold text-white hover:bg-blue-500 disabled:opacity-50">{saving ? "Saving…" : "Create package"}</button></div>
    </form>
  </div>;
}
