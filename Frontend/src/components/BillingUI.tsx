"use client";
import React, { useCallback, useEffect, useState, useRef } from "react";
import api from "@/lib/api";
import Link from "next/link";
import { ArrowLeft, ArrowRight, CaretLeft, CaretRight, MagnifyingGlass, Tray } from "@/components/icons";

/* ---------- design tokens ---------- */
export const field = "mt-1.5 min-h-11 w-full rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] px-3 py-2.5 text-sm text-[var(--fg)] placeholder:text-[var(--fg-muted)] outline-none transition-[border-color,box-shadow] focus:border-blue-600 focus:ring-2 focus:ring-blue-600/15 disabled:cursor-not-allowed disabled:opacity-60";
export const button = "inline-flex min-h-10 items-center justify-center gap-1.5 rounded-lg bg-blue-700 px-4 py-2.5 text-xs font-semibold text-white transition-[background-color,transform,opacity] hover:bg-blue-600 active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-50 disabled:active:scale-100";
export const buttonGhost = "inline-flex min-h-10 items-center justify-center gap-1.5 rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] px-4 py-2.5 text-xs font-semibold text-[var(--fg-body)] transition-[background-color,transform,opacity] hover:bg-[var(--subtle)] active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-50 disabled:active:scale-100";
export const panel = "panel-shadow rounded-xl border border-[var(--border)] bg-[var(--surface)]";
export const label = "block text-xs font-semibold text-[var(--fg-muted)]";
export const hint = "mt-1.5 text-xs text-[var(--fg-muted)]";

export function message(error: unknown) {
  const e = error as { response?: { data?: { message?: string; detail?: string } } };
  return e.response?.data?.message || e.response?.data?.detail || "Request failed. Please retry.";
}

export function useResource<T>(url: string, refresh = false) {
  const [data, setData] = useState<T[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [refreshing, setRefreshing] = useState(false);
  const latestRequest = useRef(0);
  const load = useCallback(async () => {
    const requestId = ++latestRequest.current;
    setRefreshing(true);
    try {
      const response = await api.get<T[]>(url);
      if (requestId === latestRequest.current) { setData(response.data); setError(""); }
    } catch (e) {
      if (requestId === latestRequest.current) setError(message(e));
    } finally {
      if (requestId === latestRequest.current) { setLoading(false); setRefreshing(false); }
    }
  }, [url]);
  useEffect(() => {
    const initial = window.setTimeout(load, 0);
    const timer = refresh ? window.setInterval(load, 30000) : undefined;
    return () => { window.clearTimeout(initial); window.clearInterval(timer); latestRequest.current += 1; };
  }, [load, refresh]);
  return { data, loading, refreshing, error, load };
}

/**
 * Object-shaped endpoints (overview DTOs, detail records). `data` stays null
 * until the first success, so a failed load reads as "unavailable", never as
 * zeros; a failed refresh keeps the last good payload and reports `error`.
 */
export function useApi<T>(url: string | null, refreshMs = 0) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(!!url);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const latest = useRef(0);
  const load = useCallback(async () => {
    if (!url) return;
    const id = ++latest.current;
    setLoading(true);
    try {
      const response = await api.get<T>(url);
      if (id === latest.current) { setData(response.data); setError(""); setUpdatedAt(Date.now()); }
    } catch (e) {
      if (id === latest.current) setError(message(e));
    } finally {
      if (id === latest.current) setLoading(false);
    }
  }, [url]);
  useEffect(() => {
    const initial = window.setTimeout(load, 0);
    const timer = refreshMs ? window.setInterval(() => { if (document.visibilityState === "visible") void load(); }, refreshMs) : undefined;
    return () => { window.clearTimeout(initial); window.clearInterval(timer); latest.current += 1; };
  }, [load, refreshMs]);
  return { data, error, loading, updatedAt, load };
}

/** Wall clock that ticks on an interval, so "x min ago" stays honest without impure renders. */
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

/* ---------- primitives ---------- */
export type Tone = "blue" | "green" | "amber" | "red" | "slate";

const dotColor: Record<string, string> = {
  green: "bg-[var(--status-good)]", amber: "bg-[var(--status-warn)]", red: "bg-[var(--status-bad)]",
  slate: "bg-[var(--status-idle)]", blue: "bg-[var(--chart-1)]",
};

