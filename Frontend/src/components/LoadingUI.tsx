"use client";

import { CircleNotch } from "@/components/icons";
import { useLinkStatus } from "next/link";
import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";

export function Spinner({ className = "h-4 w-4" }: { className?: string }) {
  return <CircleNotch aria-hidden="true" className={`shrink-0 animate-spin motion-reduce:animate-none ${className}`} />;
}

export function LoadingIndicator({ label = "Loading…", className = "" }: { label?: string; className?: string }) {
  return <span role="status" className={`inline-flex items-center justify-center gap-2 text-sm font-medium text-[var(--fg-muted)] ${className}`}>
    <Spinner />
    {label}
  </span>;
}

/**
 * Button content for an in-flight action: a spinner plus the busy label
 * ("Saving…"), otherwise the normal children. Callers still own `disabled`
 * so a second click can't submit again.
 */
export function BusyLabel({ busy, label, children }: { busy: boolean; label: string; children: React.ReactNode }) {
  return busy ? <><Spinner className="h-3.5 w-3.5" /><span>{label}</span></> : <>{children}</>;
}

/**
 * One in-flight action at a time. `busy` names the running action (for its
 * button's spinner); the ref also drops a second trigger that lands before
 * React re-renders, which a disabled attribute alone can miss.
 */
export function useBusyAction<K extends string | number = string>() {
  const [busy, setBusy] = useState<K | null>(null);
  const running = useRef(false);
  const run = useCallback(async (key: K, fn: () => Promise<void>) => {
    if (running.current) return;
    running.current = true;
    setBusy(key);
    try { await fn(); } finally { running.current = false; setBusy(null); }
  }, []);
  return { busy, run };
}

/**
 * Wrap a submit/action handler so it can't run twice at once. Forms keep
 * their own `saving` state for the UI; this ref closes the gap before React
 * re-renders, when a double click or double Enter would otherwise submit
 * twice. A dropped form event still gets preventDefault (no page reload).
 */
export function useSingleFlight() {
  const running = useRef(false);
  return useCallback(<A extends unknown[], R>(fn: (...args: A) => Promise<R>) => async (...args: A): Promise<R | undefined> => {
    if (running.current) {
      (args[0] as { preventDefault?: () => void } | undefined)?.preventDefault?.();
      return undefined;
    }
    running.current = true;
    try { return await fn(...args); } finally { running.current = false; }
  }, []);
}

/* ---------- navigation progress ---------- */

// Count of <Link>s whose navigation is pending (useLinkStatus, a documented
// Next API). Prefetched routes never become pending, so fast hops never show.
let pendingNavigations = 0;
const navListeners = new Set<() => void>();
const navStore = {
  subscribe(cb: () => void) { navListeners.add(cb); return () => { navListeners.delete(cb); }; },
  get: () => pendingNavigations,
  change(delta: number) { pendingNavigations = Math.max(0, pendingNavigations + delta); navListeners.forEach((l) => l()); },
};

function useReportNavigation(pending: boolean) {
  useEffect(() => {
    if (!pending) return;
    navStore.change(1);
    return () => navStore.change(-1);
  }, [pending]);
}

/** Inline spinner for sidebar links; also drives the top progress bar. */
export function LinkPending() {
  const { pending } = useLinkStatus();
  useReportNavigation(pending);
  return <span className="inline-flex w-4 shrink-0 items-center" aria-live="polite">
    {pending && <><Spinner className="h-3.5 w-3.5" /><span className="sr-only">Opening page…</span></>}
  </span>;
}

/** Render inside any <Link> to feed the top progress bar without inline UI. */
export function NavigationPending() {
  const { pending } = useLinkStatus();
  useReportNavigation(pending);
  return null;
}

const SHOW_AFTER_MS = 150;

/**
 * Thin top bar while a page navigation is pending. Hidden for navigations
 * that finish within SHOW_AFTER_MS; never blocks the page (pointer-events:
 * none). Data refreshes do not drive it.
 */
