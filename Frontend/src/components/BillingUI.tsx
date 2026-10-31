"use client";
import React, { useCallback, useDeferredValue, useEffect, useMemo, useState, useRef, useSyncExternalStore } from "react";
import api from "@/lib/api";
import Link from "next/link";
import { BusyLabel, ListSkeleton, NavigationPending, TableSkeletonRows } from "@/components/LoadingUI";
import { ArrowLeft, ArrowRight, CaretLeft, CaretRight, MagnifyingGlass, Tray } from "@/components/icons";

/* ---------- design tokens ---------- */
export const field = "mt-1.5 min-h-11 w-full rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] px-3 py-2.5 text-sm text-[var(--fg)] placeholder:text-[var(--fg-muted)] outline-none transition-[border-color,box-shadow] focus:border-blue-600 focus:ring-2 focus:ring-blue-600/15 disabled:cursor-not-allowed disabled:opacity-60";
export const button = "inline-flex min-h-10 max-sm:min-h-11 items-center justify-center gap-1.5 rounded-lg bg-blue-700 px-4 py-2.5 text-xs font-semibold text-white transition-[background-color,transform,opacity] hover:bg-blue-600 active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-50 disabled:active:scale-100";
export const buttonGhost = "inline-flex min-h-10 max-sm:min-h-11 items-center justify-center gap-1.5 rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] px-4 py-2.5 text-xs font-semibold text-[var(--fg-body)] transition-[background-color,transform,opacity] hover:bg-[var(--subtle)] active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-50 disabled:active:scale-100";
export const panel = "panel-shadow rounded-xl border border-[var(--border)] bg-[var(--surface)]";
export const label = "block text-xs font-semibold text-[var(--fg-muted)]";
export const hint = "mt-1.5 text-xs text-[var(--fg-muted)]";

/**
 * User-facing text for a failed request. The backend's `message` (see
 * GlobalExceptionHandler) is written for users and wins; otherwise explain
 * the failure class instead of a bare "Request failed".
 */
export function message(error: unknown) {
  const e = error as { code?: string; response?: { status?: number; data?: { message?: string; detail?: string } } };
  const data = e?.response?.data;
  if (data?.message || data?.detail) return (data.message || data.detail) as string;
  if (!e?.response) {
    if (e?.code === "ECONNABORTED" || e?.code === "ETIMEDOUT") return "The request took too long. Check your connection and try again.";
    if (typeof navigator !== "undefined" && navigator.onLine === false) return "You're offline. Check your internet connection and try again.";
    return "Couldn't reach AirPairy. Check your internet connection and try again.";
  }
  const status = e.response.status ?? 0;
  if (status === 403) return "You don't have permission to do that.";
  if (status === 404) return "That item could not be found. It may have been removed.";
  if (status === 429) return "Too many attempts. Wait a moment and try again.";
  if (status === 502 || status === 503 || status === 504) return "AirPairy is temporarily unavailable. Please try again in a moment.";
  if (status >= 500) return "Something went wrong on our side. Please try again.";
  return "Request failed. Please retry.";
}

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useAuth } from "@/context/AuthContext";

/**
 * Cache scope for the signed-in identity, read during render so a page's
 * first render already targets its cached data. (Resolving it in an effect
 * left every mount's first render disabled and empty, which flashed
 * "No records found" even when the data was cached.)
 */
function useCacheScope() {
  const { user } = useAuth();
  if (!user) return "anon";
  return user.role === "ROLE_SUPERADMIN" ? "superadmin" : `tenant-${user.tenantId || 0}`;
}

/**
 * Tenant-scoped shared cache hook for list-shaped endpoints.
 * - Deduplicates requests across components/pages.
 * - Preserves previously loaded data across navigation.
 * - Revalidates in the background (stale-while-revalidate).
 * - Background auto-refresh paused when window/tab is hidden.
 */
export function useResource<T>(url: string, refresh: boolean | number = false) {
  const userScope = useCacheScope();

  const refreshInterval = typeof refresh === "number" ? refresh : (refresh ? 20000 : false);

  const queryKey = [userScope, url];

  const query = useQuery<T[], Error>({
    queryKey,
    queryFn: async () => {
      const response = await api.get<T[]>(url);
      return response.data;
    },
    enabled: !!url && userScope !== "anon",
    refetchInterval: refreshInterval,
    refetchIntervalInBackground: false,
    staleTime: 15000,
  });

  const load = useCallback(async () => {
    await query.refetch();
  }, [query]);

  return {
    data: query.data || [],
    loading: query.isLoading,
    refreshing: query.isFetching && !query.isLoading,
    error: query.error ? message(query.error) : "",
    load,
  };
}

