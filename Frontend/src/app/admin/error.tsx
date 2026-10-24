"use client";

import { WarningCircle, ArrowClockwise } from "@phosphor-icons/react";
import { button } from "@/components/BillingUI";

export default function AdminError({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <section role="alert" className="panel-shadow mx-auto max-w-xl rounded-xl border border-[var(--border)] bg-[var(--surface)] p-8 text-center">
      <span className="mx-auto flex h-11 w-11 items-center justify-center rounded-full border border-red-200 bg-red-50 text-red-700">
        <WarningCircle size={21} aria-hidden="true" />
      </span>
      <h1 className="mt-4 text-lg font-semibold text-[var(--fg)]">The platform view could not be loaded</h1>
      <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-[var(--fg-muted)]">No operation was completed. Check your connection and retry this page.</p>
      <button type="button" onClick={reset} className={`${button} mt-5`}>
        <ArrowClockwise size={15} aria-hidden="true" /> Try again
      </button>
    </section>
  );
}
