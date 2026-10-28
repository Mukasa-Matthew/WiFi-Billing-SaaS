"use client";

import Link from "next/link";
import { WarningCircle, ArrowClockwise, ArrowLeft } from "@/components/icons";
import { button, buttonGhost } from "@/components/BillingUI";

/**
 * Shared body for error.tsx / global-error.tsx / not-found.tsx.
 * `reference` is the error digest (server log id) when Next provides one, so a
 * user can quote it to support without seeing internals.
 */
export function ErrorState({ title, body, onRetry, reference, homeHref = "/", homeLabel = "Go to my workspace", code }: {
  title: string;
  body: string;
  onRetry?: () => void;
  reference?: string;
  homeHref?: string;
  homeLabel?: string;
  code?: string;
}) {
  return (
    <section role="alert" className="panel-shadow mx-auto w-full max-w-xl rounded-xl border border-[var(--border)] bg-[var(--surface)] p-8 text-center">
      <span className="mx-auto flex h-11 w-11 items-center justify-center rounded-full border border-red-200 bg-red-50 text-red-700">
        <WarningCircle size={21} aria-hidden="true" />
      </span>
      {code && <p className="num mt-4 text-xs font-semibold uppercase tracking-wider text-[var(--fg-muted)]">{code}</p>}
      <h1 className={`${code ? "mt-1" : "mt-4"} text-lg font-semibold text-[var(--fg)]`}>{title}</h1>
      <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-[var(--fg-muted)]">{body}</p>
      <div className="mt-5 flex flex-col-reverse justify-center gap-2 sm:flex-row">
        <Link href={homeHref} className={buttonGhost}><ArrowLeft size={15} aria-hidden="true" /> {homeLabel}</Link>
        {onRetry && (
          <button type="button" onClick={onRetry} className={button}>
            <ArrowClockwise size={15} aria-hidden="true" /> Try again
          </button>
        )}
      </div>
      {reference && <p className="num mt-5 text-xs text-[var(--fg-muted)]">Reference: {reference}</p>}
    </section>
  );
}
