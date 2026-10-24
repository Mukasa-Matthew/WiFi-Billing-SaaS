"use client";
import React, { useEffect, useState } from "react";
import api from "@/lib/api";
import { Badge, Notice, field, button, buttonGhost, label, hint, message } from "@/components/BillingUI";
import { CheckCircle, Copy, CreditCard, Plugs, ShieldCheck, SpinnerGap, Wallet } from "@phosphor-icons/react";

interface GatewayView {
  mode: "PLATFORM" | "OWN";
  connectionStatus: string;
  connectionError: string | null;
  apiKeyMasked: string | null;
  hasSecret: boolean;
  hasWebhookSecret: boolean;
  environment: string;
  country: string;
  lastVerifiedAt: string | null;
  callbackUrl: string;
  platformAvailable: boolean;
  platformFeePercent: number;
}

const card = "rounded-xl border border-[var(--border)] bg-[var(--surface)] p-6";

export default function PaymentGatewaySettings() {
  const [gw, setGw] = useState<GatewayView | null>(null);
  const [err, setErr] = useState("");
  const [ok, setOk] = useState("");
  const [busy, setBusy] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [apiSecret, setApiSecret] = useState("");
  const [webhookSecret, setWebhookSecret] = useState("");
  const [environment, setEnvironment] = useState("live");
  const [copied, setCopied] = useState(false);

  const load = async () => {
    try { setGw((await api.get<GatewayView>("/api/settings/gateway")).data); }
    catch (e) { setErr(message(e)); }
  };
  useEffect(() => { void load(); }, []);

  const act = async (key: string, fn: () => Promise<unknown>, done: string) => {
    setBusy(key); setErr(""); setOk("");
    try { await fn(); setOk(done); await load(); }
    catch (e) { setErr(message(e)); }
    finally { setBusy(""); }
  };

  const selectMode = (mode: "PLATFORM" | "OWN") => {
    const msg = mode === "OWN"
      ? "Switch to your own MarzPay gateway? New payments will settle to YOUR MarzPay account. Existing wallet balance and history are preserved."
      : "Switch back to the platform gateway? New payments will be collected into your platform wallet. Your own gateway credentials remain saved.";
    if (!window.confirm(msg)) return;
    void act("mode", () => api.post("/api/settings/gateway/mode", { mode, confirm: true }), "Gateway mode updated.");
  };

  const saveCreds = (e: React.FormEvent) => {
    e.preventDefault();
    void act("save", () => api.post("/api/settings/gateway/credentials", {
      apiKey, apiSecret, webhookSecret: webhookSecret || undefined, environment,
    }), "Credentials saved — verify the connection below.");
    setApiKey(""); setApiSecret(""); setWebhookSecret("");
  };

  const copy = async () => {
    try { await navigator.clipboard.writeText(gw?.callbackUrl || ""); setCopied(true); setTimeout(() => setCopied(false), 1500); } catch { /* noop */ }
  };

  if (!gw) return <div className={card}><SpinnerGap className="animate-spin text-[var(--fg-faint)]" size={20} /></div>;

  return (
    <div className="space-y-5">
      <Notice text={err} />
      {ok && <p className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-xs font-medium text-emerald-800">{ok}</p>}

      <div className="grid gap-4 md:grid-cols-2">
        {/* Platform gateway */}
        <div className={`${card} ${gw.mode === "PLATFORM" ? "ring-2 ring-blue-500" : ""}`}>
          <div className="flex items-start justify-between">
            <div className="flex items-center gap-2">
              <span className="rounded-lg bg-blue-50 p-2 text-blue-600"><Wallet size={20} /></span>
              <h3 className="text-sm font-bold text-[var(--fg)]">Use Platform Payment Gateway</h3>
            </div>
            {gw.mode === "PLATFORM" && <Badge tone="green">Active</Badge>}
          </div>
          <p className="mt-3 text-xs leading-relaxed text-[var(--fg-muted)]">
            Accept customer payments using our integrated MarzPay gateway. Your earnings appear in
            your business wallet, and you can request withdrawals.
          </p>
          <ul className="mt-4 space-y-1.5 text-xs text-[var(--fg-muted)]">
            <li className="flex items-center gap-2"><CheckCircle size={14} className="text-emerald-500" /> Transaction fee: {gw.platformFeePercent}% per successful collection</li>
            <li className="flex items-center gap-2"><CheckCircle size={14} className="text-emerald-500" /> Wallet withdrawals to your mobile money</li>
            <li className="flex items-center gap-2"><CheckCircle size={14} className="text-emerald-500" /> No MarzPay account needed</li>
          </ul>
          {gw.mode !== "PLATFORM" && (
            <button className={`${buttonGhost} mt-4 w-full`} disabled={busy === "mode"} onClick={() => selectMode("PLATFORM")}>
              {busy === "mode" ? "Switching…" : "Switch to platform gateway"}
            </button>
          )}
        </div>

        {/* Own gateway */}
        <div className={`${card} ${gw.mode === "OWN" ? "ring-2 ring-blue-500" : ""}`}>
          <div className="flex items-start justify-between">
            <div className="flex items-center gap-2">
              <span className="rounded-lg bg-violet-50 p-2 text-violet-600"><CreditCard size={20} /></span>
              <h3 className="text-sm font-bold text-[var(--fg)]">Use My Own Payment Gateway</h3>
            </div>
            {gw.mode === "OWN" && <Badge tone="green">Active</Badge>}
          </div>
          <p className="mt-3 text-xs leading-relaxed text-[var(--fg-muted)]">
            Connect your own MarzPay account and receive customer payments directly into your
            MarzPay wallet. Sales still appear in your dashboard.
          </p>
          {gw.connectionStatus !== "UNCONFIGURED" && (
            <div className="mt-3">
              <Badge tone={gw.connectionStatus === "CONNECTED" ? "green" : "red"}>
                {gw.connectionStatus === "CONNECTED" ? "Connected" : "Connection failed"}
              </Badge>
              {gw.apiKeyMasked && <span className="ml-2 font-mono text-[11px] text-[var(--fg-faint)]">{gw.apiKeyMasked}</span>}
              {gw.connectionError && <p className="mt-1.5 text-[11px] text-red-500">{gw.connectionError}</p>}
            </div>
          )}
          {gw.mode !== "OWN" && (
            <button className={`${button} mt-4 w-full`} disabled={busy === "mode" || !gw.hasSecret || gw.connectionStatus !== "CONNECTED"}
              onClick={() => selectMode("OWN")}
              title={!gw.hasSecret ? "Save and verify credentials first" : undefined}>
              {busy === "mode" ? "Switching…" : "Switch to my own gateway"}
            </button>
          )}
        </div>
      </div>

      {/* Credential form — only relevant for the tenant's OWN gateway.
          Platform mode uses keys from the server environment; tenants never see or fill them. */}
      <div className={card}>
        <div className="flex items-center gap-2">
          <ShieldCheck size={18} className="text-[var(--fg-muted)]" />
          <h3 className="text-sm font-bold text-[var(--fg)]">Your own MarzPay credentials</h3>
        </div>
        <p className="mt-1.5 text-xs text-[var(--fg-muted)]">
          Only needed if you choose <b>“Use My Own Payment Gateway”</b>. The platform gateway needs
          nothing from you — it runs on our keys. From your MarzPay dashboard → API Keys.
          Credentials are encrypted at rest and never shown after saving.
        </p>
        <form onSubmit={saveCreds} className="mt-5 grid gap-4 md:grid-cols-2">
          <div>
            <label className={label}>API Key</label>
            <input className={field} required value={apiKey} onChange={(e) => setApiKey(e.target.value)}
              placeholder={gw.apiKeyMasked || "marz_live_…"} autoComplete="off" />
          </div>
          <div>
            <label className={label}>API Secret</label>
            <input className={field} type="password" required value={apiSecret} onChange={(e) => setApiSecret(e.target.value)}
              placeholder={gw.hasSecret ? "•••••••• (saved)" : "Paste secret"} autoComplete="new-password" />
            <p className={hint}>{gw.hasSecret ? "Entering a new secret rotates the saved one." : "Stored encrypted — never returned to the browser."}</p>
          </div>
          <div>
            <label className={label}>Webhook signing secret <span className="font-normal text-[var(--fg-faint)]">(optional)</span></label>
            <input className={field} type="password" value={webhookSecret} onChange={(e) => setWebhookSecret(e.target.value)}
              placeholder={gw.hasWebhookSecret ? "•••••••• (saved)" : "whsec_…"} autoComplete="new-password" />
            <p className={hint}>Enable webhook signing in MarzPay, then paste the whsec_ secret here. Signed callbacks are verified automatically.</p>
          </div>
          <div>
            <label className={label}>Environment</label>
            <select className={field} value={environment} onChange={(e) => setEnvironment(e.target.value)}>
              <option value="live">Live</option>
              <option value="sandbox">Sandbox / test</option>
            </select>
          </div>
          <div className="md:col-span-2 flex flex-wrap items-center gap-3">
            <button type="submit" className={button} disabled={busy === "save"}>
              {busy === "save" ? "Saving…" : gw.hasSecret ? "Update credentials" : "Save credentials"}
            </button>
            {gw.hasSecret && (
              <>
                <button type="button" className={buttonGhost} disabled={busy === "test"}
                  onClick={() => act("test", () => api.post("/api/settings/gateway/test"), busy === "test" ? "" : "Connection check finished.")}>
                  <Plugs size={14} /> {busy === "test" ? "Testing…" : "Test connection"}
                </button>
                <button type="button" className="text-xs font-semibold text-red-600 hover:underline" disabled={busy === "disconnect"}
                  onClick={() => { if (window.confirm("Disconnect your MarzPay gateway and return to the platform gateway? Your credentials will be deleted.")) void act("disconnect", () => api.post("/api/settings/gateway/disconnect"), "Gateway disconnected."); }}>
                  {busy === "disconnect" ? "Disconnecting…" : "Disconnect gateway"}
                </button>
              </>
            )}
          </div>
        </form>
      </div>

      {/* Webhook setup — only relevant once a tenant has their own gateway */}
      {(gw.mode === "OWN" || gw.hasSecret) && (
      <div className={card}>
        <h3 className="text-sm font-bold text-[var(--fg)]">Webhook / callback setup</h3>
        <p className="mt-1.5 text-xs text-[var(--fg-muted)]">
          In your MarzPay dashboard → Webhooks, register this URL for <b>collection.completed</b>, <b>collection.failed</b>,
          and <b>disbursement.*</b> events so payment status reaches this platform:
        </p>
        <div className="mt-3 flex items-center gap-2">
          <code className="flex-1 truncate rounded-lg bg-[var(--fill)] px-3 py-2 font-mono text-[11px] text-[var(--fg-body)]">{gw.callbackUrl}</code>
          <button type="button" onClick={copy} className={buttonGhost}>
            <Copy size={14} /> {copied ? "Copied" : "Copy"}
          </button>
        </div>
        <p className="mt-3 text-[11px] leading-relaxed text-[var(--fg-faint)]">
          Callbacks are also sent per-request for each collection, so payments work even without a registered webhook —
          registering one adds reliable re-delivery and HMAC signature verification.
        </p>
      </div>
      )}
    </div>
  );
}
