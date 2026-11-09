"use client";

import React, { useCallback, useEffect, useRef, useState } from "react";
import axios from "axios";
import api from "@/lib/api";
import {
  CheckCircle, CircleNotch, FloppyDisk, GearSix, Globe, FileText,
  Bell, Ticket, CloudArrowDown, Trash, CreditCard, ArrowSquareOut,
} from "@/components/icons";
import PaymentGatewaySettings from "@/components/PaymentGatewaySettings";
import { panel, message } from "@/components/BillingUI";
import { FormSkeleton, BusyLabel, useSingleFlight } from "@/components/LoadingUI";

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
  notifyRouterOffline: boolean;
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
  { key: "midnight", name: "Midnight", blurb: "White card with a navy header — the classic default" },
  { key: "daylight", name: "Daylight", blurb: "Clean light theme for bright storefronts" },
  { key: "campus", name: "Campus", blurb: "White card with a green header — great for schools" },
  { key: "royal", name: "Royal", blurb: "White card with a purple header" },
  { key: "sunset", name: "Sunset", blurb: "White card with an orange header" },
  { key: "ember", name: "Ember", blurb: "Warm dark theme with gold accents" },
  { key: "forest", name: "Forest", blurb: "Sleek dark theme with green accents" },
];

const FONTS = ["Poppins", "Work Sans", "Inter", "Roboto", "Open Sans", "Lato", "Montserrat", "Nunito", "Raleway"];
const LANGUAGES = ["English", "Swahili", "Luganda", "French"];

function apiErrorMessage(error: unknown, fallback: string): string {
  if (!axios.isAxiosError(error)) return fallback;
  const data: unknown = error.response?.data;
  if (typeof data === "string") return data;
  if (data && typeof data === "object" && "message" in data && typeof data.message === "string") return data.message;
  return fallback;
}

const field = "w-full rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] px-4 py-3 text-sm text-[var(--fg)] outline-none transition focus:border-blue-500";
const label = "mb-2 block text-sm font-semibold text-[var(--fg)]";
const hint = "mt-2 text-xs text-[var(--fg-muted)]";
const card = "rounded-xl border border-[var(--border)] bg-[var(--surface)] p-6";

const TABS = [
  { key: "general", name: "General Settings", icon: GearSix },
  { key: "hotspot", name: "Hotspot", icon: Globe },
  { key: "portal", name: "Captive Portal", icon: FileText },
  { key: "vouchers", name: "Vouchers", icon: Ticket },
  { key: "notifications", name: "Notifications", icon: Bell },
  { key: "payments", name: "Payment Gateway", icon: CreditCard },
] as const;
type TabKey = (typeof TABS)[number]["key"];

function Toggle({ on, onChange, title, desc }: { on: boolean; onChange: (v: boolean) => void; title: string; desc?: string }) {
  return (
    <div className="flex items-start gap-4">
      <button type="button" role="switch" aria-checked={on} onClick={() => onChange(!on)}
        className={`relative mt-0.5 h-6 w-11 shrink-0 rounded-full transition ${on ? "bg-blue-600" : "bg-slate-300"}`}>
        <span className={`absolute top-1 h-4 w-4 rounded-full bg-[var(--surface)] transition ${on ? "left-6" : "left-1"}`} />
      </button>
      <span>
        <span className="block text-sm font-semibold text-[var(--fg)]">{title}</span>
        {desc && <span className="mt-1 block text-xs text-[var(--fg-muted)]">{desc}</span>}
      </span>
    </div>
  );
}

/** Largest side, in pixels, an uploaded image is scaled down to before upload. */
const MAX_IMAGE_SIDE = { logo: 512, background: 1600 } as const;
/** The server accepts up to 1,000 KB; stay well under it. */
const TARGET_BYTES = 900_000;

/**
 * Phone photos are often several MB, more than the server accepts and far more than a
 * captive portal should download. Scale the image down and re-encode it in the browser;
 * logos keep their transparency. SVGs and files the browser can't decode are sent as is,
 * and the server explains if it can't use them.
 */
