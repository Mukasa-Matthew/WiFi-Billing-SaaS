import Link from "next/link";
import { ArrowRight, CellTower } from "@/components/icons";
import { button } from "@/components/BillingUI";

const STEPS = [
  { title: "Name it", detail: "Give your router a name and pick its internet port." },
  { title: "Paste one command", detail: "Copy it into WinBox's New Terminal and press Enter." },
  { title: "Done", detail: "AirPairy sets up your Wi-Fi, login page and payments." },
];

/** Shown when a tenant has no routers yet. `compact` fits inside a dashboard card body. */
export function FirstRouterCard({ compact = false }: { compact?: boolean }) {
  return (
    <div className={compact ? "px-5 py-6" : "rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-6 shadow-sm md:p-8"}>
      <div className={`flex items-start gap-4 ${compact ? "" : "md:items-center"}`}>
        <span className={`flex shrink-0 items-center justify-center rounded-xl bg-blue-50 text-blue-700 ${compact ? "h-10 w-10" : "h-12 w-12"}`}>
          <CellTower className={compact ? "h-5 w-5" : "h-6 w-6"} />
        </span>
        <div>
          <h2 className={`font-semibold text-[var(--fg)] ${compact ? "text-sm" : "text-lg"}`}>Set up your first router</h2>
          <p className="mt-0.5 text-xs text-[var(--fg-muted)]">Connect your MikroTik so your customers can start buying WiFi.</p>
        </div>
      </div>

      <ol className={`mt-5 grid gap-3 ${compact ? "" : "md:grid-cols-3"}`}>
        {STEPS.map((step, i) => (
          <li key={step.title} className="flex items-start gap-3 rounded-xl border border-[var(--border)] bg-[var(--canvas)] p-3.5">
            <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full border border-blue-500 text-[11px] font-semibold text-[var(--accent-text)]">{i + 1}</span>
            <span>
              <span className="block text-sm font-medium text-[var(--fg)]">{step.title}</span>
              <span className="mt-0.5 block text-xs text-[var(--fg-muted)]">{step.detail}</span>
            </span>
          </li>
        ))}
      </ol>

      <div className="mt-5">
        <Link href="/dashboard/routers/new" className={`${button} ${compact ? "w-full" : ""}`}>
          Set up a router <ArrowRight className="h-4 w-4" />
        </Link>
      </div>
    </div>
  );
}
