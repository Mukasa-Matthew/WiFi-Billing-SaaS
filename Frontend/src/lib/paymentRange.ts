/**
 * Date ranges for the Payments page. Days are counted in the reporting zone
 * (Africa/Kampala), matching the server's ReportingClock, and sent as YYYY-MM-DD.
 */
export type RangeKey = "today" | "yesterday" | "7d" | "month" | "lastMonth" | "custom";
export type StatusGroup = "ALL" | "SUCCESSFUL" | "PENDING" | "FAILED";
export interface DateRange { from: string; to: string }

export const RANGE_LABEL: Record<RangeKey, string> = {
  today: "Today", yesterday: "Yesterday", "7d": "Last 7 days", month: "This month", lastMonth: "Last month", custom: "Custom",
};
export const DEFAULT_RANGE: Exclude<RangeKey, "custom"> = "7d";

const ZONE = "Africa/Kampala";
/** Today in Kampala as YYYY-MM-DD (en-CA formats dates that way). */
export function kampalaToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: ZONE, year: "numeric", month: "2-digit", day: "2-digit" }).format(now);
}
function shift(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

export function presetRange(key: Exclude<RangeKey, "custom">, now: Date = new Date()): DateRange {
  const today = kampalaToday(now);
  const monthStart = `${today.slice(0, 8)}01`;
  switch (key) {
    case "today": return { from: today, to: today };
    case "yesterday": { const y = shift(today, -1); return { from: y, to: y }; }
    case "7d": return { from: shift(today, -6), to: today };
    case "month": return { from: monthStart, to: today };
    case "lastMonth": { const end = shift(monthStart, -1); return { from: `${end.slice(0, 8)}01`, to: end }; }
  }
}

export function reportUrl(range: DateRange, status: StatusGroup = "ALL", page = 0, size = 25): string {
  return `/api/finance/payments/report?from=${range.from}&to=${range.to}&status=${status}&page=${page}&size=${size}`;
}
export function exportUrl(range: DateRange, status: StatusGroup = "ALL"): string {
  return `/api/finance/payments/export?from=${range.from}&to=${range.to}&status=${status}`;
}

/** "26 Nov 2026" or "1 – 26 Nov 2026" for headings. */
export function describeRange({ from, to }: DateRange): string {
  const fmt = (iso: string, opts: Intl.DateTimeFormatOptions) => new Date(`${iso}T12:00:00Z`).toLocaleDateString("en-GB", { ...opts, timeZone: "UTC" });
  if (from === to) return fmt(from, { day: "numeric", month: "short", year: "numeric" });
  const sameYear = from.slice(0, 4) === to.slice(0, 4);
  return `${fmt(from, { day: "numeric", month: "short", ...(sameYear ? {} : { year: "numeric" }) })} – ${fmt(to, { day: "numeric", month: "short", year: "numeric" })}`;
}
