"use client";

import React, { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import api from "@/lib/api";
import {
  ArrowLeft,
  Check,
  CheckCircle,
  CaretRight,
  WarningCircle,
  Copy,
  CircleNotch,
  CellTower as RouterIcon,
  Terminal,
  WifiHigh,
} from "@phosphor-icons/react";

interface RouterRecord {
  id: number;
  name: string;
  token: string;
  wgIp: string;
  wgPublicKey: string | null;
  wanInterface: string;
  hotspotInterfaces: string[];
  wifiSsid: string;
  status: string;
}

const MAX_ATTEMPTS = 300;

export default function AddRouterPage() {
  const navigation = useRouter();
  const [step, setStep] = useState(1);
  const [identity, setIdentity] = useState("MikroTik-1");
  const [hasWireless, setHasWireless] = useState(true);
  const [wanInterface, setWanInterface] = useState("ether1");
  const [router, setRouter] = useState<RouterRecord | null>(null);
  const [creating, setCreating] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.get<RouterRecord[]>("/api/routers").then((response) => {
      setIdentity(`MikroTik-${response.data.length + 1}`);
    }).catch(() => undefined);
  }, []);

  const provisioningCommand = useMemo(() => {
    if (!router || typeof window === "undefined") return "";
    const url = `${window.location.origin}/api/router/provision/${router.token}`;
    return `:do { /tool fetch url="${url}" dst-path=wifi-billing.rsc; /import wifi-billing.rsc } on-error={ :put "Provisioning failed. Check internet access and try again." }`;
  }, [router]);

  // A public-key registration alone is not readiness. The backend only marks
  // the router online after telemetry confirms hotspot, RADIUS and portal assets.
  const connected = router?.status === "online";
  const degraded = router?.status === "degraded";

  useEffect(() => {
    if (step !== 2 || !router || connected || attempt >= MAX_ATTEMPTS) return;
    const timer = window.setTimeout(async () => {
      try {
        const response = await api.get<RouterRecord>(`/api/routers/${router.id}`);
        setRouter(response.data);
      } catch {
        // A temporary polling error should not interrupt router provisioning.
      } finally {
        setAttempt((value) => value + 1);
      }
    }, 2000);
    return () => window.clearTimeout(timer);
  }, [step, router, connected, attempt]);

  const createRouter = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!identity.trim()) return;
    if (router) { setStep(2); return; }
    setCreating(true);
    setError(null);
    try {
      const response = await api.post<RouterRecord>("/api/routers", {
        name: identity.trim(),
        wanInterface: wanInterface.trim() || "ether1",
        hotspotInterfaces: [],
        wifiSsid: hasWireless ? identity.trim().replace(/[^\x20-\x7E]/g, "").slice(0, 32) : "WiFi Hotspot",
      });
      setRouter(response.data);
      setAttempt(0);
      setStep(2);
    } catch (requestError: unknown) {
      const message = requestError && typeof requestError === "object" && "response" in requestError
        ? (requestError as { response?: { data?: { message?: string } } }).response?.data?.message
        : undefined;
      setError(message || "Could not create the MikroTik device.");
    } finally {
      setCreating(false);
    }
  };

  const copyCommand = async () => {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(provisioningCommand);
      } else {
        const text = document.createElement("textarea");
        text.value = provisioningCommand; document.body.appendChild(text); text.select();
        const success = document.execCommand("copy"); text.remove();
        if (!success) throw new Error("Copy unavailable");
      }
    } catch { setError("Copy is unavailable in this browser. Select and copy the command manually."); return; }
    setCopied(true);
    window.setTimeout(() => setCopied(false), 2000);
  };

  const steps = [
    { number: 1, label: "Connection", detail: "Basic device information" },
    { number: 2, label: "Device Details", detail: "Provisioning command" },
    { number: 3, label: "Service Setup", detail: "Configure Hotspot" },
  ];

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <button
        onClick={() => navigation.push("/dashboard/routers")}
        className="flex items-center gap-2 text-xs font-medium text-slate-500 transition hover:text-slate-900"
      >
        <ArrowLeft className="h-4 w-4" /> Back to routers
      </button>

      <div>
        <div className="mb-2 flex items-center gap-2 text-xs text-slate-500">
          <span>MikroTik</span><CaretRight className="h-3.5 w-3.5" /><span>Create</span>
        </div>
        <h1 className="text-3xl font-bold tracking-tight text-slate-900">Add MikroTik Device</h1>
        <p className="mt-1 max-w-2xl text-sm text-slate-500">
          Connect your MikroTik router to enable automated provisioning, hotspot billing, and management.
        </p>
      </div>

      <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white/70 shadow-sm">
        <div className="grid border-b border-slate-200 md:grid-cols-3">
          {steps.map((item) => {
            const complete = step > item.number;
            const active = step === item.number;
            return (
              <div key={item.number} className={`flex items-center gap-4 px-6 py-5 ${active ? "bg-slate-100/40" : ""}`}>
                <div className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-full border text-sm font-semibold ${
                  complete ? "border-blue-600 bg-blue-600 text-white" : active ? "border-blue-500 text-blue-700" : "border-slate-600 text-slate-500"
                }`}>
                  {complete ? <Check className="h-5 w-5" /> : String(item.number).padStart(2, "0")}
                </div>
                <div>
                  <p className={`text-sm font-medium ${active || complete ? "text-slate-900" : "text-slate-500"}`}>{item.label}</p>
                  <p className="text-xs text-slate-500">{item.detail}</p>
                </div>
              </div>
            );
          })}
        </div>

        <div className="p-6 md:p-8">
          {step === 1 && (
            <form onSubmit={createRouter} className="space-y-7">
              <div>
                <label className="mb-2 block text-sm font-semibold text-slate-900">MikroTik Identity <span className="text-red-600">*</span></label>
                <input
                  required
                  maxLength={80}
                  value={identity}
                  onChange={(event) => setIdentity(event.target.value)}
                  className="w-full rounded-xl border border-slate-300 bg-[#F5F7FA]/70 px-4 py-3 text-sm text-slate-900 outline-none transition focus:border-blue-500"
                />
                <p className="mt-2 text-xs text-slate-500">Auto-generated name — you can change it if needed.</p>
              </div>

              <div>
                <label className="mb-2 block text-sm font-semibold text-slate-900">Internet / ISP port</label>
                <input
                  required
                  maxLength={32}
                  value={wanInterface}
                  onChange={(event) => setWanInterface(event.target.value)}
                  className="w-full rounded-xl border border-slate-300 bg-[#F5F7FA]/70 px-4 py-3 font-mono text-sm text-slate-900 outline-none transition focus:border-blue-500"
                  placeholder="ether1"
                />
                <p className="mt-2 text-xs text-slate-500">
                  The port your ISP cable plugs into (e.g. <code className="text-slate-700">ether1</code>, <code className="text-slate-700">sfp1</code>). All other ports and Wi-Fi are bridged to the hotspot automatically — no port counting needed.
                </p>
              </div>

              <label className="flex cursor-pointer items-start gap-4">
                <button
                  type="button"
                  role="switch"
                  aria-checked={hasWireless}
                  onClick={() => setHasWireless((value) => !value)}
                  className={`relative mt-0.5 h-6 w-11 rounded-full transition ${hasWireless ? "bg-blue-600" : "bg-slate-700"}`}
                >
                  <span className={`absolute top-1 h-4 w-4 rounded-full bg-white transition ${hasWireless ? "left-6" : "left-1"}`} />
                </button>
                <span>
                  <span className="block text-sm font-semibold text-slate-900">Router has Wireless</span>
                  <span className="mt-1 block text-xs text-slate-500">Enable this if your MikroTik router has Wi-Fi capabilities.</span>
                </span>
              </label>

              {error && <div className="rounded-xl border border-red-200 bg-red-50 p-3 text-xs text-red-300">{error}</div>}

              <div className="flex justify-between border-t border-slate-200 pt-5">
                <button type="button" onClick={() => navigation.push("/dashboard/routers")} className="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-100">Cancel</button>
                <button disabled={creating} className="rounded-lg bg-blue-600 px-5 py-2 text-sm font-semibold text-white hover:bg-blue-500 disabled:opacity-50">
                  {creating ? "Creating..." : "Next"}
                </button>
              </div>
            </form>
          )}

          {step === 2 && router && (
            <div className="space-y-6">
              {error && <p role="alert" className="text-xs text-amber-700">{error}</p>}
              {attempt >= MAX_ATTEMPTS && !connected && <button className="text-xs text-blue-700" onClick={() => setAttempt(0)}>Polling timed out. Retry connection checks</button>}
              <div className="overflow-hidden rounded-xl border border-slate-300 bg-[#F5F7FA]/60">
                <div className="flex items-center justify-between border-b border-slate-300 px-5 py-4">
                  <span className="flex items-center gap-2 text-sm font-semibold text-slate-900"><Terminal className="h-4 w-4" /> Provisioning Command</span>
                  <button onClick={copyCommand} className="flex items-center gap-1.5 text-xs font-medium text-blue-700 hover:text-blue-600">
                    {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}{copied ? "Copied" : "Click to copy"}
                  </button>
                </div>
                <pre className="overflow-x-auto whitespace-pre bg-[#0B1F36] px-5 py-5 font-mono text-xs text-slate-200">{provisioningCommand}</pre>
                <div className="flex gap-3 border-t border-slate-200 bg-white px-5 py-4 text-xs text-slate-500">
                  <WarningCircle className="mt-0.5 h-4 w-4 shrink-0" />
                  <p>If the router reports <strong className="text-slate-700">device mode not allowed</strong>, run <code className="text-blue-600">/system/device-mode/update mode=advanced</code>, power-cycle the router, and retry the command.</p>
                </div>
              </div>

              <div className={`rounded-xl border p-5 ${connected ? "border-emerald-200 bg-emerald-50" : "border-slate-200 bg-slate-50"}`}>
                <div className="flex items-start gap-3">
                  {connected ? <CheckCircle className="h-6 w-6 text-emerald-700" /> : <CircleNotch className="h-6 w-6 animate-spin text-blue-700" />}
                  <div>
                    <p className={`font-mono text-sm font-semibold ${connected ? "text-emerald-700" : "text-slate-900"}`}>
                      {connected ? "Router is verified and ready" : degraded ? "Router connected, but readiness checks failed" : "$ Paste the provisioning command in your MikroTik terminal to begin"}
                    </p>
                    <p className="mt-2 text-xs text-slate-500">
                      {connected
                        ? `Tunnel ${router.wgIp}, hotspot, RADIUS configuration and portal assets are reporting ready.`
                        : degraded
                          ? "Review the router log and provisioning troubleshooting guide; the dashboard will keep checking."
                          : "The system waits for configuration completion and a successful readiness report."}
                    </p>
                    {!connected && <p className="mt-3 text-xs text-slate-500">Attempt {Math.min(attempt + 1, MAX_ATTEMPTS)} of {MAX_ATTEMPTS}</p>}
                  </div>
                </div>
              </div>

              <div className="flex justify-between border-t border-slate-200 pt-5">
                <button onClick={() => setStep(1)} disabled={connected} className="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-100 disabled:opacity-40">Back</button>
                <button onClick={() => setStep(3)} disabled={!connected} className="rounded-lg bg-blue-600 px-5 py-2 text-sm font-semibold text-white hover:bg-blue-500 disabled:cursor-not-allowed disabled:opacity-40">Next</button>
              </div>
            </div>
          )}

          {step === 3 && router && (
            <div className="space-y-6">
              <div className="rounded-2xl border border-emerald-200 bg-emerald-50 p-6 text-center">
                <CheckCircle className="mx-auto h-12 w-12 text-emerald-700" />
                <h2 className="mt-3 text-xl font-bold text-slate-900">CellTower verified and ready</h2>
                <p className="mt-2 text-sm text-slate-500">{router.name} is reporting its managed hotspot, RADIUS configuration and captive portal assets as ready.</p>
              </div>
              <div className="grid gap-4 md:grid-cols-3">
                <div className="rounded-xl border border-slate-200 bg-[#F5F7FA]/50 p-4"><p className="text-xs text-slate-500">WAN interface</p><p className="mt-1 font-mono text-sm text-slate-900">{router.wanInterface}</p></div>
                <div className="rounded-xl border border-slate-200 bg-[#F5F7FA]/50 p-4"><p className="text-xs text-slate-500">Hotspot ports</p><p className="mt-1 font-mono text-sm text-slate-900">{router.hotspotInterfaces.length > 0 ? router.hotspotInterfaces.join(", ") : `All ports except ${router.wanInterface} (auto)`}</p></div>
                <div className="rounded-xl border border-slate-200 bg-[#F5F7FA]/50 p-4"><p className="text-xs text-slate-500">Wi-Fi name</p><p className="mt-1 flex items-center gap-2 text-sm text-slate-900"><WifiHigh className="h-4 w-4 text-blue-700" />{hasWireless ? router.wifiSsid : "Wireless disabled"}</p></div>
              </div>
              <div className="flex justify-end border-t border-slate-200 pt-5">
                <button onClick={() => navigation.push("/dashboard/routers")} className="flex items-center gap-2 rounded-lg bg-blue-600 px-5 py-2 text-sm font-semibold text-white hover:bg-blue-500">
                  Finish <RouterIcon className="h-4 w-4" />
                </button>
              </div>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}