export function NavigationProgress() {
  const active = useSyncExternalStore(navStore.subscribe, navStore.get, () => 0) > 0;
  // `shown` flips on after the delay while active, and off once the
  // completion animation has played; the phase itself is derived.
  const [shown, setShown] = useState(false);

  useEffect(() => {
    const timer = window.setTimeout(() => setShown(active), active ? SHOW_AFTER_MS : 300);
    return () => window.clearTimeout(timer);
  }, [active]);

  const phase = shown ? (active ? "running" : "done") : "idle";
  if (phase === "idle") return null;
  return (
    <div className="pointer-events-none fixed inset-x-0 top-0 z-[80] h-0.5" aria-hidden="true">
      <div
        className={`h-full bg-blue-600 shadow-[0_0_8px_rgba(37,99,235,0.5)] ${phase === "running" ? "nav-progress-run" : "nav-progress-done"}`}
      />
    </div>
  );
}

export function DashboardLoading({ label = "Loading your dashboard…" }: { label?: string }) {
  return <div className="mx-auto w-full max-w-7xl space-y-5" aria-busy="true">
    <span className="sr-only" role="status">{label}</span>
    <div aria-hidden="true" className="space-y-5 animate-pulse motion-reduce:animate-none">
      <div className="h-28 rounded-xl border border-[var(--border)] bg-[var(--surface)] p-6"><div className="h-5 w-56 max-w-full rounded bg-[var(--border)]" /><div className="mt-4 h-3 w-72 max-w-full rounded bg-[var(--fill)]" /></div>
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">{Array.from({ length: 8 }, (_, i) => <div key={i} className="h-36 rounded-xl border border-[var(--border)] bg-[var(--surface)] p-5"><div className="h-3 w-24 rounded bg-[var(--fill)]" /><div className="mt-5 h-7 w-32 rounded bg-[var(--border)]" /><div className="mt-4 h-3 w-36 rounded bg-[var(--fill)]" /></div>)}</div>
      <div className="grid gap-5 lg:grid-cols-2"><div className="h-64 rounded-xl border border-[var(--border)] bg-[var(--surface)]" /><div className="h-64 rounded-xl border border-[var(--border)] bg-[var(--surface)]" /></div>
    </div>
  </div>;
}

/** Placeholder <tr>s for a table body; widths vary so it reads as data. */
export function TableSkeletonRows({ rows = 6, columns, cellClassName = () => "" }: { rows?: number; columns: number; cellClassName?: (column: number) => string }) {
  return <>
    {Array.from({ length: rows }, (_, row) => (
      <tr key={`skeleton-${row}`} aria-hidden="true">
        {Array.from({ length: columns }, (_, column) => (
          <td key={column} className={`px-4 py-3.5 ${cellClassName(column)}`}>
            <span className="block h-3.5 max-w-36 animate-pulse rounded bg-[var(--fill)] motion-reduce:animate-none" style={{ width: `${58 + ((row + column) % 4) * 11}%` }} />
          </td>
        ))}
      </tr>
    ))}
  </>;
}

/**
 * List placeholder matching the dashboard's list rows (optional avatar,
 * title and detail lines, trailing value). `inset` adds the card's padding.
 */
export function ListSkeleton({ rows = 3, avatar = true, inset = true }: { rows?: number; avatar?: boolean; inset?: boolean }) {
  return (
    <ul aria-hidden className="divide-y divide-[var(--border-soft)]">
      {Array.from({ length: rows }, (_, i) => (
        <li key={i} className={`flex animate-pulse items-center gap-3 py-3.5 motion-reduce:animate-none ${inset ? "px-5" : ""}`}>
          {avatar && <span className="h-9 w-9 rounded-full bg-[var(--fill)]" />}
          <span className="flex-1 space-y-2"><span className="block h-3 w-2/5 rounded bg-[var(--fill)]" /><span className="block h-2.5 w-3/5 rounded bg-[var(--fill)]" /></span>
          <span className="h-3 w-16 rounded bg-[var(--fill)]" />
        </li>
      ))}
    </ul>
  );
}

/** Form/settings panel placeholder: label + field pairs. */
export function FormSkeleton({ fields = 4 }: { fields?: number }) {
  return <div aria-hidden="true" className="space-y-5 animate-pulse motion-reduce:animate-none">
    {Array.from({ length: fields }, (_, i) => (
      <div key={i} className="space-y-2"><div className="h-3 w-28 rounded bg-[var(--fill)]" /><div className="h-11 rounded-lg border border-[var(--border)] bg-[var(--subtle)]" /></div>
    ))}
  </div>;
}
