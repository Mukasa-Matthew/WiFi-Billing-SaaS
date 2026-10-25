"use client";

/**
 * Dependency-free SVG charts drawn against the --chart-* and --status-* tokens.
 * Specs: bars ≤ 24px with a 4px rounded data end, hairline solid grid, one
 * y-axis, per-mark hover/focus tooltip, and a screen-reader table so no value
 * is gated behind hover.
 */

import React, { useEffect, useRef, useState } from "react";

export interface BarDatum { key: string; label: string; value: number; detail?: string }

function niceTicks(max: number, count = 4, integer = false) {
  if (max <= 0) return [0, 1];
  const raw = Math.max(max / count, integer ? 1 : 0);
  const mag = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= raw) || raw;
  const top = Math.ceil(max / step) * step;
  const ticks: number[] = [];
  for (let v = 0; v <= top + step / 2; v += step) ticks.push(Math.round(v * 1e6) / 1e6);
  return ticks;
}

const compactTick = (v: number) => new Intl.NumberFormat(undefined, { notation: "compact", maximumFractionDigits: 1 }).format(v);

function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, width] as const;
}

/** Column path with a rounded top and a square baseline. */
function columnPath(x: number, y: number, w: number, h: number, r = 4) {
  const rr = Math.min(r, w / 2, h);
  return `M${x},${y + h}V${y + rr}Q${x},${y} ${x + rr},${y}H${x + w - rr}Q${x + w},${y} ${x + w},${y + rr}V${y + h}Z`;
}

export function BarChart({ data, label, height = 220, format = (v) => v.toLocaleString(), tickFormat = compactTick, dim = false, integer = false }: {
  data: BarDatum[];
  /** Accessible name, e.g. "New tenants per month". */
  label: string;
  height?: number;
  format?: (v: number) => string;
  /** Axis tick labels; defaults to compact numbers (1K, 2.5M). Pass a unit-aware one for bytes. */
  tickFormat?: (v: number) => string;
  /** Hold the previous render at reduced opacity while refetching. */
  dim?: boolean;
  /** Counts: keep ticks on whole numbers. */
  integer?: boolean;
}) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const max = Math.max(0, ...data.map((d) => d.value));
  const ticks = niceTicks(max, 4, integer);
  const top = ticks[ticks.length - 1] || 1;
  const left = Math.max(28, Math.max(...ticks.map((t) => tickFormat(t).length)) * 7 + 12);
  const pad = { top: 18, right: 4, bottom: 26 };
  const innerW = Math.max(0, width - left - pad.right);
  const innerH = height - pad.top - pad.bottom;
  const band = data.length ? innerW / data.length : 0;
  const barW = Math.max(2, Math.min(24, band * 0.62));
  const y = (v: number) => pad.top + innerH - (v / top) * innerH;
  const every = Math.max(1, Math.ceil(data.length / Math.max(1, Math.floor(innerW / 52))));
  const peak = data.reduce((best, d, i) => (d.value > (data[best]?.value ?? -1) ? i : best), 0);
  const hovered = hover != null ? data[hover] : null;

  return (
    <div ref={ref} className={`relative w-full transition-opacity ${dim ? "opacity-60" : ""}`} style={{ height }}>
      {width > 0 && (
        <svg width={width} height={height} role="img" aria-label={label} className="block overflow-visible">
          {ticks.map((t) => (
            <g key={t}>
              <line x1={left} x2={width - pad.right} y1={y(t)} y2={y(t)} stroke="var(--chart-grid)" strokeWidth={1} shapeRendering="crispEdges" />
              <text x={left - 8} y={y(t)} dy="0.32em" textAnchor="end" className="num fill-[var(--fg-faint)] text-[11px]">{tickFormat(t)}</text>
            </g>
          ))}
          <line x1={left} x2={width - pad.right} y1={y(0)} y2={y(0)} stroke="var(--border-strong)" strokeWidth={1} shapeRendering="crispEdges" />
          {data.map((d, i) => {
            const x = left + i * band + (band - barW) / 2;
            const h = Math.max(0, y(0) - y(d.value));
            return (
              <g key={d.key}>
                {h > 0 && (
                  <path d={columnPath(x, y(d.value), barW, h)} fill="var(--chart-1)"
                    opacity={hover == null || hover === i ? 1 : 0.55} className="transition-opacity duration-150" />
                )}
                {i % every === 0 && (
                  <text x={left + i * band + band / 2} y={height - 8} textAnchor="middle" className="fill-[var(--fg-muted)] text-[11px]">{d.label}</text>
                )}
                {i === peak && d.value > 0 && hover == null && (
                  <text x={x + barW / 2} y={y(d.value) - 6} textAnchor="middle" className="num fill-[var(--fg-body)] text-[11px] font-medium">{format(d.value)}</text>
                )}
                <rect
                  x={left + i * band} y={pad.top} width={band} height={innerH} fill="transparent"
                  tabIndex={0} aria-label={`${d.label}: ${format(d.value)}`}
                  onPointerEnter={() => setHover(i)} onPointerLeave={() => setHover(null)}
                  onFocus={() => setHover(i)} onBlur={() => setHover(null)}
                  className="cursor-default outline-none"
                />
              </g>
            );
          })}
        </svg>
      )}
      {hovered && hover != null && (
        <div
          role="presentation"
          className="pointer-events-none absolute z-10 -translate-x-1/2 -translate-y-full whitespace-nowrap rounded-lg border border-[var(--border)] bg-[var(--surface)] px-3 py-2 shadow-[var(--shadow-overlay)]"
          style={{
            left: Math.min(Math.max(left + hover * band + band / 2, 70), width - 70),
            top: Math.max(y(hovered.value) - 8, 40),
          }}
        >
          <p className="num text-sm font-semibold text-[var(--fg)]">{format(hovered.value)}</p>
          <p className="text-[11px] text-[var(--fg-muted)]">{hovered.detail || hovered.label}</p>
        </div>
      )}
      <table className="sr-only">
        <caption>{label}</caption>
        <tbody>{data.map((d) => <tr key={d.key}><th scope="row">{d.detail || d.label}</th><td>{format(d.value)}</td></tr>)}</tbody>
      </table>
    </div>
  );
}