async function shrinkImage(file: File, kind: "logo" | "background"): Promise<File> {
  if (file.type === "image/svg+xml") return file;
  let bitmap: ImageBitmap;
  try { bitmap = await createImageBitmap(file); } catch { return file; }
  const scale = Math.min(1, MAX_IMAGE_SIDE[kind] / Math.max(bitmap.width, bitmap.height));
  if (scale === 1 && file.size <= TARGET_BYTES) { bitmap.close(); return file; }
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(bitmap.width * scale));
  canvas.height = Math.max(1, Math.round(bitmap.height * scale));
  canvas.getContext("2d")?.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  const encode = (type: string, quality?: number) =>
    new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, type, quality));
  let blob: Blob | null = null;
  for (const quality of [0.85, 0.75, 0.6, 0.45]) {
    blob = await encode("image/webp", quality);
    // Browsers without WebP encoding return PNG: fall back to a format that keeps the image.
    if (blob && blob.type !== "image/webp") blob = await encode(kind === "logo" ? "image/png" : "image/jpeg", quality);
    if (blob && blob.size <= TARGET_BYTES) break;
  }
  if (!blob) return file;
  const ext = blob.type === "image/webp" ? "webp" : blob.type === "image/png" ? "png" : "jpg";
  return new File([blob], `${kind}.${ext}`, { type: blob.type });
}

