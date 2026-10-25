"use client";

import { CircleNotch } from "@/components/icons";
import { useLinkStatus } from "next/link";
import { useSyncExternalStore } from "react";
import { getPendingRequests, subscribeRequests } from "@/lib/api";

export function LoadingIndicator({ label = "Loading…", className = "" }: { label?: string; className?: string }) {
  return <span role="status" className={`inline-flex items-center justify-center gap-2 text-sm font-medium text-[var(--fg-muted)] ${className}`}>
    <CircleNotch aria-hidden="true" className="h-4 w-4 shrink-0 animate-spin motion-reduce:animate-none" />
    {label}
  </span>;
}

export function LinkPending() {
  const { pending } = useLinkStatus();
  return <span className="inline-flex w-4 shrink-0 items-center" aria-live="polite">
    {pending && <><CircleNotch aria-hidden="true" className="h-3.5 w-3.5 animate-spin motion-reduce:animate-none" /><span className="sr-only">Opening page…</span></>}
  </span>;
}

export function NetworkActivity() {
  const count = useSyncExternalStore(subscribeRequests, getPendingRequests, () => 0);
  if (!count) return null;
  return <>
    <div className="pointer-events-none fixed inset-x-0 top-0 z-[80] h-1 overflow-hidden bg-blue-100" aria-hidden="true"><div className="request-progress h-full w-1/3 bg-blue-600" /></div>
    <div className="pointer-events-none fixed bottom-4 right-4 z-[80] rounded-lg border border-blue-200 bg-[var(--surface)] px-4 py-3 shadow-sm"><LoadingIndicator label="Loading data…" /></div>
  </>;
}

export function DashboardLoading({ label = "Loading your dashboard…" }: { label?: string }) {
  return <div className="mx-auto w-full max-w-7xl space-y-5" aria-busy="true">
    <LoadingIndicator label={label} />
    <div aria-hidden="true" className="space-y-5 animate-pulse motion-reduce:animate-none">
      <div className="h-28 rounded-xl border border-[var(--border)] bg-[var(--surface)] p-6"><div className="h-5 w-56 max-w-full rounded bg-[var(--border)]" /><div className="mt-4 h-3 w-72 max-w-full rounded bg-[var(--fill)]" /></div>
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">{Array.from({ length: 8 }, (_, i) => <div key={i} className="h-36 rounded-xl border border-[var(--border)] bg-[var(--surface)] p-5"><div className="h-3 w-24 rounded bg-[var(--fill)]" /><div className="mt-5 h-7 w-32 rounded bg-[var(--border)]" /><div className="mt-4 h-3 w-36 rounded bg-[var(--fill)]" /></div>)}</div>
      <div className="grid gap-5 lg:grid-cols-2"><div className="h-64 rounded-xl border border-[var(--border)] bg-[var(--surface)]" /><div className="h-64 rounded-xl border border-[var(--border)] bg-[var(--surface)]" /></div>
    </div>
  </div>;
}
