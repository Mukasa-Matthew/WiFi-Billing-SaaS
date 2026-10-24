"use client";

import React, { useEffect, useRef, useState } from "react";
import api from "@/lib/api";
import {
  Warning, CheckCircle, CircleNotch, FloppyDisk, GearSix, Globe, FileText,
  Bell, Ticket, CloudArrowDown, Trash,
} from "@phosphor-icons/react";

interface TenantSettings {
  name: string;
  email: string;
  supportPhone: string | null;
  supportPhone2: string | null;
  supportEmail: string | null;
  whatsappPhone: string | null;
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
  portalFont: string | null;
  portalLanguage: string | null;
  portalPrimaryColor: string | null;
  portalSecondaryColor: string | null;
  portalLogoSize: number | null;
  portalShowDeviceLimits: boolean;
  portalShowDeviceMac: boolean;
  portalShowPackageTime: boolean;
  portalAllowPurchases: boolean;
  portalBuyForOther: boolean;
  portalShowFeedback: boolean;
  hasLogo: boolean;
  hasBackground: boolean;
}

const TEMPLATES = [
  { key: "midnight", name: "Midnight", blurb: "Dark glass card with blue accents" },
  { key: "daylight", name: "Daylight", blurb: "Clean light theme for bright storefronts" },
  { key: "ember", name: "Ember", blurb: "Warm dark theme with gold accents" },
];

const FONTS = ["Poppins", "Work Sans", "Inter", "Roboto", "Open Sans", "Lato", "Montserrat", "Nunito", "Raleway"];
const LANGUAGES = ["English", "Swahili", "Luganda", "French"];

const field = "w-full rounded-xl border border-slate-300 bg-white px-4 py-3 text-sm text-slate-900 outline-none transition focus:border-blue-500";
const label = "mb-2 block text-sm font-semibold text-slate-900";
const hint = "mt-2 text-xs text-slate-500";
const card = "rounded-xl border border-slate-200 bg-white p-6";

const TABS = [
  { key: "general", name: "General Settings", icon: GearSix },
  { key: "hotspot", name: "Hotspot", icon: Globe },
  { key: "portal", name: "Captive Portal", icon: FileText },
  { key: "vouchers", name: "Vouchers", icon: Ticket },
  { key: "notifications", name: "Notifications", icon: Bell },
] as const;
type TabKey = (typeof TABS)[number]["key"];

function Toggle({ on, onChange, title, desc }: { on: boolean; onChange: (v: boolean) => void; title: string; desc?: string }) {
  return (
    <div className="flex items-start gap-4">
      <button type="button" role="switch" aria-checked={on} onClick={() => onChange(!on)}
        className={`relative mt-0.5 h-6 w-11 shrink-0 rounded-full transition ${on ? "bg-blue-600" : "bg-slate-300"}`}>
        <span className={`absolute top-1 h-4 w-4 rounded-full bg-white transition ${on ? "left-6" : "left-1"}`} />
      </button>
      <span>
        <span className="block text-sm font-semibold text-slate-900">{title}</span>
        {desc && <span className="mt-1 block text-xs text-slate-500">{desc}</span>}
      </span>
    </div>
  );
}

function AssetBox({ title, hintText, has, uploading, onPick, onRemove, inputRef }: {
  title: string; hintText: string; has: boolean; uploading: boolean;
  onPick: (f: File) => void; onRemove: () => void; inputRef: React.RefObject<HTMLInputElement | null>;
}) {
  return (
    <div>
      <label className={label}>{title}</label>
      <button type="button" onClick={() => inputRef.current?.click()} disabled={uploading}
        className="flex h-24 w-full flex-col items-center justify-center gap-1.5 rounded-xl border-2 border-dashed border-slate-300 bg-slate-50 text-xs text-slate-500 transition hover:border-blue-400 hover:bg-blue-50/40">
        {uploading ? <CircleNotch size={18} className="animate-spin text-blue-600" /> : <CloudArrowDown size={20} className="text-slate-400" />}
        {has ? "Replace image" : <>Drag &amp; drop or <span className="font-semibold text-blue-700">Browse</span></>}
      </button>
      <input ref={inputRef} type="file" accept="image/png,image/jpeg,image/webp,image/svg+xml" className="hidden"
        onChange={(e) => { const f = e.target.files?.[0]; if (f) onPick(f); e.target.value = ""; }} />
      <p className={hint}>{hintText}</p>
      {has && (
        <button type="button" onClick={onRemove} className="mt-2 flex items-center gap-1.5 text-xs font-medium text-red-600 hover:underline">
          <Trash size={13} />Remove
        </button>
      )}
    </div>
  );
}