/**
 * Tenant-scoped shared cache hook for object-shaped endpoints (DTOs, stats, details).
 * - SWR experience: renders cached record immediately, quiet background revalidation.
 * - Intelligent refresh: defaults to ~20s if enabled, pauses when inactive/hidden.
 * - keepPrevious: while a new URL loads (e.g. a different date range), keep
 *   showing the previous response instead of dropping to the loading state.
 *   Only for variants of the same view; never across different records.
 */
export function useApi<T>(url: string | null, refreshMs = 0, opts: { keepPrevious?: boolean } = {}) {
  const userScope = useCacheScope();

  const queryKey = [userScope, url || ""];

  const query = useQuery<T, Error>({
    queryKey,
    queryFn: async () => {
      if (!url) throw new Error("No URL provided");
      const response = await api.get<T>(url);
      return response.data;
    },
    enabled: !!url && userScope !== "anon",
    refetchInterval: refreshMs > 0 ? refreshMs : false,
    refetchIntervalInBackground: false,
    staleTime: 15000,
    placeholderData: opts.keepPrevious ? keepPreviousData : undefined,
  });

  const load = useCallback(async () => {
    if (!url) return;
    await query.refetch();
  }, [query, url]);

  return {
    data: query.data ?? null,
    loading: query.isLoading,
    refreshing: query.isFetching && !query.isLoading,
    /** True while `data` is the previous URL's response (keepPrevious). */
    placeholder: query.isPlaceholderData,
    error: query.error ? message(query.error) : "",
    updatedAt: query.dataUpdatedAt || null,
    load,
  };
}

export function useNow(intervalMs = 30000) {
  const [now, setNow] = useState(0);
  useEffect(() => {
    const tick = () => setNow(Date.now());
    const first = window.setTimeout(tick, 0);
    const timer = window.setInterval(tick, intervalMs);
    return () => { window.clearTimeout(first); window.clearInterval(timer); };
  }, [intervalMs]);
  return now;
}

/** Eases a figure up to its value on first load; later updates simply swap the number. */
export function CountUp({ value, format }: { value: number; format: (v: number) => string }) {
  const [shown, setShown] = useState<number | null>(null);
  const settled = useRef(false);
  useEffect(() => {
    if (settled.current) return;
    settled.current = true;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const start = performance.now();
    let frame = requestAnimationFrame(function tick(now) {
      const t = Math.min(1, (now - start) / 900);
      setShown(t === 1 ? null : Math.round(value * (1 - (1 - t) ** 4)));
      if (t < 1) frame = requestAnimationFrame(tick);
    });
    return () => cancelAnimationFrame(frame);
  }, [value]);
  return <>{format(shown ?? value)}</>;
}

/* ---------- primitives ---------- */
export type Tone = "blue" | "green" | "amber" | "red" | "slate";

const dotColor: Record<string, string> = {
  green: "bg-[var(--status-good)]", amber: "bg-[var(--status-warn)]", red: "bg-[var(--status-bad)]",
  slate: "bg-[var(--status-idle)]", blue: "bg-[var(--chart-1)]",
};

/** Tinted label. `dot` adds a status dot so state never rides on colour alone. */
export function Badge({ children, tone = "blue", dot = false, live = false }: { children: React.ReactNode; tone?: string; dot?: boolean; live?: boolean }) {
  const color =
    tone === "green" ? "text-emerald-700 bg-emerald-50 border-emerald-200" :
    tone === "red" ? "text-red-700 bg-red-50 border-red-200" :
    tone === "amber" ? "text-amber-700 bg-amber-50 border-amber-200" :
    tone === "slate" ? "text-[var(--fg-muted)] bg-[var(--fill)] border-[var(--border)]" :
    "text-[var(--accent-text)] bg-blue-50 border-blue-200";
  return (
    <span className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-md border px-1.5 py-0.5 text-[11.5px] font-medium leading-4 ${color}`}>
      {dot && <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${live ? "live-dot" : ""} ${dotColor[tone] || dotColor.slate}`} aria-hidden />}
      {children}
    </span>
  );
}

/** Bare status dot + label, for dense rows where a full badge is too loud. */
export function StatusDot({ tone = "slate", children }: { tone?: string; children: React.ReactNode }) {
  return (
    <span className="inline-flex items-center gap-2 whitespace-nowrap text-[13px] text-[var(--fg-body)]">
      <span className={`h-2 w-2 shrink-0 rounded-full ${dotColor[tone] || dotColor.slate}`} aria-hidden />
      {children}
    </span>
  );
}

export function Notice({ text }: { text: string }) {
  return text ? <p role="status" className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-xs font-medium text-amber-800">{text}</p> : null;
}

