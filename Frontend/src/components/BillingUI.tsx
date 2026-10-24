"use client";
import React, { useCallback, useEffect, useState, useRef } from "react";
import api from "@/lib/api";
import { LoadingIndicator } from "@/components/LoadingUI";
import Link from "next/link";
import { ArrowLeft, CaretLeft, CaretRight, MagnifyingGlass, Tray } from "@phosphor-icons/react";

/* ---------- design tokens ---------- */
export const field = "mt-1.5 w-full rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] px-3 py-2.5 text-sm text-[var(--fg)] placeholder:text-[var(--fg-muted)] outline-none focus:border-blue-600 focus:ring-2 focus:ring-blue-600/15";
export const button = "inline-flex items-center gap-1.5 rounded-lg bg-blue-700 px-4 py-2.5 text-xs font-semibold text-white hover:bg-blue-600 disabled:opacity-50 disabled:cursor-not-allowed transition";
export const buttonGhost = "inline-flex items-center gap-1.5 rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] px-4 py-2.5 text-xs font-semibold text-[var(--fg-body)] hover:bg-[var(--subtle)] disabled:opacity-50 transition";
export const panel = "rounded-xl border border-[var(--border)] bg-[var(--surface)] shadow-sm";
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
    void load();
    const timer = refresh ? setInterval(load, 30000) : undefined;
    return () => { clearInterval(timer); latestRequest.current += 1; };
  }, [load, refresh]);
  return { data, loading, refreshing, error, load };
}