export default function TenantSettingsPage() {
  const [form, setForm] = useState<TenantSettings | null>(null);
  const [tab, setTab] = useState<TabKey>("general");
  const [previewHtml, setPreviewHtml] = useState<string | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [previewKey, setPreviewKey] = useState(0);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [uploading, setUploading] = useState<string | null>(null);
  const logoRef = useRef<HTMLInputElement>(null);
  const bgRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    api.get<TenantSettings>("/api/settings")
      .then((r) => setForm(r.data))
      .catch(() => setError("Could not load settings"));
  }, []);

  const isPortalHtml = (html: string) =>
    typeof html === "string" &&
    html.includes("portal-card") &&
    !html.includes("/_next/") &&
    !/<title[^>]*>\s*WiFi-Billing/i.test(html);

  useEffect(() => {
    if (!form) return;
    setPreviewError(null);
    api.get<string>(`/api/settings/portal-preview?template=${encodeURIComponent(form.portalTemplate)}`, { responseType: "text" })
      .then((r) => {
        if (isPortalHtml(r.data)) { setPreviewHtml(r.data); }
        else { setPreviewHtml(null); setPreviewError("The preview response wasn't a captive-portal page."); }
      })
      .catch(() => { setPreviewHtml(null); setPreviewError("Could not load the portal preview."); });
  }, [form?.portalTemplate, previewKey]);

  const set = <K extends keyof TenantSettings>(key: K, value: TenantSettings[K]) =>
    setForm((f) => (f ? { ...f, [key]: value } : f));

  const save = async (e?: React.FormEvent) => {
    e?.preventDefault();
    if (!form) return;
    setSaving(true);
    setError(null);
    setSaved(false);
    try {
      const r = await api.put<TenantSettings>("/api/settings", form);
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

  const uploadAsset = async (kind: "logo" | "background", file: File) => {
    setUploading(kind);
    setError(null);
    try {
      const data = new FormData();
      data.append("file", file);
      const r = await api.post<TenantSettings>(`/api/settings/assets/${kind}`, data,
        { headers: { "Content-Type": "multipart/form-data" } });
      setForm(r.data);
      setPreviewKey((k) => k + 1);
    } catch {
      setError(`Failed to upload ${kind} image`);
    } finally {
      setUploading(null);
    }
  };

  const removeAsset = async (kind: "logo" | "background") => {
    try {
      const r = await api.delete<TenantSettings>(`/api/settings/assets/${kind}`);
      setForm(r.data);
      setPreviewKey((k) => k + 1);
    } catch {
      setError(`Failed to remove ${kind} image`);
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
    <div className="mx-auto max-w-5xl space-y-6">
      <div>
        <h1 className="text-xl font-bold tracking-tight text-slate-900">Settings</h1>
        <p className="mt-1 text-sm text-slate-500">
          Branding, portal behavior, voucher defaults, and notifications for your hotspot business.
        </p>
      </div>

      {/* Tab bar */}
      <nav className="flex flex-wrap gap-1 border-b border-slate-200 pb-px" aria-label="Settings sections">
        {TABS.map(({ key, name, icon: Icon }) => (
          <button key={key} type="button" onClick={() => setTab(key)}
            className={`flex items-center gap-2 rounded-t-lg border-b-2 px-4 py-2.5 text-sm font-medium transition ${
              tab === key ? "border-blue-600 text-blue-700" : "border-transparent text-slate-500 hover:text-slate-800"
            }`}>
            <Icon size={16} />{name}
          </button>
        ))}
      </nav>

      <form onSubmit={save} className="space-y-6">
        {tab === "general" && (
          <section className={card}>
            <h2 className="text-sm font-semibold text-slate-900">Your ISP Branding</h2>
            <p className="mb-5 mt-1 text-xs text-slate-500">Configure your WiFi business branding and customer support details for {form.name}.</p>
            <div className="grid gap-5 sm:grid-cols-2">
              <AssetBox title="Your Logo" hintText="Upload a JPG, PNG, or SVG logo. Appears on the login page."
                has={form.hasLogo} uploading={uploading === "logo"}
                onPick={(f) => uploadAsset("logo", f)} onRemove={() => removeAsset("logo")} inputRef={logoRef} />
              <div>
                <label className={label}>Your ISP / WiFi Business Name*</label>
                <input required maxLength={120} value={form.name} onChange={(e) => set("name", e.target.value)} className={field} />
                <p className={hint}>This name will appear on vouchers, SMS messages, and the captive portal.</p>
              </div>
              <div>
                <label className={label}>Brand Color</label>
                <div className="flex items-center gap-3">
                  <input type="color" value={form.brandColor} onChange={(e) => set("brandColor", e.target.value)} className="h-11 w-14 cursor-pointer rounded-lg border border-slate-300 bg-[#F5F7FA]" />
                  <input pattern="#[0-9A-Fa-f]{6}" value={form.brandColor} onChange={(e) => set("brandColor", e.target.value)} className={`${field} font-mono`} />
                </div>
                <p className={hint}>Your brand color for the dashboard.</p>
              </div>
              <div>
                <label className={label}>Customer Support Number</label>
                <input maxLength={60} value={form.supportPhone ?? ""} onChange={(e) => set("supportPhone", e.target.value || null)} className={field} placeholder="+2567XXXXXXXX" />
                <p className={hint}>Primary number shown on the captive portal.</p>
              </div>
              <div>
                <label className={label}>Second Support Number (Optional)</label>
                <input maxLength={60} value={form.supportPhone2 ?? ""} onChange={(e) => set("supportPhone2", e.target.value || null)} className={field} placeholder="+2567XXXXXXXX" />
                <p className={hint}>Additional number shown on the captive portal.</p>
              </div>
              <div>
                <label className={label}>WhatsApp Support Number</label>
                <input maxLength={60} value={form.whatsappPhone ?? ""} onChange={(e) => set("whatsappPhone", e.target.value || null)} className={field} placeholder="+2567XXXXXXXX" />
                <p className={hint}>Customers can tap this to start a WhatsApp chat.</p>
              </div>
              <div>
                <label className={label}>Your Customer Support Email</label>
                <input type="email" maxLength={120} value={form.supportEmail ?? ""} onChange={(e) => set("supportEmail", e.target.value || null)} className={field} placeholder="support@yourbusiness.com" />
                <p className={hint}>Your customers can email this address for support.</p>
              </div>
              <div>
                <label className={label}>Account email</label>
                <input value={form.email} disabled className={`${field} opacity-50`} />
                <p className={hint}>Login email — contact support to change.</p>
              </div>
            </div>
          </section>
        )}

        {tab === "hotspot" && (
          <section className={card}>
            <h2 className="mb-5 text-sm font-semibold text-slate-900">Hotspot</h2>
            <div className="grid gap-5 sm:grid-cols-2">
              <div>
                <label className={label}>Redirect URL after login</label>
                <input type="url" maxLength={255} value={form.redirectUrl ?? ""} onChange={(e) => set("redirectUrl", e.target.value || null)} className={field} placeholder="https://yourbusiness.com (optional)" />
                <p className={hint}>Where customers land after connecting. Empty = their original page.</p>
              </div>
              <div>
                <label className={label}>Tethering protection</label>
                <select value={form.tetheringProtection} onChange={(e) => set("tetheringProtection", e.target.value)} className={field}>
                  <option value="OFF">Off — allow customers to share their connection</option>
                  <option value="LOG">Detect only — log tethered devices, don't block</option>
                  <option value="BLOCK">Block — stop devices sharing WiFi via mobile hotspot</option>
                </select>
                <p className={hint}>Applies to all your routers — pushed automatically within a minute.</p>
              </div>
            </div>
          </section>
        )}

        {tab === "portal" && (
          <>
            <section className={card}>
              <h2 className="text-sm font-semibold text-slate-900">Portal Design</h2>
              <p className="mb-5 mt-1 text-xs text-slate-500">Adjust the branding and appearance of your customer login page. Scroll down to choose a captive portal template.</p>
              <div className="grid gap-5 sm:grid-cols-2">
                <div>
                  <label className={label}>Primary colour override</label>
                  <div className="flex items-center gap-3">
                    <input type="color" value={form.portalPrimaryColor || "#3B82F6"} onChange={(e) => set("portalPrimaryColor", e.target.value)} className="h-11 w-14 cursor-pointer rounded-lg border border-slate-300 bg-[#F5F7FA]" />
                    <input pattern="#[0-9A-Fa-f]{6}|" value={form.portalPrimaryColor ?? ""} onChange={(e) => set("portalPrimaryColor", e.target.value || null)} className={`${field} font-mono`} placeholder="Template default" />
                  </div>
                  <p className={hint}>Leave empty to use the selected template colour.</p>
                </div>
                <div>
                  <label className={label}>Secondary colour override</label>
                  <div className="flex items-center gap-3">
                    <input type="color" value={form.portalSecondaryColor || "#BFD9F2"} onChange={(e) => set("portalSecondaryColor", e.target.value)} className="h-11 w-14 cursor-pointer rounded-lg border border-slate-300 bg-[#F5F7FA]" />
                    <input pattern="#[0-9A-Fa-f]{6}|" value={form.portalSecondaryColor ?? ""} onChange={(e) => set("portalSecondaryColor", e.target.value || null)} className={`${field} font-mono`} placeholder="Template default" />
                  </div>
                  <p className={hint}>Leave empty to use the selected template colour.</p>
                </div>
                <div>
                  <label className={label}>Logo size</label>
                  <div className="relative">
                    <input type="number" min={40} max={120} value={form.portalLogoSize ?? ""} onChange={(e) => set("portalLogoSize", e.target.value ? Number(e.target.value) : null)} className={`${field} pr-10`} placeholder="Default" />
                    <span className="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-slate-400">px</span>
                  </div>
                  <p className={hint}>Leave empty to use the template default. Range 40–120px.</p>
                </div>
                <div>
                  <label className={label}>Portal font</label>
                  <select value={form.portalFont ?? "Poppins"} onChange={(e) => set("portalFont", e.target.value)} className={field}>
                    {FONTS.map((f) => <option key={f} value={f}>{f}</option>)}
                  </select>
                </div>
                <div>
                  <label className={label}>Portal language</label>
                  <select value={form.portalLanguage ?? "English"} onChange={(e) => set("portalLanguage", e.target.value)} className={field}>
                    {LANGUAGES.map((l) => <option key={l} value={l}>{l}</option>)}
                  </select>
                </div>
                <div>
                  <label className={label}>Portal background</label>
                  <AssetBox title="" hintText="Optional JPG, PNG or WebP, maximum 500 KB. Keep it light for fast portal loading."
                    has={form.hasBackground} uploading={uploading === "background"}
                    onPick={(f) => uploadAsset("background", f)} onRemove={() => removeAsset("background")} inputRef={bgRef} />
                </div>
              </div>
            </section>

            <section className={card}>
              <h2 className="text-sm font-semibold text-slate-900">Customer Experience</h2>
              <p className="mb-5 mt-1 text-xs text-slate-500">Control what customers can see and do on the captive portal.</p>
              <div className="grid gap-5 sm:grid-cols-2">
                <Toggle on={form.portalShowPackages} onChange={(v) => set("portalShowPackages", v)} title="Show packages" desc="When off, the portal only accepts voucher codes." />
                <Toggle on={form.portalAllowPurchases} onChange={(v) => set("portalAllowPurchases", v)} title="Allow online purchases" desc="Show the buy options on packages." />
                <Toggle on={form.portalShowDeviceLimits} onChange={(v) => set("portalShowDeviceLimits", v)} title="Show package device limits" />
                <Toggle on={form.portalShowPackageTime} onChange={(v) => set("portalShowPackageTime", v)} title="Show package time" desc='Prints how long each package lasts under its name, e.g. "1 Hour".' />
                <Toggle on={form.portalShowDeviceMac} onChange={(v) => set("portalShowDeviceMac", v)} title="Show device MAC" />
                <Toggle on={form.portalBuyForOther} onChange={(v) => set("portalBuyForOther", v)} title="Buy for TV or another device" />
                <Toggle on={form.portalShowFeedback} onChange={(v) => set("portalShowFeedback", v)} title="Show feedback button" />
              </div>
            </section>

            <section className={card}>
              <h2 className="mb-1 text-sm font-semibold text-slate-900">Portal template</h2>
              <p className="mb-5 text-xs text-slate-500">Pick the design customers see on your captive portal. The preview shows your branding.</p>
              <div className="grid gap-6 lg:grid-cols-2">
                <div className="space-y-3">
                  {TEMPLATES.map((t) => (
                    <button key={t.key} type="button" onClick={() => set("portalTemplate", t.key)}
                      className={`flex w-full items-center justify-between rounded-xl border p-4 text-left transition ${
                        form.portalTemplate === t.key ? "border-blue-500 bg-blue-50" : "border-slate-300 bg-[#F5F7FA]/50 hover:border-slate-500"
                      }`}>
                      <span>
                        <span className="block text-sm font-semibold text-slate-900">{t.name}</span>
                        <span className="mt-0.5 block text-xs text-slate-500">{t.blurb}</span>
                      </span>
                      <span className={`flex h-5 w-5 items-center justify-center rounded-full border ${form.portalTemplate === t.key ? "border-blue-600 bg-blue-600" : "border-slate-300"}`}>
                        {form.portalTemplate === t.key && <CheckCircle size={12} className="text-white" />}
                      </span>
                    </button>
                  ))}
                </div>
                <div>
                  <div className="overflow-hidden rounded-xl border border-slate-300 bg-[#F5F7FA]">
                    <div className="border-b border-slate-200 px-3 py-2 text-[10px] text-slate-500">Live preview — {form.portalTemplate}</div>
                    {previewError ? (
                      <div className="flex h-[420px] flex-col items-center justify-center gap-2 px-6 text-center">
                        <Warning size={20} className="text-amber-600" />
                        <p className="text-xs font-medium text-slate-700">{previewError}</p>
                        <button type="button" onClick={() => setPreviewKey(k => k + 1)} className="mt-1 text-xs font-medium text-blue-700 hover:underline">Retry preview</button>
                      </div>
                    ) : previewHtml ? (
                      <iframe key={form.portalTemplate} srcDoc={previewHtml} title="Captive portal preview" className="h-[420px] w-full bg-white" />
                    ) : (
                      <div className="flex h-[420px] items-center justify-center text-xs text-slate-500">
                        <CircleNotch size={16} className="mr-2 animate-spin" />Rendering preview…
                      </div>
                    )}
                  </div>
                </div>
              </div>
            </section>
          </>
        )}

        {tab === "vouchers" && (
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
        )}

        {tab === "notifications" && (
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
        )}

        {error && <div className="rounded-xl border border-red-200 bg-red-50 p-3 text-xs text-red-700">{error}</div>}

        <div className="flex items-center gap-4">
          <button disabled={saving} className="flex items-center gap-2 rounded-lg bg-blue-600 px-6 py-2.5 text-sm font-semibold text-white hover:bg-blue-500 disabled:opacity-50">
            {saving ? <CircleNotch size={16} className="animate-spin" /> : <FloppyDisk size={16} />}
            {saving ? "Saving…" : "Save settings"}
          </button>
          {saved && <span className="flex items-center gap-1.5 text-sm text-emerald-700"><CheckCircle size={15} />Saved</span>}
        </div>
      </form>
    </div>
  );
}