export function ErrorNotice({ text }: { text: string }) {
  return text ? <p role="alert" className="rounded-lg border border-red-200 bg-red-50 p-3 text-xs font-medium text-red-700">{text}</p> : null;
}

export function PageHeader({ title, description, action, meta }: { title: React.ReactNode; description?: string; action?: React.ReactNode; meta?: React.ReactNode }) {
  return (
    <div className="mb-6 flex flex-col items-start justify-between gap-4 sm:flex-row sm:items-end">
      <div className="min-w-0">
        <h1 className="text-[1.65rem] font-bold leading-tight tracking-[-0.02em] text-[var(--fg)]">{title}</h1>
        {description && <p className="mt-1.5 max-w-2xl text-sm leading-relaxed text-[var(--fg-muted)]">{description}</p>}
        {meta && <div className="mt-2.5 flex flex-wrap items-center gap-x-3 gap-y-1.5 text-xs text-[var(--fg-muted)]">{meta}</div>}
      </div>
      {action && <div className="flex shrink-0 flex-wrap items-center gap-2">{action}</div>}
    </div>
  );
}

/**
 * Label · value · context. The icon tile carries the tone so the number stays
 * neutral ink; `href` turns the whole card into a drill-down; `children` hosts
 * a meter or breakdown under the figure.
 */
export function StatCard({ label, value, icon, hintText, hint, tone, href, children, loading = false }: {
  label: string; value: React.ReactNode; icon?: React.ReactNode; hintText?: string; hint?: React.ReactNode;
  tone?: "default" | "green" | "amber" | "red"; href?: string; children?: React.ReactNode;
  /** First load only: a placeholder in the value's line box, so nothing shifts when it arrives. */
  loading?: boolean;
}) {
  const iconColor =
    tone === "green" ? "bg-emerald-50 text-emerald-700" :
    tone === "amber" ? "bg-amber-50 text-amber-700" :
    tone === "red" ? "bg-red-50 text-red-700" :
    "bg-[var(--fill)] text-[var(--fg-muted)]";
  const body = (
    <>
      <div className="flex items-start justify-between gap-3">
        <p className="text-[13px] font-medium text-[var(--fg-muted)]">{label}</p>
        {icon && <span className={`-mr-1 -mt-1 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg [&_svg]:h-4 [&_svg]:w-4 ${iconColor}`} aria-hidden>{icon}</span>}
      </div>
      <p className="mt-1.5 truncate text-[1.7rem] font-semibold leading-tight tracking-tight text-[var(--fg)]">
        {loading ? <span aria-hidden className="inline-block h-[0.8em] w-24 animate-pulse rounded bg-[var(--fill)] align-middle motion-reduce:animate-none" /> : value}
        {loading && <span className="sr-only">Loading</span>}
      </p>
      {(hint || hintText) && <div className="mt-1.5 text-xs leading-relaxed text-[var(--fg-muted)]">{hint || hintText}</div>}
      {children && <div className="mt-4">{children}</div>}
    </>
  );
  return href ? (
    <Link href={href} className={`${panel} panel-lift group block p-5`}>{body}<NavigationPending /></Link>
  ) : (
    <section className={`${panel} p-5`}>{body}</section>
  );
}

/** Standard content card: titled header row with an optional action, then body. */
export function Card({ title, description, action, children, className = "", bodyClassName = "p-5", id }: {
  title?: React.ReactNode; description?: React.ReactNode; action?: React.ReactNode; children: React.ReactNode;
  className?: string; bodyClassName?: string; id?: string;
}) {
  return (
    <section id={id} className={`${panel} flex min-w-0 flex-col ${className}`}>
      {(title || action) && (
        <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-2.5 border-b border-[var(--border)] px-5 py-4">
          <div className="min-w-0 flex-1 basis-40">
            {title && <h2 className="text-[14.5px] font-semibold text-[var(--fg)]">{title}</h2>}
            {description && <p className="mt-0.5 text-xs leading-relaxed text-[var(--fg-muted)]">{description}</p>}
          </div>
          {action && <div className="shrink-0">{action}</div>}
        </div>
      )}
      <div className={`min-w-0 flex-1 ${bodyClassName}`}>{children}</div>
    </section>
  );
}

/** Quiet text link with an arrow, for "view all" style card actions. */
export function CardLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <Link href={href} className="group inline-flex items-center gap-1 text-xs font-medium text-[var(--accent-text)] hover:underline max-sm:min-h-11">
      {children}<ArrowRight className="h-3.5 w-3.5 transition-transform group-hover:translate-x-0.5 motion-reduce:transition-none" aria-hidden />
      <NavigationPending />
    </Link>
  );
}

