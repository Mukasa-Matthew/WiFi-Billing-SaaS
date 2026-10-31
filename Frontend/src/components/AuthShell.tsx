import React from "react";
import { CheckCircle, WarningCircle } from "@/components/icons";
import { LogoMark } from "@/components/Logo";

const FEATURES = ["Sell packages and vouchers", "Collect MTN and Airtel mobile money", "See every MikroTik router in one place"];

export const authSubmit = "flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-[var(--primary)] px-4 text-sm font-semibold text-white shadow-sm transition-[background-color,transform] duration-200 hover:bg-[var(--primary-hover)] active:scale-[0.985] disabled:cursor-not-allowed disabled:opacity-50 disabled:active:scale-100";

/** Arcs radiating from the brand's signal point; they draw in once, then breathe. */
function SignalField() {
  return (
    <svg viewBox="0 0 600 600" fill="none" aria-hidden className="pointer-events-none absolute -bottom-40 -right-40 h-[40rem] w-[40rem] text-[var(--sidebar-indicator)] max-lg:-right-28 max-lg:-top-44 max-lg:bottom-auto max-lg:h-[26rem] max-lg:w-[26rem]">
      {[110, 185, 260, 335, 410, 485].map((r, i) => (
        <circle key={r} className="signal-ring" cx="300" cy="300" r={r} stroke="currentColor" strokeWidth={i < 2 ? 2 : 1.25} style={{ animationDelay: `${i * 90}ms, ${900 + i * 420}ms`, opacity: 0.44 - i * 0.06 }} pathLength={1} />
      ))}
      <circle className="signal-core" cx="300" cy="300" r="9" fill="currentColor" />
    </svg>
  );
}

/** Shared frame for every signed-out screen. */
export function AuthShell({ title, description, children }: { title: React.ReactNode; description?: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="grid min-h-dvh bg-[var(--canvas)] lg:grid-cols-[minmax(0,1.05fr)_minmax(0,1fr)]">
      <aside className="relative isolate overflow-hidden bg-[#08282E] px-6 pb-16 pt-7 text-white lg:flex lg:flex-col lg:justify-between lg:p-12 xl:p-16">
        <div aria-hidden className="absolute inset-0 -z-10 bg-[radial-gradient(60rem_40rem_at_100%_100%,rgb(11_122_117/0.55),transparent_65%)]" />
        <SignalField />
        <div className="flex items-center gap-2.5">
          <LogoMark className="h-9 w-9" />
          <span className="font-display text-xl font-bold tracking-[-0.03em]">AirPairy</span>
        </div>
        <div className="relative mt-6 lg:mt-0">
          <p className="max-w-lg font-display text-[1.65rem] font-semibold leading-[1.05] tracking-[-0.03em] lg:text-[3.4rem]">Connecting WiFi. Simplifying Business.</p>
          <ul className="mt-9 hidden space-y-3 lg:block">
            {FEATURES.map((feature) => <li key={feature} className="flex items-center gap-3 text-[15px] text-[#BFDAD6]"><span className="h-1.5 w-1.5 rounded-full bg-[var(--sidebar-indicator)]" aria-hidden />{feature}</li>)}
          </ul>
        </div>
        <p className="relative hidden text-xs text-[#8FB5B0] lg:block">AirPairy is a product by Thewton.</p>
      </aside>

      <main className="relative -mt-8 flex items-start justify-center rounded-t-[1.75rem] bg-[var(--canvas)] px-5 pb-10 pt-8 lg:mt-0 lg:items-center lg:rounded-none lg:p-12">
        <div className="auth-enter w-full max-w-[26rem]">
          <h1 className="text-[1.75rem] font-bold leading-tight tracking-[-0.025em] text-[var(--fg)]">{title}</h1>
          {description && <p className="mt-1.5 text-sm leading-relaxed text-[var(--fg-muted)] [overflow-wrap:anywhere]">{description}</p>}
          <div className="mt-7">{children}</div>
          <p className="mt-8 text-center text-[11px] text-[var(--fg-faint)] lg:hidden">AirPairy is a product by Thewton.</p>
        </div>
      </main>
    </div>
  );
}

export function AuthAlert({ tone = "error", children }: { tone?: "error" | "success" | "warning"; children: React.ReactNode }) {
  const color = tone === "error" ? "border-red-200 bg-red-50 text-red-700" : tone === "success" ? "border-emerald-200 bg-emerald-50 text-emerald-700" : "border-amber-200 bg-amber-50 text-amber-800";
  const Icon = tone === "success" ? CheckCircle : WarningCircle;
  return (
    <div role={tone === "error" ? "alert" : "status"} className={`mb-5 flex items-start gap-3 rounded-xl border p-3.5 text-sm ${color}`}>
      <Icon className="mt-0.5 h-5 w-5 shrink-0" aria-hidden /><span>{children}</span>
    </div>
  );
}

type FieldProps = React.InputHTMLAttributes<HTMLInputElement> & {
  id: string; label: string; icon: React.ElementType; aside?: React.ReactNode; trailing?: React.ReactNode;
};

/** Labelled input with a leading icon; `aside` sits on the label row, `trailing` inside the field. */
export function AuthField({ id, label, icon: Icon, aside, trailing, className = "", ...input }: FieldProps) {
  return (
    <div>
      <div className="mb-1.5 flex min-h-5 items-center justify-between gap-3">
        <label htmlFor={id} className="text-[13px] font-semibold text-[var(--fg-body)]">{label}</label>
        {aside}
      </div>
      <div className="group relative">
        <Icon aria-hidden className="pointer-events-none absolute left-3.5 top-1/2 h-[18px] w-[18px] -translate-y-1/2 text-[var(--fg-muted)] transition-colors group-focus-within:text-[var(--accent-text)]" />
        <input id={id} {...input} className={`min-h-12 w-full rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] pl-11 pr-4 text-[15px] text-[var(--fg)] outline-none transition-[border-color,box-shadow] duration-200 placeholder:text-[var(--fg-muted)] focus:border-[var(--ring)] focus:ring-[3px] focus:ring-[var(--ring)]/20 ${trailing ? "pr-12" : ""} ${className}`} />
        {trailing}
      </div>
    </div>
  );
}