/* ---------- primitives ---------- */
export function Badge({ children, tone = "blue" }: { children: React.ReactNode; tone?: string }) {
  const color =
    tone === "green" ? "text-emerald-700 bg-emerald-50 border-emerald-200" :
    tone === "red" ? "text-red-700 bg-red-50 border-red-200" :
    tone === "amber" ? "text-amber-700 bg-amber-50 border-amber-200" :
    tone === "slate" ? "text-[var(--fg-muted)] bg-[var(--fill)] border-[var(--border)]" :
    "text-[var(--accent-text)] bg-blue-50 border-blue-200";
  return <span className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-semibold ${color}`}>{children}</span>;
}

export function Notice({ text }: { text: string }) {
  return text ? <p role="status" className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-xs font-medium text-amber-800">{text}</p> : null;
}

export function ErrorNotice({ text }: { text: string }) {
  return text ? <p role="alert" className="rounded-lg border border-red-200 bg-red-50 p-3 text-xs font-medium text-red-700">{text}</p> : null;
}

export function PageHeader({ title, description, action }: { title: string; description?: string; action?: React.ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
      <div>
        <h1 className="text-xl font-bold tracking-tight text-[var(--fg)]">{title}</h1>
        {description && <p className="mt-1 text-sm text-[var(--fg-muted)]">{description}</p>}
      </div>
      {action}
    </div>
  );
}

export function StatCard({ label, value, icon, hintText, tone }: {
  label: string; value: React.ReactNode; icon?: React.ReactNode; hintText?: string;
  tone?: "default" | "green" | "amber" | "red";
}) {
  const iconColor =
    tone === "green" ? "bg-emerald-50 text-emerald-700 border-emerald-100" :
    tone === "amber" ? "bg-amber-50 text-amber-700 border-amber-100" :
    tone === "red" ? "bg-red-50 text-red-700 border-red-100" :
    "bg-blue-50 text-[var(--accent-text)] border-blue-100";
  return (
    <div className={`${panel} p-5`}>
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wide text-[var(--fg-muted)]">{label}</p>
          <p className="mt-2 text-2xl font-bold tabular-nums text-[var(--fg)]">{value}</p>
          {hintText && <p className="mt-1 text-xs text-[var(--fg-muted)]">{hintText}</p>}
        </div>
        {icon && <div className={`rounded-lg border p-2.5 ${iconColor}`}>{icon}</div>}
      </div>
    </div>
  );
}

export function EmptyState({ title, description, action, icon }: { title: string; description?: string; action?: React.ReactNode; icon?: React.ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-14 text-center">
      {icon || <Tray className="h-8 w-8 text-slate-300" />}
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
          {o.label}{o.count !== undefined && <span className="ml-1.5 text-[var(--fg-muted)]">{o.count}</span>}
        </button>
      ))}
    </div>
  );
}

/* ---------- table ---------- */
export function Table<T>({ rows, columns, loading = false, emptyTitle = "No records found.", emptyDescription }: { emptyTitle?: string; emptyDescription?: string; rows: T[]; columns: { label: string; render: (row: T) => React.ReactNode; className?: string }[]; loading?: boolean }) {
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const filtered = rows.filter(r => JSON.stringify(r).toLowerCase().includes(search.toLowerCase()));
  const pages = Math.max(1, Math.ceil(filtered.length / size));
  const current = Math.min(page, pages);
  return (
    <section className={`${panel} overflow-hidden`} aria-busy={loading}>
      <div className="flex justify-end border-b border-[var(--border)] px-4 py-3">
        <div className="relative">
          <MagnifyingGlass className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-[var(--fg-muted)]" />
          <input aria-label="Search records" placeholder="Search…" value={search}
            onChange={e => { setSearch(e.target.value); setPage(1); }}
            className="w-full sm:w-56 rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] py-2 pl-9 pr-3 text-xs text-[var(--fg)] outline-none focus:border-blue-600" />
        </div>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full whitespace-nowrap text-left text-sm">
          <thead className="bg-[var(--subtle)] text-xs uppercase tracking-wide text-[var(--fg-muted)]">
            <tr>{columns.map((c, i) => <th key={i} scope="col" className={`px-4 py-3 font-semibold ${c.className || ""}`}>{c.label}</th>)}</tr>
          </thead>
          <tbody className="divide-y divide-[var(--border-soft)]">
            {filtered.slice((current - 1) * size, current * size).map((r, i) => (
              <tr key={i} className="hover:bg-[var(--subtle)]">
                {columns.map((c, j) => <td key={j} className="px-4 py-3.5 text-[var(--fg-body)]">{c.render(r)}</td>)}
              </tr>
            ))}
            {!filtered.length && (
              <tr><td colSpan={columns.length} className="py-12 text-center text-sm text-[var(--fg-muted)]">{loading ? <LoadingIndicator label="Loading records…" /> : <EmptyState title={search ? "No matching records" : emptyTitle} description={search ? "Try a different search." : emptyDescription} />}</td></tr>
            )}
          </tbody>
        </table>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-[var(--border)] px-4 py-3 text-xs text-[var(--fg-muted)]">
        <span>{loading ? "Loading records…" : `Showing ${filtered.length ? (current - 1) * size + 1 : 0} to ${Math.min(current * size, filtered.length)} of ${filtered.length} results`}</span>
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
    </section>
  );
}

/* ---------- formatters ---------- */
export function date(value: string | null) { return value ? new Date(value).toLocaleString() : "—"; }
export function bytes(value: number) { return value < 1048576 ? `${(value / 1024).toFixed(1)} KB` : value < 1073741824 ? `${(value / 1048576).toFixed(2)} MB` : `${(value / 1073741824).toFixed(2)} GB`; }
export function remaining(value: number | null) { if (value === null) return "Not started"; if (value === 0) return "Expired"; return `${Math.floor(value / 86400)}d ${Math.floor(value % 86400 / 3600)}h ${Math.floor(value % 3600 / 60)}m`; }
export function ugx(v: number | string | null | undefined) { return `UGX ${Number(v || 0).toLocaleString()}`; }

/* ---------- create / edit form scaffolding ----------
   Shared so every "new" page reads the same: back link, titled sections with
   an icon, and one action row. */

export function BackLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <Link href={href} className="inline-flex items-center gap-1.5 text-xs font-semibold text-[var(--fg-muted)] transition hover:text-[var(--fg)]">
      <ArrowLeft size={13} weight="bold" aria-hidden />{children}
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