export function Skeleton({ className = "" }: { className?: string }) {
  return <span aria-hidden className={`block animate-pulse rounded bg-[var(--fill)] motion-reduce:animate-none ${className}`} />;
}

/** Underline tabs for switching sections inside a detail page. */
export function Tabs<T extends string>({ tabs, value, onChange, label }: { tabs: readonly T[] | { key: T; label: string; count?: number }[]; value: T; onChange: (t: T) => void; label: string }) {
  const items = (tabs as (T | { key: T; label: string; count?: number })[]).map((t) => typeof t === "string" ? { key: t, label: t as string, count: undefined } : t);
  return (
    <div role="tablist" aria-label={label} className="flex gap-1 overflow-x-auto border-b border-[var(--border)]">
      {items.map((t) => {
        const active = t.key === value;
        return (
          <button key={t.key} role="tab" aria-selected={active} onClick={() => onChange(t.key)}
            className={`relative whitespace-nowrap px-3 pb-2.5 pt-2 text-[13px] font-medium max-sm:min-h-11 transition-colors ${active ? "text-[var(--fg)]" : "text-[var(--fg-muted)] hover:text-[var(--fg)]"}`}>
            {t.label}
            {t.count !== undefined && <span className="num ml-1.5 rounded bg-[var(--fill)] px-1.5 py-px text-[11px] text-[var(--fg-muted)]">{t.count}</span>}
            {active && <span className="absolute inset-x-2 bottom-0 h-0.5 rounded-full bg-[var(--primary)]" aria-hidden />}
          </button>
        );
      })}
    </div>
  );
}

export function EmptyState({ title, description, action, icon }: { title: string; description?: string; action?: React.ReactNode; icon?: React.ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-14 text-center">
      <span className="mb-1 flex h-10 w-10 items-center justify-center rounded-full bg-[var(--fill)] text-[var(--fg-faint)] [&_svg]:h-5 [&_svg]:w-5">{icon || <Tray />}</span>
      <p className="text-sm font-semibold text-[var(--fg-body)]">{title}</p>
      {description && <p className="max-w-sm text-xs text-[var(--fg-muted)]">{description}</p>}
      {action}
    </div>
  );
}

/** `loading` hides counts until the first load, so "0" never stands in for "not loaded yet". */
export function FilterTabs({ options, value, onChange, loading = false }: { options: { key: string; label: string; count?: number }[]; value: string; onChange: (k: string) => void; loading?: boolean }) {
  return (
    <div className="flex flex-wrap gap-1 rounded-lg border border-[var(--border)] bg-[var(--subtle)] p-1" role="tablist">
      {options.map((o) => (
        <button
          key={o.key}
          role="tab"
          aria-selected={value === o.key}
          onClick={() => onChange(o.key)}
          className={`rounded-md px-3 py-1.5 text-xs font-semibold transition max-sm:min-h-11 ${value === o.key ? "bg-[var(--surface)] text-[var(--fg)] shadow-sm" : "text-[var(--fg-muted)] hover:text-[var(--fg)]"}`}
        >
          {o.label}{!loading && o.count !== undefined && <span className="num ml-1.5 text-[var(--fg-muted)]">{o.count}</span>}
        </button>
      ))}
    </div>
  );
}

/* ---------- table ---------- */
export interface Column<T> {
  label: string;
  render: (row: T) => React.ReactNode;
  className?: string;
  align?: "right";
  /** Drop low-priority columns on small screens instead of forcing a wide scroll. */
  hideBelow?: "sm" | "md" | "lg";
}

const hideClass = { sm: "hidden sm:table-cell", md: "hidden md:table-cell", lg: "hidden lg:table-cell" } as const;
const pagerButton = "inline-flex items-center justify-center rounded-md border border-[var(--border-strong)] p-1.5 disabled:opacity-30 max-sm:h-11 max-sm:w-11";
/* Row actions sit inside cells as small inline controls; phone cards give each one a full touch target. */
const cardTargets = "[&_a]:inline-flex [&_a]:min-h-11 [&_a]:items-center [&_button]:inline-flex [&_button]:min-h-11 [&_button]:min-w-11 [&_button]:items-center [&_button]:justify-center";

const PHONE_QUERY = "(max-width: 639px)";
function subscribePhone(cb: () => void) {
  const query = window.matchMedia(PHONE_QUERY);
  query.addEventListener("change", cb);
  return () => query.removeEventListener("change", cb);
}
/** Below `sm`. Renders one layout at a time, so screen readers never get the same rows twice. */
export function useIsPhone() {
  return useSyncExternalStore(subscribePhone, () => window.matchMedia(PHONE_QUERY).matches, () => false);
}
const isActionColumn = <T,>(c: Column<T>) => !c.label || c.label === "Actions";