/** Status tones carry state; "info" and "muted" are for neutral part-to-whole splits (inventory, lifecycle). */
export type StatusTone = "good" | "warn" | "bad" | "idle" | "info" | "muted";
export interface StatusSegment { key: string; label: string; value: number; tone: StatusTone }

const toneVar: Record<StatusTone, string> = {
  good: "var(--status-good)", warn: "var(--status-warn)", bad: "var(--status-bad)", idle: "var(--status-idle)",
  info: "var(--chart-1)", muted: "var(--chart-muted)",
};

/** Part-to-whole status strip with a 2px surface gap and a counted legend. */
export function StatusBar({ segments, label, legend = true }: { segments: StatusSegment[]; label: string; legend?: boolean }) {
  const total = segments.reduce((sum, s) => sum + s.value, 0);
  const visible = segments.filter((s) => s.value > 0);
  return (
    <div>
      <div role="img" aria-label={`${label}: ${segments.map((s) => `${s.value} ${s.label}`).join(", ")}`}
        className="flex h-2.5 w-full gap-[2px] overflow-hidden rounded-full bg-[var(--fill)]">
        {visible.map((s) => (
          <span key={s.key} title={`${s.label}: ${s.value}`} style={{ flexGrow: s.value, background: toneVar[s.tone] }} className="h-full min-w-1.5 first:rounded-l-full last:rounded-r-full" />
        ))}
      </div>
      {legend && (
        <ul className="mt-3 flex flex-wrap gap-x-4 gap-y-1.5">
          {segments.map((s) => (
            <li key={s.key} className="flex items-center gap-1.5 text-xs text-[var(--fg-muted)]">
              <span className="h-2 w-2 rounded-[3px]" style={{ background: toneVar[s.tone] }} aria-hidden />
              {s.label}
              <span className="num font-semibold text-[var(--fg)]">{s.value}</span>
              {total > 0 && <span className="num text-[var(--fg-faint)]">· {Math.round((s.value / total) * 100)}%</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Single-value utilisation meter (CPU, RAM). Fill steps to warn/bad by threshold. */
export function Meter({ value, label }: { value: number | null | undefined; label: string }) {
  if (value == null) return <span className="text-xs text-[var(--fg-faint)]">—</span>;
  const v = Math.max(0, Math.min(100, value));
  const tone = v >= 90 ? "bad" : v >= 75 ? "warn" : "good";
  return (
    <span className="flex items-center gap-2" title={`${label} ${v}%`}>
      <span className="h-1.5 w-14 overflow-hidden rounded-full bg-[var(--fill)]" role="meter" aria-label={label} aria-valuenow={v} aria-valuemin={0} aria-valuemax={100}>
        <span className="block h-full rounded-full" style={{ width: `${v}%`, background: toneVar[tone] }} />
      </span>
      <span className="num text-xs text-[var(--fg-body)]">{v}%</span>
    </span>
  );
}

/**
 * Stat-tile trend: compact columns, earlier periods in the de-emphasis tone and
 * the latest in the accent. No axis; every column has a tooltip and the values
 * are mirrored in a screen-reader table.
 */
export function MiniBars({ data, label, format = (v) => v.toLocaleString(), height = 48 }: {
  data: BarDatum[]; label: string; format?: (v: number) => string; height?: number;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const max = Math.max(1, ...data.map((d) => d.value));
  const hovered = hover != null ? data[hover] : null;
  return (
    <div className="relative">
      <div className="flex items-end gap-1" style={{ height }} role="img" aria-label={label}>
        {data.map((d, i) => {
          const latest = i === data.length - 1;
          return (
            <button
              key={d.key} type="button" aria-label={`${d.detail || d.label}: ${format(d.value)}`}
              onPointerEnter={() => setHover(i)} onPointerLeave={() => setHover(null)} onFocus={() => setHover(i)} onBlur={() => setHover(null)}
              className="flex h-full min-w-0 flex-1 cursor-default items-end outline-none"
            >
              <span
                className="block w-full rounded-t-[3px] transition-opacity"
                style={{
                  height: `${Math.max(d.value > 0 ? 6 : 2, (d.value / max) * 100)}%`,
                  background: latest ? "var(--chart-1)" : "var(--chart-muted)",
                  opacity: hover == null || hover === i ? 1 : 0.6,
                }}
              />
            </button>
          );
        })}
      </div>
      <div className="mt-1.5 flex gap-1 text-[10.5px] text-[var(--fg-faint)]">
        {data.map((d) => <span key={d.key} className="min-w-0 flex-1 truncate text-center">{d.label}</span>)}
      </div>
      {hovered && hover != null && (
        <div role="presentation" className="pointer-events-none absolute bottom-full z-10 mb-2 -translate-x-1/2 whitespace-nowrap rounded-lg border border-[var(--border)] bg-[var(--surface)] px-2.5 py-1.5 shadow-[var(--shadow-overlay)]"
          style={{ left: `${((hover + 0.5) / data.length) * 100}%` }}>
          <p className="num text-[13px] font-semibold text-[var(--fg)]">{format(hovered.value)}</p>
          <p className="text-[11px] text-[var(--fg-muted)]">{hovered.detail || hovered.label}</p>
        </div>
      )}
      <table className="sr-only"><caption>{label}</caption><tbody>{data.map((d) => <tr key={d.key}><th scope="row">{d.detail || d.label}</th><td>{format(d.value)}</td></tr>)}</tbody></table>
    </div>
  );
}
