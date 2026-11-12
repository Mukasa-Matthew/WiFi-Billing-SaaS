import React from "react";
import { CheckCircle, Radio, WarningCircle } from "@/components/icons";

/** Centered branded card used by the public sign-up pages (matches /login). */
export function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="relative min-h-screen flex items-center justify-center p-4 bg-[var(--canvas)] overflow-hidden">
      <div className="relative w-full max-w-md">
        <a href="https://airpairy.com" className="block text-center mb-8 group">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-[#122B49] mb-4 transition-transform group-hover:scale-105">
            <Radio className="w-7 h-7 text-white" />
          </div>
          <h1 className="text-2xl font-bold tracking-tight text-[var(--fg)] group-hover:text-[var(--accent-text)] transition-colors">AirPairy</h1>
          <p className="text-sm text-[var(--fg-muted)] mt-1">Connecting WiFi. Simplifying Business.</p>
        </a>
        <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl p-8 shadow-sm">{children}</div>
        <a
          href="https://thewtontechnologies.netlify.app/"
          target="_blank"
          rel="noopener"
          className="flex items-center justify-center gap-2 text-sm text-[var(--fg-muted)] mt-4 hover:text-[var(--fg-body)] transition-colors"
        >
          <span>AirPairy is a product by</span>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/logos/thewton-mark-buff.svg" alt="" className="h-6 w-auto" />
          <span className="font-semibold text-[var(--fg)]">Thewton</span>
        </a>
      </div>
    </div>
  );
}

export function AuthAlert({ tone, children }: { tone: "error" | "success"; children: React.ReactNode }) {
  const styles = tone === "error"
    ? "bg-red-50 border-red-200 text-red-600"
    : "bg-emerald-50 border-emerald-200 text-emerald-700";
  const Icon = tone === "error" ? WarningCircle : CheckCircle;
  return (
    <div role={tone === "error" ? "alert" : "status"} className={`mb-6 p-3.5 rounded-xl border flex items-start gap-3 text-sm ${styles}`}>
      <Icon className="w-5 h-5 shrink-0 mt-0.5" />
      <div className="min-w-0">{children}</div>
    </div>
  );
}

export const authInput =
  "w-full pl-10 pr-4 py-2.5 rounded-xl bg-[var(--surface)] border border-[var(--border)] text-sm text-[var(--fg)] placeholder-[var(--fg-muted)] focus:outline-none focus:border-blue-700 focus:ring-2 focus:ring-blue-600/15 transition-all";
export const authLabel = "block text-xs font-medium text-[var(--fg-body)] mb-1.5";
export const authButton =
  "w-full flex items-center justify-center gap-2 py-3 px-4 rounded-lg bg-blue-700 hover:bg-blue-600 disabled:opacity-50 text-sm font-semibold text-white shadow-sm transition-all cursor-pointer";

export function Spinner() {
  return <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />;
}

/** Status + server message from an axios error, without depending on axios types at call sites. */
export function readError(err: unknown): { status?: number; message?: string } {
  if (err && typeof err === "object" && "response" in err) {
    const r = (err as { response?: { status?: number; data?: { message?: string } } }).response;
    return { status: r?.status, message: r?.data?.message };
  }
  return {};
}