/**
 * Client-side table: search + pagination over the rows it is given. For
 * server-paged data pass `searchable={false} paginated={false}` and render a
 * <Pager>; pass `bare` when the table sits inside another card.
 */
export function Table<T>({ rows, columns, loading = false, emptyTitle = "No records found.", emptyDescription, emptyIcon, searchable = true, paginated = true, bare = false, toolbar, rowKey, searchPlaceholder = "Search…", selection }: {
  emptyTitle?: string; emptyDescription?: string; emptyIcon?: React.ReactNode; rows: T[]; columns: Column<T>[]; loading?: boolean;
  searchable?: boolean; paginated?: boolean; bare?: boolean; toolbar?: React.ReactNode; rowKey?: (row: T) => React.Key; searchPlaceholder?: string;
  /** Leading checkboxes (needs rowKey). The header box selects every row matching the search. */
  selection?: { selected: ReadonlySet<React.Key>; onChange: (next: Set<React.Key>) => void };
}) {
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  // While searching, serialise rows once per data change rather than per
  // keystroke, and filter against a deferred query so typing stays responsive.
  // Nothing is serialised when the box is empty.
  const query = useDeferredValue(search).toLowerCase();
  const searching = searchable && query !== "";
  const haystack = useMemo(() => searching ? rows.map(r => JSON.stringify(r).toLowerCase()) : null, [rows, searching]);
  const filtered = useMemo(() => haystack ? rows.filter((_, i) => haystack[i].includes(query)) : rows, [rows, haystack, query]);
  const pages = paginated ? Math.max(1, Math.ceil(filtered.length / size)) : 1;
  const current = Math.min(page, pages);
  const visible = paginated ? filtered.slice((current - 1) * size, current * size) : filtered;
  const cell = (c: Column<T>) => `${c.hideBelow ? hideClass[c.hideBelow] : ""} ${c.className || ""}`;
  const keyOf = (r: T, i: number) => rowKey ? rowKey(r) : i;
  const picked = selection ? filtered.filter((r, i) => selection.selected.has(keyOf(r, i))).length : 0;
  const toggleAll = () => {
    if (!selection) return;
    const next = new Set(selection.selected);
    filtered.forEach((r, i) => picked === filtered.length ? next.delete(keyOf(r, i)) : next.add(keyOf(r, i)));
    selection.onChange(next);
  };
  const toggleRow = (k: React.Key) => {
    if (!selection) return;
    const next = new Set(selection.selected);
    if (next.has(k)) next.delete(k); else next.add(k);
    selection.onChange(next);
  };
  const checkbox = "h-4 w-4 cursor-pointer rounded border-[var(--border-strong)] accent-[var(--accent-text)]";
  const spanAll = columns.length + (selection ? 1 : 0);
  const phone = useIsPhone();
  const [titleColumn, ...detailColumns] = columns.filter((c) => !isActionColumn(c));
  const actionColumns = columns.filter(isActionColumn);
  return (
    <section className={bare ? "min-w-0" : `${panel} overflow-hidden`} aria-busy={loading}>
      {(searchable || toolbar) && (
        <div className="flex flex-col gap-3 border-b border-[var(--border)] px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex min-w-0 flex-wrap items-center gap-2">{toolbar}</div>
          {searchable && (
            <div className="relative w-full sm:w-auto">
              <MagnifyingGlass className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-[var(--fg-muted)]" />
              <input aria-label="Search records" placeholder={searchPlaceholder} value={search}
                onChange={e => { setSearch(e.target.value); setPage(1); }}
                className="w-full rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] py-2 pl-9 pr-3 text-xs text-[var(--fg)] outline-none focus:border-[var(--ring)] focus:ring-2 focus:ring-[var(--ring)]/15 max-sm:min-h-11 sm:w-60" />
            </div>
          )}
        </div>
      )}
      {phone ? (
        loading && !rows.length ? <ListSkeleton rows={5} /> : !filtered.length ? (
          <EmptyState icon={emptyIcon} title={search ? "No matching records" : emptyTitle} description={search ? "Try a different search." : emptyDescription} />
        ) : (
          <ul className="divide-y divide-[var(--border-soft)]">
            {visible.map((r, i) => (
              <li key={keyOf(r, i)} className={`px-4 py-3.5 ${selection?.selected.has(keyOf(r, i)) ? "bg-[var(--chart-1-wash)]" : ""}`}>
                <div className="flex items-center gap-2">
                  {selection && (
                    <label className="-ml-2.5 flex h-11 w-11 shrink-0 items-center justify-center">
                      <input type="checkbox" aria-label="Select row" className={checkbox} checked={selection.selected.has(keyOf(r, i))} onChange={() => toggleRow(keyOf(r, i))} />
                    </label>
                  )}
                  <div className={`min-w-0 flex-1 text-sm font-medium text-[var(--fg)] [overflow-wrap:anywhere] ${cardTargets}`}>{titleColumn?.render(r)}</div>
                  {actionColumns.map((c, j) => <div key={j} className={`flex shrink-0 items-center ${cardTargets}`}>{c.render(r)}</div>)}
                </div>
                {detailColumns.length > 0 && (
                  <dl className="mt-2 space-y-1.5">
                    {detailColumns.map((c) => (
                      <div key={c.label} className="flex items-baseline justify-between gap-4 text-[13px]">
                        <dt className="shrink-0 text-[var(--fg-muted)]">{c.label}</dt>
                        <dd className={`min-w-0 text-right text-[var(--fg-body)] [overflow-wrap:anywhere] ${c.align === "right" ? "num" : ""} ${cardTargets}`}>{c.render(r)}</dd>
                      </div>
                    ))}
                  </dl>
                )}
              </li>
            ))}
          </ul>
        )
      ) : (
      <div className="overflow-x-auto">
        <table className="w-full whitespace-nowrap text-left text-sm">
          <thead className="border-b border-[var(--border)] bg-[var(--subtle)] text-xs text-[var(--fg-muted)]">
            <tr>
              {selection && (
                <th scope="col" className="w-10 py-2.5 pl-4 pr-0">
                  <input type="checkbox" aria-label="Select all matching rows" className={checkbox} disabled={!filtered.length}
                    checked={filtered.length > 0 && picked === filtered.length}
                    ref={(el) => { if (el) el.indeterminate = picked > 0 && picked < filtered.length; }}
                    onChange={toggleAll} />
                </th>
              )}
              {columns.map((c, i) => <th key={i} scope="col" className={`px-4 py-2.5 font-medium ${c.align === "right" ? "text-right" : ""} ${cell(c)}`}>{c.label}</th>)}
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--border-soft)]">
            {loading && !rows.length && <TableSkeletonRows columns={spanAll} cellClassName={(i) => selection ? (i === 0 ? "" : cell(columns[i - 1])) : cell(columns[i])} />}
            {!(loading && !rows.length) && visible.map((r, i) => (
              <tr key={keyOf(r, i)} className={`transition-colors hover:bg-[var(--subtle)] ${selection?.selected.has(keyOf(r, i)) ? "bg-[var(--chart-1-wash)]" : ""}`}>
                {selection && (
                  <td className="w-10 py-3 pl-4 pr-0">
                    <input type="checkbox" aria-label="Select row" className={checkbox} checked={selection.selected.has(keyOf(r, i))} onChange={() => toggleRow(keyOf(r, i))} />
                  </td>
                )}
                {columns.map((c, j) => <td key={j} className={`px-4 py-3 text-[var(--fg-body)] ${c.align === "right" ? "num text-right" : ""} ${cell(c)}`}>{c.render(r)}</td>)}
              </tr>
            ))}
            {!loading && !filtered.length && (
              <tr><td colSpan={spanAll} className="py-12 text-center text-sm text-[var(--fg-muted)]"><EmptyState icon={emptyIcon} title={search ? "No matching records" : emptyTitle} description={search ? "Try a different search." : emptyDescription} /></td></tr>
            )}
          </tbody>
        </table>
      </div>
      )}
      {paginated && (
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-[var(--border)] px-4 py-3 text-xs text-[var(--fg-muted)]">
          <span>{loading && !rows.length ? "Loading records…" : `Showing ${filtered.length ? (current - 1) * size + 1 : 0} to ${Math.min(current * size, filtered.length)} of ${filtered.length} results`}</span>
          <div className="flex items-center gap-3">
            <select aria-label="Rows per page" value={size} onChange={e => { setSize(Number(e.target.value)); setPage(1); }}
              className="rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] px-2 py-1.5 text-xs max-sm:min-h-11">
              {[10, 25, 50].map(n => <option key={n} value={n}>{n} per page</option>)}
            </select>
            <button disabled={current === 1} onClick={() => setPage(current - 1)} aria-label="Previous page" className={pagerButton}><CaretLeft className="h-3.5 w-3.5" /></button>
            <span className="tabular-nums">{current}/{pages}</span>
            <button disabled={current === pages} onClick={() => setPage(current + 1)} aria-label="Next page" className={pagerButton}><CaretRight className="h-3.5 w-3.5" /></button>
          </div>
        </div>
      )}
    </section>
  );
}