/** Tinted label. `dot` adds a status dot so state never rides on colour alone. */
export function Badge({ children, tone = "blue", dot = false }: { children: React.ReactNode; tone?: string; dot?: boolean }) {
  const color =
    tone === "green" ? "text-emerald-700 bg-emerald-50 border-emerald-200" :
    tone === "red" ? "text-red-700 bg-red-50 border-red-200" :
    tone === "amber" ? "text-amber-700 bg-amber-50 border-amber-200" :
    tone === "slate" ? "text-[var(--fg-muted)] bg-[var(--fill)] border-[var(--border)]" :
    "text-[var(--accent-text)] bg-blue-50 border-blue-200";
  return (
    <span className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-md border px-1.5 py-0.5 text-[11.5px] font-medium leading-4 ${color}`}>
      {dot && <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${dotColor[tone] || dotColor.slate}`} aria-hidden />}
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
        <h1 className="text-[1.45rem] font-semibold leading-tight tracking-tight text-[var(--fg)]">{title}</h1>
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
export function StatCard({ label, value, icon, hintText, hint, tone, href, children }: {
  label: string; value: React.ReactNode; icon?: React.ReactNode; hintText?: string; hint?: React.ReactNode;
  tone?: "default" | "green" | "amber" | "red"; href?: string; children?: React.ReactNode;
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
      <p className="mt-1.5 truncate text-[1.7rem] font-semibold leading-tight tracking-tight text-[var(--fg)]">{value}</p>
      {(hint || hintText) && <div className="mt-1.5 text-xs leading-relaxed text-[var(--fg-muted)]">{hint || hintText}</div>}
      {children && <div className="mt-4">{children}</div>}
    </>
  );
  return href ? (
    <Link href={href} className={`${panel} group block p-5 transition-colors hover:border-[var(--border-strong)]`}>{body}</Link>
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
    <Link href={href} className="group inline-flex items-center gap-1 text-xs font-medium text-[var(--accent-text)] hover:underline">
      {children}<ArrowRight className="h-3.5 w-3.5 transition-transform group-hover:translate-x-0.5 motion-reduce:transition-none" aria-hidden />
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
            className={`relative whitespace-nowrap px-3 pb-2.5 pt-2 text-[13px] font-medium transition-colors ${active ? "text-[var(--fg)]" : "text-[var(--fg-muted)] hover:text-[var(--fg)]"}`}>
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

export function FilterTabs({ options, value, onChange }: { options: { key: string; label: string; count?: number }[]; value: string; onChange: (k: string) => void }) {
  return (
    <div className="flex flex-wrap gap-1 rounded-lg border border-[var(--border)] bg-[var(--subtle)] p-1" role="tablist">
      {options.map((o) => (
        <button
          key={o.key}
          role="tab"
          aria-selected={value === o.key}
          onClick={() => onChange(o.key)}
          className={`rounded-md px-3 py-1.5 text-xs font-semibold transition ${value === o.key ? "bg-[var(--surface)] text-[var(--fg)] shadow-sm" : "text-[var(--fg-muted)] hover:text-[var(--fg)]"}`}
        >
          {o.label}{o.count !== undefined && <span className="num ml-1.5 text-[var(--fg-muted)]">{o.count}</span>}
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

/**
 * Client-side table: search + pagination over the rows it is given. For
 * server-paged data pass `searchable={false} paginated={false}` and render a
 * <Pager>; pass `bare` when the table sits inside another card.
 */
export function Table<T>({ rows, columns, loading = false, emptyTitle = "No records found.", emptyDescription, emptyIcon, searchable = true, paginated = true, bare = false, toolbar, rowKey, searchPlaceholder = "Search…" }: {
  emptyTitle?: string; emptyDescription?: string; emptyIcon?: React.ReactNode; rows: T[]; columns: Column<T>[]; loading?: boolean;
  searchable?: boolean; paginated?: boolean; bare?: boolean; toolbar?: React.ReactNode; rowKey?: (row: T) => React.Key; searchPlaceholder?: string;
}) {
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const filtered = searchable && search ? rows.filter(r => JSON.stringify(r).toLowerCase().includes(search.toLowerCase())) : rows;
  const pages = paginated ? Math.max(1, Math.ceil(filtered.length / size)) : 1;
  const current = Math.min(page, pages);
  const visible = paginated ? filtered.slice((current - 1) * size, current * size) : filtered;
  const cell = (c: Column<T>) => `${c.hideBelow ? hideClass[c.hideBelow] : ""} ${c.className || ""}`;
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
                className="w-full rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] py-2 pl-9 pr-3 text-xs text-[var(--fg)] outline-none focus:border-[var(--ring)] focus:ring-2 focus:ring-[var(--ring)]/15 sm:w-60" />
            </div>
          )}
        </div>
      )}
      <div className="overflow-x-auto">
        <table className="w-full whitespace-nowrap text-left text-sm">
          <thead className="border-b border-[var(--border)] bg-[var(--subtle)] text-xs text-[var(--fg-muted)]">
            <tr>{columns.map((c, i) => <th key={i} scope="col" className={`px-4 py-2.5 font-medium ${c.align === "right" ? "text-right" : ""} ${cell(c)}`}>{c.label}</th>)}</tr>
          </thead>
          <tbody className="divide-y divide-[var(--border-soft)]">
            {loading && !rows.length && Array.from({ length: 6 }, (_, row) => (
              <tr key={`skeleton-${row}`} aria-hidden="true">
                {columns.map((c, column) => <td key={column} className={`px-4 py-3.5 ${cell(c)}`}><span className="block h-3.5 max-w-36 animate-pulse rounded bg-[var(--fill)] motion-reduce:animate-none" style={{ width: `${58 + ((row + column) % 4) * 11}%` }} /></td>)}
              </tr>
            ))}
            {!(loading && !rows.length) && visible.map((r, i) => (
              <tr key={rowKey ? rowKey(r) : i} className="transition-colors hover:bg-[var(--subtle)]">
                {columns.map((c, j) => <td key={j} className={`px-4 py-3 text-[var(--fg-body)] ${c.align === "right" ? "num text-right" : ""} ${cell(c)}`}>{c.render(r)}</td>)}
              </tr>
            ))}
            {!loading && !filtered.length && (
              <tr><td colSpan={columns.length} className="py-12 text-center text-sm text-[var(--fg-muted)]"><EmptyState icon={emptyIcon} title={search ? "No matching records" : emptyTitle} description={search ? "Try a different search." : emptyDescription} /></td></tr>
            )}
          </tbody>
        </table>
      </div>
      {paginated && (
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-[var(--border)] px-4 py-3 text-xs text-[var(--fg-muted)]">
          <span>{loading && !rows.length ? "Loading records…" : `Showing ${filtered.length ? (current - 1) * size + 1 : 0} to ${Math.min(current * size, filtered.length)} of ${filtered.length} results`}</span>
          <div className="flex items-center gap-3">
            <select aria-label="Rows per page" value={size} onChange={e => { setSize(Number(e.target.value)); setPage(1); }}
              className="rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] px-2 py-1.5 text-xs">
              {[10, 25, 50].map(n => <option key={n} value={n}>{n} per page</option>)}
            </select>
            <button disabled={current === 1} onClick={() => setPage(current - 1)} aria-label="Previous page" className="rounded-md border border-[var(--border-strong)] p-1.5 disabled:opacity-30"><CaretLeft className="h-3.5 w-3.5" /></button>
            <span className="tabular-nums">{current}/{pages}</span>
            <button disabled={current === pages} onClick={() => setPage(current + 1)} aria-label="Next page" className="rounded-md border border-[var(--border-strong)] p-1.5 disabled:opacity-30"><CaretRight className="h-3.5 w-3.5" /></button>
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
          <button disabled={page <= 0 || busy} onClick={() => onPage(page - 1)} aria-label="Previous page" className="rounded-md border border-[var(--border-strong)] p-1.5 disabled:opacity-30"><CaretLeft className="h-3.5 w-3.5" /></button>
          <span className="num">Page {page + 1} of {totalPages}</span>
          <button disabled={page >= totalPages - 1 || busy} onClick={() => onPage(page + 1)} aria-label="Next page" className="rounded-md border border-[var(--border-strong)] p-1.5 disabled:opacity-30"><CaretRight className="h-3.5 w-3.5" /></button>
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
            className={`whitespace-nowrap rounded-md px-2.5 py-1 text-xs font-medium transition-colors ${active ? "bg-[var(--surface)] text-[var(--fg)] shadow-sm" : "text-[var(--fg-muted)] hover:text-[var(--fg)]"}`}>
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
export function date(value: string | null) { return value ? new Date(value).toLocaleString() : "—"; }
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
    <Link href={href} className="inline-flex items-center gap-1.5 text-xs font-semibold text-[var(--fg-muted)] transition hover:text-[var(--fg)]">
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
      <h1 className="mt-3 text-2xl font-bold tracking-tight text-[var(--fg)]">{title}</h1>
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
        {busy ? busyLabel : submitLabel}
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
