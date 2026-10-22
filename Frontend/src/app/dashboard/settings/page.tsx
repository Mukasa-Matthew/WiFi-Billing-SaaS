"use client";

import React, { useEffect, useState } from "react";
import api from "@/lib/api";
import { CheckCircle2, LoaderCircle, Save } from "lucide-react";

interface Settings {
  name: string;
  email: string;
  supportPhone: string | null;
  supportEmail: string | null;
  brandColor: string;
  redirectUrl: string | null;
  voucherPrefix: string | null;
  voucherCodeLength: number;
  voucherCodeFormat: string;
  portalShowPackages: boolean;
  notifyEmails: string | null;
  notifyPhones: string | null;
  portalTemplate: string;
  tetheringProtection: string;
}

const TEMPLATES = [
  { key: "midnight", name: "Midnight", blurb: "Dark glass card with blue accents" },
  { key: "daylight", name: "Daylight", blurb: "Clean light theme for bright storefronts" },
  { key: "ember", name: "Ember", blurb: "Warm dark theme with gold accents" },
];

const field = "w-full rounded-xl border border-slate-300 bg-white px-4 py-3 text-sm text-slate-900 outline-none transition focus:border-blue-500";
const label = "mb-2 block text-sm font-semibold text-slate-900";
const hint = "mt-2 text-xs text-slate-500";
const card = "rounded-xl border border-slate-200 bg-white p-6";