/** Pager for server-paged endpoints (0-based pages, as Spring returns them). */
export function Pager({ page, totalPages, totalElements, onPage, busy }: { page: number; totalPages: number; totalElements?: number; onPage: (p: number) => void; busy?: boolean }) {
  if (totalPages <= 1 && totalElements === undefined) return null;
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t border-[var(--border)] px-4 py-3 text-xs text-[var(--fg-muted)]">
      <span className="num">{totalElements !== undefined ? `${totalElements.toLocaleString()} record${totalElements === 1 ? "" : "s"}` : ""}</span>
      {totalPages > 1 && (
        <div className="flex items-center gap-3">
          <button disabled={page <= 0 || busy} onClick={() => onPage(page - 1)} aria-label="Previous page" className={pagerButton}><CaretLeft className="h-3.5 w-3.5" /></button>
          <span className="num">Page {page + 1} of {totalPages}</span>
          <button disabled={page >= totalPages - 1 || busy} onClick={() => onPage(page + 1)} aria-label="Next page" className={pagerButton}><CaretRight className="h-3.5 w-3.5" /></button>
        </div>
      )}
    </div>
  );
}

/** Compact segmented control for filters inside toolbars. */
export function Segmented<T extends string>({ options, value, onChange, label }: { options: { key: T; label: string; count?: number }[]; value: T; onChange: (k: T) => void; label: string }) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex max-w-full overflow-x-auto rounded-lg border border-[var(--border)] bg-[var(--subtle)] p-0.5">
      {options.map((o) => {
        const active = o.key === value;
        return (
          <button key={o.key} role="radio" aria-checked={active} onClick={() => onChange(o.key)}
            className={`whitespace-nowrap rounded-md px-2.5 py-1 text-xs font-medium transition-colors max-sm:min-h-10 ${active ? "bg-[var(--surface)] text-[var(--fg)] shadow-sm" : "text-[var(--fg-muted)] hover:text-[var(--fg)]"}`}>
            {o.label}{o.count !== undefined && <span className="num ml-1.5 text-[var(--fg-faint)]">{o.count}</span>}
          </button>
        );
      })}
    </div>
  );
}