function AssetBox({ title, hintText, has, uploading, onPick, onRemove, inputRef, kind, previewUrl }: {
  title: string; hintText: string; has: boolean; uploading: boolean;
  onPick: (f: File) => void; onRemove: () => void; inputRef: React.RefObject<HTMLInputElement | null>;
  kind: "logo" | "background"; previewUrl: string | null;
}) {
  return (
    <div>
      <label className={label}>{title}</label>
      {previewUrl && (
        // A logo sits on a checkerboard so transparency is visible; a background shows as it fills the portal.
        <div className={`relative mb-2 overflow-hidden rounded-xl border border-[var(--border-strong)] ${kind === "logo"
          ? "flex h-28 items-center justify-center bg-[length:16px_16px] bg-[conic-gradient(#e2e8f0_25%,#fff_0_50%,#e2e8f0_0_75%,#fff_0)] p-3"
          : "aspect-video bg-[var(--subtle)]"}`}>
          {/* eslint-disable-next-line @next/next/no-img-element -- a local blob URL, not an optimisable asset */}
          <img src={previewUrl} alt={kind === "logo" ? "Logo preview" : "Background preview"}
            className={kind === "logo" ? "max-h-full max-w-full object-contain" : "h-full w-full object-cover"} />
          {uploading && (
            <div className="absolute inset-0 flex items-center justify-center bg-white/60">
              <CircleNotch size={20} className="animate-spin text-blue-600" />
            </div>
          )}
        </div>
      )}
      <button type="button" onClick={() => inputRef.current?.click()} disabled={uploading}
        className="flex h-24 w-full flex-col items-center justify-center gap-1.5 rounded-xl border-2 border-dashed border-[var(--border-strong)] bg-[var(--subtle)] text-xs text-[var(--fg-muted)] transition hover:border-blue-400 hover:bg-blue-50/40">
        {uploading ? <CircleNotch size={18} className="animate-spin text-blue-600" /> : <CloudArrowDown size={20} className="text-[var(--fg-faint)]" />}
        {has ? "Replace image" : <>Drag &amp; drop or <span className="font-semibold text-[var(--accent-text)]">Browse</span></>}
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

/** Inline account-email change: code is sent to the CURRENT email to authorise. */
function EmailChange({ currentEmail, onChanged }: { currentEmail: string; onChanged: (e: string) => void }) {
  const [open, setOpen] = useState(false);
  const [newEmail, setNewEmail] = useState("");
  const [otp, setOtp] = useState("");
  const [sent, setSent] = useState(false);
  const [devCode, setDevCode] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ kind: "ok" | "err"; text: string } | null>(null);

  const singleFlight = useSingleFlight();
  const sendCode = singleFlight(async () => {
    setBusy(true); setMsg(null);
    try {
      const r = await api.post<{ devCode?: string }>("/api/settings/email/otp", { newEmail });
      setSent(true);
      setDevCode(r.data.devCode ?? null);
      setMsg({ kind: "ok", text: `Code sent to ${currentEmail} — enter it below to confirm.` });
    } catch (e: unknown) {
      setMsg({ kind: "err", text: apiErrorMessage(e, "Could not send code") });
    } finally {
      setBusy(false);
    }
  });

  const confirm = singleFlight(async () => {
    setBusy(true); setMsg(null);
    try {
      const r = await api.post<{ email: string }>("/api/settings/email", { newEmail, otp });
      onChanged(r.data.email);
      setOpen(false); setNewEmail(""); setOtp(""); setSent(false); setDevCode(null);
      setMsg({ kind: "ok", text: "Account email updated. Use it to sign in from now on." });
    } catch (e: unknown) {
      setMsg({ kind: "err", text: apiErrorMessage(e, "Email change failed") });
    } finally {
      setBusy(false);
    }
  });

  return (
    <div className="sm:col-span-2">
      <label className={label}>Account email</label>
      <div className="flex items-center gap-3">
        <input value={currentEmail} disabled className={`${field} opacity-60 max-w-sm`} />
        {!open && (
          <button type="button" onClick={() => { setOpen(true); setMsg(null); }}
            className="shrink-0 rounded-lg border border-[var(--border-strong)] px-3 py-2 text-xs font-semibold text-[var(--fg-body)] hover:text-[var(--fg)] hover:bg-[var(--fill)] transition">
            Change
          </button>
        )}
      </div>
      <p className={hint}>Sign-in email — also where withdrawal and password-reset codes are sent.</p>

      {msg && !open && <p className={`mt-2 text-xs font-medium ${msg.kind === "ok" ? "text-emerald-600" : "text-red-500"}`}>{msg.text}</p>}

      {open && (
        <div className="mt-3 max-w-md space-y-3 rounded-xl border border-[var(--border)] bg-[var(--subtle)] p-4">
          <div>
            <label className="mb-1 block text-xs font-semibold text-[var(--fg)]">New email address</label>
            <input type="email" value={newEmail} onChange={(e) => setNewEmail(e.target.value)}
              placeholder="new@email.com" className={field} />
          </div>
          {sent && (
            <div>
              <label className="mb-1 block text-xs font-semibold text-[var(--fg)]">Verification code</label>
              <input inputMode="numeric" maxLength={6} value={otp} onChange={(e) => setOtp(e.target.value.replace(/\D/g, ""))}
                placeholder="6-digit code" className={`${field} font-mono tracking-widest`} />
              {devCode && <p className="mt-1 text-xs text-amber-500">Dev mode — code: {devCode}</p>}
            </div>
          )}
          {msg && <p className={`text-xs font-medium ${msg.kind === "ok" ? "text-emerald-600" : "text-red-500"}`}>{msg.text}</p>}
          <div className="flex items-center gap-2">
            {!sent ? (
              <button type="button" disabled={busy || !newEmail} onClick={sendCode}
                className="rounded-lg bg-blue-700 px-3.5 py-2 text-xs font-semibold text-white hover:bg-blue-600 disabled:opacity-50">
                <BusyLabel busy={busy} label="Sending…">{`Send code to ${currentEmail}`}</BusyLabel>
              </button>
            ) : (
              <>
                <button type="button" disabled={busy || otp.length !== 6} onClick={confirm}
                  className="rounded-lg bg-blue-700 px-3.5 py-2 text-xs font-semibold text-white hover:bg-blue-600 disabled:opacity-50">
                  <BusyLabel busy={busy} label="Confirming…">Confirm change</BusyLabel>
                </button>
                <button type="button" disabled={busy} onClick={sendCode}
                  className="rounded-lg px-3 py-2 text-xs font-semibold text-[var(--accent-text)] hover:underline disabled:opacity-50">
                  Resend
                </button>
              </>
            )}
            <button type="button" disabled={busy} onClick={() => { setOpen(false); setSent(false); setOtp(""); setMsg(null); }}
              className="rounded-lg px-3 py-2 text-xs font-semibold text-[var(--fg-muted)] hover:text-[var(--fg)]">
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

export default function TenantSettingsPage() {
  const [form, setForm] = useState<TenantSettings | null>(null);
  const [tab, setTab] = useState<TabKey>("general");
  const [previewSrc, setPreviewSrc] = useState<string | null>(null);
  const [previewLoading, setPreviewLoading] = useState(true);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [previewMac, setPreviewMac] = useState("");
  const [previewKey, setPreviewKey] = useState(0);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [uploading, setUploading] = useState<string | null>(null);
  const logoRef = useRef<HTMLInputElement>(null);
  const bgRef = useRef<HTMLInputElement>(null);
  // Local object URLs of the logo/background shown in their upload boxes.
  const [imagePreview, setImagePreview] = useState<{ logo: string | null; background: string | null }>({ logo: null, background: null });
  const previewUrls = useRef<{ logo: string | null; background: string | null }>({ logo: null, background: null });

  /** Shows {@code url} for {@code kind}, releasing the previous object URL unless told to keep it. */
  const showImage = useCallback((kind: "logo" | "background", url: string | null, keepPrevious = false) => {
    const previous = previewUrls.current[kind];
    if (previous && previous !== url && !keepPrevious) URL.revokeObjectURL(previous);
    previewUrls.current[kind] = url;
    setImagePreview((p) => ({ ...p, [kind]: url }));
  }, []);

  /** Loads the saved image through the authenticated API (an <img> can't send the bearer token). */
  const loadImage = useCallback((kind: "logo" | "background") =>
    api.get<Blob>(`/api/settings/assets/${kind}`, { responseType: "blob" })
      .then((r) => showImage(kind, URL.createObjectURL(r.data)))
      .catch(() => showImage(kind, null)), [showImage]);

  useEffect(() => () => {
    Object.values(previewUrls.current).forEach((u) => { if (u) URL.revokeObjectURL(u); });
  }, []);
  const normalizedPreviewMac = previewMac.trim().toUpperCase().replaceAll("-", ":");
  const previewMacError = normalizedPreviewMac && !/^([0-9A-F]{2}:){5}[0-9A-F]{2}$/.test(normalizedPreviewMac)
    ? "Enter a complete MAC address, for example F2:D9:9E:5C:FC:4B."
    : null;

  useEffect(() => {
    api.get<TenantSettings>("/api/settings")
      .then((r) => {
        setForm(r.data);
        if (r.data.hasLogo) void loadImage("logo");
        if (r.data.hasBackground) void loadImage("background");
      })
      .catch(() => setError("Could not load settings"));
  }, [loadImage]);

  /* Fetch the authenticated preview with the dashboard bearer token, then render
     it as a document. A direct iframe request cannot carry that token. */
  useEffect(() => {
    if (!form) return;
    if (previewMacError) return;
    let objectUrl: string | null = null;
    let cancelled = false;
    const timer = window.setTimeout(() => {
      setPreviewLoading(true);
      setPreviewError(null);
      api.get<string>("/api/settings/portal-preview", {
        params: { template: form.portalTemplate, mac: normalizedPreviewMac || undefined },
        responseType: "text",
      }).then((response) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(new Blob([response.data], { type: "text/html" }));
        setPreviewSrc(objectUrl);
      }).catch(() => {
        if (!cancelled) {
          setPreviewSrc(null);
          setPreviewError("Could not load the live portal preview.");
          setPreviewLoading(false);
        }
      });
    }, 350);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [form, normalizedPreviewMac, previewKey, previewMacError]);

  const set = <K extends keyof TenantSettings>(key: K, value: TenantSettings[K]) =>
    setForm((f) => (f ? { ...f, [key]: value } : f));

  const singleFlight = useSingleFlight();

  const save = singleFlight(async (e?: React.FormEvent) => {
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
  });

  const uploadAsset = async (kind: "logo" | "background", file: File) => {
    setUploading(kind);
    setError(null);
    const previous = previewUrls.current[kind];
    try {
      const image = await shrinkImage(file, kind);
      // Show the chosen picture straight away; the old one comes back if the upload fails.
      showImage(kind, URL.createObjectURL(image), true);
      const data = new FormData();
      data.append("file", image);
      const r = await api.post<TenantSettings>(`/api/settings/assets/${kind}`, data,
        { headers: { "Content-Type": "multipart/form-data" } });
      if (previous) URL.revokeObjectURL(previous);
      setForm(r.data);
      setPreviewKey((k) => k + 1);
    } catch (e) {
      showImage(kind, previous);
      setError(`Couldn't upload the ${kind}: ${message(e)}`);
    } finally {
      setUploading(null);
    }
  };

  const removeAsset = async (kind: "logo" | "background") => {
    try {
      const r = await api.delete<TenantSettings>(`/api/settings/assets/${kind}`);
      showImage(kind, null);
      setForm(r.data);
      setPreviewKey((k) => k + 1);
    } catch (e) {
      setError(`Couldn't remove the ${kind}: ${message(e)}`);
    }
  };

  if (!form) {
    return (
      <div className="mx-auto max-w-3xl space-y-5" aria-busy={!error}>
        {error
          ? <p role="alert" className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">{error}</p>
          : <><span className="sr-only" role="status">Loading settings…</span><div className={`${panel} p-6`}><FormSkeleton fields={5} /></div></>}
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div>
        <h1 className="text-xl font-bold tracking-tight text-[var(--fg)]">Settings</h1>
        <p className="mt-1 text-sm text-[var(--fg-muted)]">
          Branding, portal behavior, voucher defaults, and notifications for your hotspot business.
        </p>
      </div>

      {/* Tab bar */}
      <nav className="flex border-b border-[var(--border)]" aria-label="Settings sections">
        {TABS.map(({ key, name, icon: Icon }) => (
          <button key={key} type="button" onClick={() => setTab(key)}
            className={`flex flex-1 min-w-0 items-center justify-center gap-2 whitespace-nowrap border-b-2 px-3 py-3.5 text-sm font-medium transition ${
              tab === key
                ? "border-blue-600 text-[var(--accent-text)]"
                : "border-transparent text-[var(--fg-muted)] hover:bg-[var(--subtle)] hover:text-[var(--fg)]"
            }`}>
            <Icon size={17} className="shrink-0" />
            <span className="hidden sm:inline">{name}</span>
          </button>
        ))}
      </nav>

      {tab === "payments" ? (
        <PaymentGatewaySettings />
      ) : (
      <form onSubmit={save} className="space-y-6">
        {tab === "general" && (
          <section className={card}>
            <h2 className="text-sm font-semibold text-[var(--fg)]">Your business name &amp; look</h2>
            <p className="mb-5 mt-1 text-xs text-[var(--fg-muted)]">Configure your WiFi business branding and customer support details for {form.name}.</p>
            <div className="grid gap-5 sm:grid-cols-2">
              <AssetBox title="Your Logo" hintText="JPG, PNG, WebP or SVG. Large images are resized automatically. Appears on the login page."
                has={form.hasLogo} uploading={uploading === "logo"} kind="logo" previewUrl={imagePreview.logo}
                onPick={(f) => uploadAsset("logo", f)} onRemove={() => removeAsset("logo")} inputRef={logoRef} />
              <div>
                <label className={label}>Your WiFi business name*</label>
                <input required maxLength={120} value={form.name} onChange={(e) => set("name", e.target.value)} className={field} />
                <p className={hint}>This name will appear on vouchers, SMS messages, and the captive portal.</p>
              </div>
              <div>
                <label className={label}>Brand Color</label>
                <div className="flex items-center gap-3">
                  <input type="color" value={form.brandColor} onChange={(e) => set("brandColor", e.target.value)} className="h-11 w-14 cursor-pointer rounded-lg border border-[var(--border-strong)] bg-[var(--canvas)]" />
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
              <EmailChange currentEmail={form.email} onChanged={(e) => set("email", e)} />
            </div>
          </section>
        )}

        {tab === "hotspot" && (
          <section className={card}>
            <h2 className="mb-5 text-sm font-semibold text-[var(--fg)]">Hotspot</h2>
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
                  <option value="LOG">Detect only — log tethered devices, don&apos;t block</option>
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
              <h2 className="text-sm font-semibold text-[var(--fg)]">Portal Design</h2>
              <p className="mb-5 mt-1 text-xs text-[var(--fg-muted)]">Adjust the branding and appearance of your customer login page. Scroll down to choose a captive portal template.</p>
              <div className="grid gap-5 sm:grid-cols-2">
                <div>
                  <label className={label}>Primary colour override</label>
                  <div className="flex items-center gap-3">
                    <input type="color" value={form.portalPrimaryColor || "#3B82F6"} onChange={(e) => set("portalPrimaryColor", e.target.value)} className="h-11 w-14 cursor-pointer rounded-lg border border-[var(--border-strong)] bg-[var(--canvas)]" />
                    <input pattern="#[0-9A-Fa-f]{6}|" value={form.portalPrimaryColor ?? ""} onChange={(e) => set("portalPrimaryColor", e.target.value || null)} className={`${field} font-mono`} placeholder="Template default" />
                  </div>
                  <p className={hint}>Leave empty to use the selected template colour.</p>
                </div>
                <div>
                  <label className={label}>Secondary colour override</label>
                  <div className="flex items-center gap-3">
                    <input type="color" value={form.portalSecondaryColor || "#BFD9F2"} onChange={(e) => set("portalSecondaryColor", e.target.value)} className="h-11 w-14 cursor-pointer rounded-lg border border-[var(--border-strong)] bg-[var(--canvas)]" />
                    <input pattern="#[0-9A-Fa-f]{6}|" value={form.portalSecondaryColor ?? ""} onChange={(e) => set("portalSecondaryColor", e.target.value || null)} className={`${field} font-mono`} placeholder="Template default" />
                  </div>
                  <p className={hint}>Leave empty to use the selected template colour.</p>
                </div>
                <div>
                  <label className={label}>Logo size</label>
                  <div className="relative">
                    <input type="number" min={40} max={120} value={form.portalLogoSize ?? ""} onChange={(e) => set("portalLogoSize", e.target.value ? Number(e.target.value) : null)} className={`${field} pr-10`} placeholder="Default" />
                    <span className="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-[var(--fg-faint)]">px</span>
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
                  <AssetBox title="" hintText="Optional JPG, PNG or WebP photo. Large photos are resized automatically so the portal loads fast."
                    has={form.hasBackground} uploading={uploading === "background"} kind="background" previewUrl={imagePreview.background}
                    onPick={(f) => uploadAsset("background", f)} onRemove={() => removeAsset("background")} inputRef={bgRef} />
                </div>
              </div>
            </section>

            <section className={card}>
              <h2 className="text-sm font-semibold text-[var(--fg)]">Customer Experience</h2>
              <p className="mb-5 mt-1 text-xs text-[var(--fg-muted)]">Control what customers can see and do on the captive portal.</p>
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
              <h2 className="mb-1 text-sm font-semibold text-[var(--fg)]">Portal template</h2>
              <p className="mb-5 text-xs text-[var(--fg-muted)]">Pick the design customers see on your captive portal. The preview is interactive, so you can test packages, voucher lookup, and feedback yourself.</p>
              <div className="grid gap-6 lg:grid-cols-2">
                <div className="space-y-3">
                  {TEMPLATES.map((t) => (
                    <button key={t.key} type="button" onClick={() => set("portalTemplate", t.key)}
                      className={`flex w-full items-center justify-between rounded-xl border p-4 text-left transition ${
                        form.portalTemplate === t.key ? "border-blue-500 bg-blue-50" : "border-[var(--border-strong)] bg-[var(--canvas)]/50 hover:border-slate-500"
                      }`}>
                      <span>
                        <span className="block text-sm font-semibold text-[var(--fg)]">{t.name}</span>
                        <span className="mt-0.5 block text-xs text-[var(--fg-muted)]">{t.blurb}</span>
                      </span>
                      <span className={`flex h-5 w-5 items-center justify-center rounded-full border ${form.portalTemplate === t.key ? "border-blue-600 bg-blue-600" : "border-[var(--border-strong)]"}`}>
                        {form.portalTemplate === t.key && <CheckCircle size={12} className="text-white" />}
                      </span>
                    </button>
                  ))}
                </div>
                <div>
                  <div className="overflow-hidden rounded-xl border border-[var(--border-strong)] bg-[var(--canvas)]">
                    <div className="flex items-center justify-between gap-3 border-b border-[var(--border)] px-3 py-2">
                      <span className="text-[10px] text-[var(--fg-muted)]">Live preview — {form.portalTemplate}</span>
                      {previewSrc && (
                        <a
                          href={previewSrc}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="inline-flex items-center gap-1 rounded text-[10px] font-semibold text-[var(--accent-text)] hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600"
                        >
                          Open in new tab
                          <ArrowSquareOut size={12} aria-hidden />
                        </a>
                      )}
                    </div>
                    <div className="border-b border-[var(--border)] bg-[var(--subtle)] px-3 py-2">
                      <label htmlFor="preview-device-mac" className="mb-1 block text-[10px] font-semibold text-[var(--fg-muted)]">
                        Customer device MAC (optional)
                      </label>
                      <input
                        id="preview-device-mac"
                        value={previewMac}
                        onChange={(e) => setPreviewMac(e.target.value.toUpperCase())}
                        placeholder="Example: F2:D9:9E:5C:FC:4B"
                        className="w-full rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] px-2.5 py-1.5 font-mono text-[11px] text-[var(--fg)] outline-none focus:border-blue-500"
                      />
                      <p className="mt-1 text-[10px] text-[var(--fg-muted)]">Enter the client&apos;s MAC to reproduce whether their bought voucher is revealed or locked.</p>
                    </div>
                    <div className="relative h-[620px]">
                      {previewSrc && (
                        <iframe
                          key={previewKey}
                          src={previewSrc}
                          title="Captive portal preview"
                          onLoad={() => setPreviewLoading(false)}
                          className="h-full w-full bg-[var(--surface)]"
                        />
                      )}
                      {(previewMacError || previewError) && (
                        <div className="absolute inset-0 flex items-center justify-center bg-[var(--canvas)] px-6 text-center text-xs text-red-600">
                          {previewMacError || previewError}
                        </div>
                      )}
                      {previewLoading && !previewMacError && (
                        <div className="absolute inset-0 flex items-center justify-center bg-[var(--canvas)] text-xs text-[var(--fg-muted)]">
                          <CircleNotch size={16} className="mr-2 animate-spin" />Rendering preview…
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            </section>
          </>
        )}

        {tab === "vouchers" && (
          <section className={card}>
            <h2 className="mb-5 text-sm font-semibold text-[var(--fg)]">Voucher defaults</h2>
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
            <p className="mt-3 text-xs text-[var(--fg-muted)]">These pre-fill the voucher generation form — you can still override per batch.</p>
          </section>
        )}

        {tab === "notifications" && (
          <section className={card}>
            <h2 className="mb-5 text-sm font-semibold text-[var(--fg)]">Notifications & Router Alerts</h2>
            <div className="space-y-6">
              <div className="rounded-xl border border-[var(--border)] bg-[var(--surface-subtle)] p-4">
                <Toggle
                  on={form.notifyRouterOffline ?? true}
                  onChange={(v) => set("notifyRouterOffline", v)}
                  title="MikroTik Router Connectivity & Power Alerts"
                  desc="Receive automatic email alerts when your router loses power or Internet connectivity, and a recovery confirmation when it comes back online."
                />
              </div>

              <div className="grid gap-5 sm:grid-cols-2">
                <div>
                  <label className={label}>Alert emails</label>
                  <input maxLength={500} value={form.notifyEmails ?? ""} onChange={(e) => set("notifyEmails", e.target.value || null)} className={field} placeholder="ops@yourbusiness.com, you@gmail.com" />
                  <p className={hint}>Comma-separated additional recipients. Account owner email always receives alerts by default.</p>
                </div>
                <div>
                  <label className={label}>Alert phone numbers</label>
                  <input maxLength={255} value={form.notifyPhones ?? ""} onChange={(e) => set("notifyPhones", e.target.value || null)} className={field} placeholder="+2567XXXXXXXX" />
                  <p className={hint}>For SMS alerts once messaging is enabled.</p>
                </div>
              </div>
            </div>
          </section>
        )}

        {error && <div className="rounded-xl border border-red-200 bg-red-50 p-3 text-xs text-red-700">{error}</div>}

        <div className="flex items-center gap-4">
          <button disabled={saving} className="flex items-center gap-2 rounded-lg bg-blue-600 px-6 py-2.5 text-sm font-semibold text-white hover:bg-blue-500 disabled:opacity-50">
            {saving ? <CircleNotch size={16} className="animate-spin" /> : <FloppyDisk size={16} />}
            <BusyLabel busy={saving} label="Saving…">Save settings</BusyLabel>
          </button>
          {saved && <span className="flex items-center gap-1.5 text-sm text-emerald-700"><CheckCircle size={15} />Saved</span>}
        </div>
      </form>
      )}
    </div>
  );
}