export default function TenantSettingsPage() {
  const [form, setForm] = useState<Settings | null>(null);
  const [previewHtml, setPreviewHtml] = useState<string | null>(null);
  const [previewKey, setPreviewKey] = useState(0);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.get<Settings>("/api/settings")
      .then((r) => setForm(r.data))
      .catch(() => setError("Could not load settings"));
  }, []);

  // Fetch preview through the authenticated API client — iframes can't
  // attach the JWT header, and the backend correctly denies framing anyway.
  useEffect(() => {
    if (!form) return;
    api.get<string>(`/api/settings/portal-preview?template=${encodeURIComponent(form.portalTemplate)}`, { responseType: "text" })
      .then((r) => setPreviewHtml(r.data))
      .catch(() => setPreviewHtml(null));
  }, [form?.portalTemplate, previewKey]);

  const set = <K extends keyof Settings>(key: K, value: Settings[K]) =>
    setForm((f) => (f ? { ...f, [key]: value } : f));

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!form) return;
    setSaving(true);
    setError(null);
    setSaved(false);
    try {
      const r = await api.put<Settings>("/api/settings", form);
      setForm(r.data);
      setPreviewKey((k) => k + 1);
      setSaved(true);
      window.setTimeout(() => setSaved(false), 3000);
    } catch {
      setError("Failed to save settings");
    } finally {
      setSaving(false);
    }
  };

  if (!form) {
    return (
      <div className="mx-auto max-w-3xl p-8 text-sm text-slate-500">
        {error ?? "Loading settings…"}
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">Settings</h1>
        <p className="mt-1 text-sm text-slate-500">
          Branding, portal behavior, voucher defaults, and notifications for your hotspot business.
        </p>
      </div>

      <form onSubmit={save} className="space-y-6">
        {/* Business profile */}
        <section className={card}>
          <h2 className="mb-5 text-sm font-semibold text-slate-900">Business profile</h2>
          <div className="grid gap-5 sm:grid-cols-2">
            <div>
              <label className={label}>Business name</label>
              <input required maxLength={120} value={form.name} onChange={(e) => set("name", e.target.value)} className={field} />
              <p className={hint}>Shown on the captive portal and dashboard.</p>
            </div>
            <div>
              <label className={label}>Account email</label>
              <input value={form.email} disabled className={`${field} opacity-50`} />
              <p className={hint}>Login email — contact support to change.</p>
            </div>
            <div>
              <label className={label}>Support phone</label>
              <input maxLength={60} value={form.supportPhone ?? ""} onChange={(e) => set("supportPhone", e.target.value || null)} className={field} placeholder="+2567XXXXXXXX" />
              <p className={hint}>Shown on the captive portal for customers needing help.</p>
            </div>
            <div>
              <label className={label}>Support email</label>
              <input type="email" maxLength={120} value={form.supportEmail ?? ""} onChange={(e) => set("supportEmail", e.target.value || null)} className={field} placeholder="support@yourbusiness.com" />
            </div>
            <div>
              <label className={label}>Brand color</label>
              <div className="flex items-center gap-3">
                <input type="color" value={form.brandColor} onChange={(e) => set("brandColor", e.target.value)} className="h-11 w-14 cursor-pointer rounded-lg border border-slate-300 bg-[#F5F7FA]" />
                <input pattern="#[0-9A-Fa-f]{6}" value={form.brandColor} onChange={(e) => set("brandColor", e.target.value)} className={`${field} font-mono`} />
              </div>
              <p className={hint}>Accent color on your captive portal.</p>
            </div>
          </div>
        </section>

        {/* Captive portal */}
        <section className={card}>
          <h2 className="mb-5 text-sm font-semibold text-slate-900">Captive portal</h2>
          <div className="grid gap-5 sm:grid-cols-2">
            <div>
              <label className={label}>Redirect URL after login</label>
              <input type="url" maxLength={255} value={form.redirectUrl ?? ""} onChange={(e) => set("redirectUrl", e.target.value || null)} className={field} placeholder="https://yourbusiness.com (optional)" />
              <p className={hint}>Where customers land after connecting. Empty = their original page.</p>
            </div>
            <div className="flex items-start gap-4 pt-7">
              <button
                type="button"
                role="switch"
                aria-checked={form.portalShowPackages}
                onClick={() => set("portalShowPackages", !form.portalShowPackages)}
                className={`relative mt-0.5 h-6 w-11 shrink-0 rounded-full transition ${form.portalShowPackages ? "bg-blue-600" : "bg-slate-300"}`}
              >
                <span className={`absolute top-1 h-4 w-4 rounded-full bg-white transition ${form.portalShowPackages ? "left-6" : "left-1"}`} />
              </button>
              <span>
                <span className="block text-sm font-semibold text-slate-900">Show packages for purchase</span>
                <span className="mt-1 block text-xs text-slate-500">When off, the portal only accepts voucher codes — no Mobile Money purchases.</span>
              </span>
            </div>
          </div>
        </section>

        {/* Portal template gallery + live preview */}
        <section className={card}>
          <h2 className="mb-1 text-sm font-semibold text-slate-900">Portal template</h2>
          <p className="mb-5 text-xs text-slate-500">Pick the design customers see on your captive portal. The preview below shows exactly what your portal will look like with your branding.</p>
          <div className="grid gap-6 lg:grid-cols-2">
            <div className="space-y-3">
              {TEMPLATES.map((t) => (
                <button
                  key={t.key}
                  type="button"
                  onClick={() => set("portalTemplate", t.key)}
                  className={`flex w-full items-center justify-between rounded-xl border p-4 text-left transition ${
                    form.portalTemplate === t.key
                      ? "border-blue-500 bg-blue-50"
                      : "border-slate-300 bg-[#F5F7FA]/50 hover:border-slate-500"
                  }`}
                >
                  <span>
                    <span className="block text-sm font-semibold text-slate-900">{t.name}</span>
                    <span className="mt-0.5 block text-xs text-slate-500">{t.blurb}</span>
                  </span>
                  <span className={`flex h-5 w-5 items-center justify-center rounded-full border ${form.portalTemplate === t.key ? "border-blue-600 bg-blue-600" : "border-slate-300"}`}>
                    {form.portalTemplate === t.key && <CheckCircle2 size={12} className="text-white" />}
                  </span>
                </button>
              ))}
            </div>
            <div>
              <div className="overflow-hidden rounded-xl border border-slate-300 bg-[#F5F7FA]">
                <div className="border-b border-slate-200 px-3 py-2 text-[10px] text-slate-500">Live preview — {form.portalTemplate}</div>
                {previewHtml ? (
                  <iframe
                    key={form.portalTemplate}
                    srcDoc={previewHtml}
                    title="Captive portal preview"
                    className="h-[420px] w-full bg-white"
                  />
                ) : (
                  <div className="flex h-[420px] items-center justify-center text-xs text-slate-500">
                    <LoaderCircle size={16} className="mr-2 animate-spin" />Rendering preview…
                  </div>
                )}
              </div>
            </div>
          </div>
        </section>

        {/* Hotspot security */}
        <section className={card}>
          <h2 className="mb-5 text-sm font-semibold text-slate-900">Hotspot security</h2>
          <div>
            <label className={label}>Tethering protection</label>
            <select value={form.tetheringProtection} onChange={(e) => set("tetheringProtection", e.target.value)} className={field}>
              <option value="OFF">Off — allow customers to share their connection</option>
              <option value="LOG">Detect only — log tethered devices, don't block</option>
              <option value="BLOCK">Block — stop devices sharing WiFi via mobile hotspot</option>
            </select>
            <p className={hint}>
              Detects phones/laptops re-sharing your WiFi through their hotspot using TTL analysis. Applies to all your routers — pushed automatically to provisioned devices within a minute.
            </p>
          </div>
        </section>

        {/* Voucher defaults */}
        <section className={card}>
          <h2 className="mb-5 text-sm font-semibold text-slate-900">Voucher defaults</h2>
          <div className="grid gap-5 sm:grid-cols-3">
            <div>
              <label className={label}>Code prefix</label>
              <input maxLength={4} value={form.voucherPrefix ?? ""} onChange={(e) => set("voucherPrefix", e.target.value.toUpperCase() || null)} className={`${field} font-mono`} placeholder="e.g. T" />
              <p className={hint}>Optional, up to 4 characters.</p>
            </div>
            <div>
              <label className={label}>Code length</label>
              <select value={form.voucherCodeLength} onChange={(e) => set("voucherCodeLength", Number(e.target.value))} className={field}>
                {[5, 6, 7, 8, 9, 10, 11, 12].map((n) => <option key={n} value={n}>{n} characters</option>)}
              </select>
            </div>
            <div>
              <label className={label}>Code format</label>
              <select value={form.voucherCodeFormat} onChange={(e) => set("voucherCodeFormat", e.target.value)} className={field}>
                <option value="ALPHANUMERIC">Letters and numbers</option>
                <option value="NUMERIC">Numbers only</option>
                <option value="LETTERS">Letters only</option>
              </select>
            </div>
          </div>
          <p className="mt-3 text-xs text-slate-500">These pre-fill the voucher generation form — you can still override per batch.</p>
        </section>

        {/* Notifications */}
        <section className={card}>
          <h2 className="mb-5 text-sm font-semibold text-slate-900">Notifications</h2>
          <div className="grid gap-5 sm:grid-cols-2">
            <div>
              <label className={label}>Alert emails</label>
              <input maxLength={500} value={form.notifyEmails ?? ""} onChange={(e) => set("notifyEmails", e.target.value || null)} className={field} placeholder="ops@yourbusiness.com, you@gmail.com" />
              <p className={hint}>Comma-separated — router offline alerts and system notifications.</p>
            </div>
            <div>
              <label className={label}>Alert phone numbers</label>
              <input maxLength={255} value={form.notifyPhones ?? ""} onChange={(e) => set("notifyPhones", e.target.value || null)} className={field} placeholder="+2567XXXXXXXX" />
              <p className={hint}>For SMS alerts once messaging is enabled.</p>
            </div>
          </div>
        </section>

        {error && <div className="rounded-xl border border-red-200 bg-red-50 p-3 text-xs text-red-300">{error}</div>}

        <div className="flex items-center gap-4">
          <button disabled={saving} className="flex items-center gap-2 rounded-lg bg-blue-600 px-6 py-2.5 text-sm font-semibold text-white hover:bg-blue-500 disabled:opacity-50">
            {saving ? <LoaderCircle size={16} className="animate-spin" /> : <Save size={16} />}
            {saving ? "Saving…" : "Save settings"}
          </button>
          {saved && <span className="flex items-center gap-1.5 text-sm text-emerald-700"><CheckCircle2 size={15} />Saved</span>}
        </div>
      </form>
    </div>
  );
}