/** Small "definition list" row used in detail panels. */
export function Field({ label, children, mono = false }: { label: string; children: React.ReactNode; mono?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-4 py-2.5">
      <dt className="shrink-0 text-[13px] text-[var(--fg-muted)]">{label}</dt>
      <dd className={`min-w-0 truncate text-right text-[13px] font-medium text-[var(--fg)] ${mono ? "font-mono text-xs" : ""}`}>{children}</dd>
    </div>
  );
}

/* ---------- formatters ---------- */
/** Compact 24h timestamp, e.g. "Oct 29, 21:42"; the year shows only when it isn't the current one. */
export function date(value: string | null) {
  if (!value) return "—";
  const d = new Date(value);
  if (isNaN(d.getTime())) return "—";
  return d.toLocaleString(undefined, {
    month: "short", day: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23",
    year: d.getFullYear() === new Date().getFullYear() ? undefined : "numeric",
  });
}
/** Axis-friendly bytes: 0, 500 MB, 1.5 GB, 40 GB. */
export function bytesTick(value: number) {
  if (!value) return "0";
  const unit = Math.min(Math.floor(Math.log(value) / Math.log(1024)), 4);
  return `${Number((value / 1024 ** unit).toFixed(1))} ${["B", "KB", "MB", "GB", "TB"][unit]}`;
}
export function bytes(value: number) { if (!value) return "0 B"; return value < 1048576 ? `${(value / 1024).toFixed(1)} KB` : value < 1073741824 ? `${(value / 1048576).toFixed(2)} MB` : `${(value / 1073741824).toFixed(2)} GB`; }
export function remaining(value: number | null) { if (value === null) return "Not started"; if (value === 0) return "Expired"; return `${Math.floor(value / 86400)}d ${Math.floor(value % 86400 / 3600)}h ${Math.floor(value % 3600 / 60)}m`; }
export function ugx(v: number | string | null | undefined) { return `UGX ${Number(v || 0).toLocaleString()}`; }
/** 1,284 / 12.9K / 4.2M — for figures where precision isn't the point. */
export function compactNumber(v: number) { return new Intl.NumberFormat(undefined, { notation: "compact", maximumFractionDigits: 1 }).format(v); }
export function shortDate(value: string | null) { return value ? new Date(value).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" }) : "—"; }
/** "just now", "5 min ago", "3 h ago", "2 d ago", then a date. */
export function relativeTime(value: string | null, now = Date.now()) {
  if (!value) return "Never";
  const s = Math.round((now - new Date(value).getTime()) / 1000);
  if (s < 45) return "just now";
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  if (s < 86400 * 14) return `${Math.round(s / 86400)} d ago`;
  return shortDate(value);
}
/** "SUSPENDED" → "Suspended", "OPEN_STALE" → "Open stale". */
export function humanize(value: string | null | undefined) {
  if (!value) return "Unknown";
  const t = value.replace(/_/g, " ").toLowerCase();
  return t.charAt(0).toUpperCase() + t.slice(1);
}

/* ---------- create / edit form scaffolding ----------
   Shared so every "new" page reads the same: back link, titled sections with
   an icon, and one action row. */

export function BackLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <Link href={href} className="inline-flex items-center gap-1.5 text-xs font-semibold text-[var(--fg-muted)] transition hover:text-[var(--fg)] max-sm:min-h-11">
      <ArrowLeft size={13} aria-hidden />{children}
    </Link>
  );
}

export function FormHeader({ title, description, backHref, backLabel }: {
  title: string; description?: string; backHref: string; backLabel: string;
}) {
  return (
    <div>
      <BackLink href={backHref}>{backLabel}</BackLink>
      <h1 className="mt-3 text-[1.65rem] font-bold leading-tight tracking-[-0.02em] text-[var(--fg)]">{title}</h1>
      {description && <p className="mt-2 max-w-2xl text-sm leading-relaxed text-[var(--fg-muted)]">{description}</p>}
    </div>
  );
}

export function FormSection({ icon, title, hint, children }: {
  icon?: React.ReactNode; title: string; hint?: string; children: React.ReactNode;
}) {
  return (
    <section className={panel}>
      <div className="flex items-start gap-3 border-b border-[var(--border)] px-5 py-4">
        {icon && (
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-blue-100 bg-blue-50 text-[var(--accent-text)]">
            {icon}
          </span>
        )}
        <div className="min-w-0">
          <h2 className="text-sm font-semibold text-[var(--fg)]">{title}</h2>
          {hint && <p className="mt-0.5 text-xs leading-relaxed text-[var(--fg-muted)]">{hint}</p>}
        </div>
      </div>
      <div className="space-y-5 p-5">{children}</div>
    </section>
  );
}

/* Stacks full-width on phones with the primary action on top (thumb reach),
   and sits right-aligned on a single row from sm up. */
export function FormActions({ busy, busyLabel, submitLabel, cancelHref }: {
  busy?: boolean; busyLabel: string; submitLabel: string; cancelHref: string;
}) {
  return (
    <div className="flex flex-col-reverse gap-3 pb-2 sm:flex-row sm:items-center sm:justify-end">
      <Link href={cancelHref} className={`${buttonGhost} min-h-[44px] justify-center py-3 sm:min-h-0 sm:py-2.5`}>Cancel</Link>
      <button type="submit" disabled={busy} className={`${button} min-h-[44px] justify-center py-3 sm:min-h-0 sm:py-2.5`}>
        <BusyLabel busy={!!busy} label={busyLabel}>{submitLabel}</BusyLabel>
      </button>
    </div>
  );
}

/* Two-column shell: the form keeps a readable measure while the aside carries
   a live summary, instead of the form stretching the full page width. */
export function FormLayout({ children, aside }: { children: React.ReactNode; aside?: React.ReactNode }) {
  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_19rem] lg:items-start lg:gap-8">
      <div className="min-w-0 space-y-5">{children}</div>
      {aside && <div className="space-y-4 lg:sticky lg:top-2">{aside}</div>}
    </div>
  );
}

export function SummaryCard({ title, rows, footer }: {
  title: string;
  rows: { label: string; value: React.ReactNode }[];
  footer?: React.ReactNode;
}) {
  return (
    <section className={panel}>
      <h2 className="border-b border-[var(--border)] px-5 py-3.5 text-xs font-semibold uppercase tracking-wide text-[var(--fg-muted)]">{title}</h2>
      <dl className="divide-y divide-[var(--border-soft)]">
        {rows.map((r) => (
          <div key={r.label} className="flex items-baseline justify-between gap-3 px-5 py-3">
            <dt className="text-xs text-[var(--fg-muted)]">{r.label}</dt>
            <dd className="min-w-0 truncate text-right text-sm font-semibold text-[var(--fg)]">{r.value}</dd>
          </div>
        ))}
      </dl>
      {footer && <div className="border-t border-[var(--border)] px-5 py-3.5 text-xs leading-relaxed text-[var(--fg-muted)]">{footer}</div>}
    </section>
  );
}

export function PageContainer({ children, width = "default" }: { children: React.ReactNode; width?: "narrow" | "default" | "wide" }) {
  const w = width === "narrow" ? "max-w-3xl" : width === "wide" ? "max-w-[1440px]" : "max-w-6xl";
  return <div className={`mx-auto ${w} space-y-6`}>{children}</div>;
}
