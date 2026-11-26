"use client";
import { useMemo, useState, useSyncExternalStore } from "react";
import api from "@/lib/api";
import { DashboardLoading } from "@/components/LoadingUI";
import {
  ArrowClockwise, Download, Eye, EyeSlash, Lightbulb, Printer, Target, TrendingDown, TrendingUp,
} from "@/components/icons";
import { Card, ErrorNotice, PageHeader, StatCard, button, buttonGhost, compactNumber, field, message, useApi, ugx } from "@/components/BillingUI";
import { DonutChart, Gauge, Heatmap, RankedBars, StatusBar, TrendChart, Waterfall } from "@/components/charts";
import { hideStore } from "@/lib/hideRevenue";
import { CHANNEL_COLOR, GROUP_COLOR } from "@/lib/revenueColors";

/* ------------------------------------------------------------------ API shape (FinancialAnalysisDto) */

interface Totals { gross: number; gatewayFees: number; platformFees: number; net: number; sales: number; avgSale: number; customers: number; perCustomer: number }
interface SeriesPoint { start: string; label: string; gross: number; net: number; fees: number; sales: number; previousGross: number | null }
interface Slice { key: string; label: string; gross: number; sales: number; previousGross: number }
interface Ranked { id: number; name: string; gross: number; net: number; sales: number; customers: number; previousGross: number; price: number }
interface TopCustomer { phone: string; name: string | null; gross: number; sales: number; lastPurchase: string | null }
interface Analysis {
  period: { key: string; label: string; from: string; to: string; previousFrom: string; previousTo: string; bucket: string; partial: boolean };
  school: boolean;
  options: { routers: { id: number; name: string }[]; packages: { id: number; name: string }[] };
  totals: Totals; previous: Totals; series: SeriesPoint[];
  byChannel: Slice[]; byGroup: Slice[]; byPackage: Ranked[]; byRouter: Ranked[];
  heatmapGross: number[][]; heatmapSales: number[][];
  customers: { total: number; newCustomers: number; returning: number; newGross: number; returningGross: number; repeatBuyers: number; lapsed: number; lapsedPreviousGross: number; previousTotal: number; top: TopCustomer[] };
  lost: { failed: number; failedValue: number; abandoned: number; abandonedValue: number; attempts: number; successRate: number | null; previousSuccessRate: number | null; byNetwork: { key: string; label: string; failed: number; abandoned: number; value: number }[] };
  moneyFlow: { gross: number; gatewayFees: number; platformFees: number; net: number; withdrawn: number; withdrawalFees: number; withdrawals: number; subscriptionPaid: number; walletAvailable: number; walletPending: number; walletReserved: number };
  target: { monthlyTarget: number | null; monthToDate: number; lastMonthSameDay: number; lastMonthTotal: number; projected: number; dayOfMonth: number; daysInMonth: number; month: string };
}

const PERIODS = [
  { key: "today", label: "Today" }, { key: "yesterday", label: "Yesterday" }, { key: "7d", label: "Last 7 days" },
  { key: "30d", label: "Last 30 days" }, { key: "week", label: "This week" }, { key: "lastweek", label: "Last week" },
  { key: "month", label: "This month" }, { key: "lastmonth", label: "Last month" }, { key: "quarter", label: "This quarter" },
  { key: "year", label: "This year" }, { key: "lastyear", label: "Last year" }, { key: "custom", label: "Custom dates…" },
];
const CHANNELS = [
  { key: "", label: "All methods" }, { key: "MTN", label: "MTN Mobile Money" }, { key: "AIRTEL", label: "Airtel Money" },
  { key: "OTHER", label: "Other mobile money" }, { key: "WALK_IN", label: "Walk-in / cash" },
];
const GROUPS = [
  { key: "", label: "All customers" }, { key: "STUDENT", label: "Students" },
  { key: "PAID_CUSTOMER", label: "Other paying customers" }, { key: "WALK_IN", label: "Walk-in / cash" },
];
const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const DAY_NAMES = ["Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays", "Sundays"];

/* ------------------------------------------------------------------ helpers */

/** % change, or null when there is nothing to compare with. */
function pctChange(cur: number, prev: number) {
  if (!prev) return null;
  return ((cur - prev) / prev) * 100;
}

function Delta({ cur, prev, goodWhenUp = true, suffix = "vs previous period" }: { cur: number; prev: number; goodWhenUp?: boolean; suffix?: string }) {
  const p = pctChange(cur, prev);
  if (p == null) return <span className="text-[var(--fg-muted)]">{cur > 0 ? "Nothing to compare with" : "No sales in either period"}</span>;
  const up = p > 0.05, down = p < -0.05;
  const good = (up && goodWhenUp) || (down && !goodWhenUp);
  const bad = (down && goodWhenUp) || (up && !goodWhenUp);
  const Icon = up ? TrendingUp : down ? TrendingDown : null;
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5">
      <span className={`inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11.5px] font-semibold ${good ? "bg-emerald-50 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-400" : bad ? "bg-red-50 text-red-700 dark:bg-red-500/10 dark:text-red-400" : "bg-[var(--fill)] text-[var(--fg-muted)]"}`}>
        {Icon && <Icon className="h-3.5 w-3.5" aria-hidden />}
        {up ? "+" : ""}{p.toFixed(Math.abs(p) < 10 ? 1 : 0)}%
      </span>
      <span>{suffix}</span>
    </span>
  );
}

function shortDay(iso: string) {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

function csvCell(v: unknown) {
  const s = String(v ?? "");
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

function downloadCsv(d: Analysis) {
  const rows: unknown[][] = [];
  rows.push(["Financial analysis", d.period.label, `${d.period.from} to ${d.period.to}`]);
  rows.push([]);
  rows.push(["Measure", "This period", `Previous (${d.period.previousFrom} to ${d.period.previousTo})`]);
  const t = d.totals, p = d.previous;
  ([["Sales (UGX)", t.gross, p.gross], ["MarzPay fees (UGX)", t.gatewayFees, p.gatewayFees], ["Platform fee (UGX)", t.platformFees, p.platformFees],
    ["Net income (UGX)", t.net, p.net], ["Number of sales", t.sales, p.sales], ["Average sale (UGX)", t.avgSale, p.avgSale],
    ["Paying customers", t.customers, p.customers], ["Income per customer (UGX)", t.perCustomer, p.perCustomer]] as unknown[][]).forEach((r) => rows.push(r));
  rows.push([]);
  rows.push(["When", "Sales (UGX)", "Net income (UGX)", "Fees (UGX)", "Number of sales", "Previous period sales (UGX)"]);
  d.series.forEach((s) => rows.push([s.label, s.gross, s.net, s.fees, s.sales, s.previousGross ?? ""]));
  rows.push([]);
  rows.push(["Package", "Price (UGX)", "Sales (UGX)", "Net (UGX)", "Number of sales", "Customers", "Previous period (UGX)"]);
  d.byPackage.forEach((r) => rows.push([r.name, r.price, r.gross, r.net, r.sales, r.customers, r.previousGross]));
  rows.push([]);
  rows.push(["Router / location", "Sales (UGX)", "Net (UGX)", "Number of sales", "Customers", "Previous period (UGX)"]);
  d.byRouter.forEach((r) => rows.push([r.name, r.gross, r.net, r.sales, r.customers, r.previousGross]));
  rows.push([]);
  rows.push(["Payment method", "Sales (UGX)", "Number of sales", "Previous period (UGX)"]);
  d.byChannel.forEach((r) => rows.push([r.label, r.gross, r.sales, r.previousGross]));
  rows.push([]);
  rows.push(["Failed payments", d.lost.failed, "UGX", d.lost.failedValue]);
  rows.push(["Never approved", d.lost.abandoned, "UGX", d.lost.abandonedValue]);
  const blob = new Blob([rows.map((r) => r.map(csvCell).join(",")).join("\n")], { type: "text/csv;charset=utf-8" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = `financial-analysis-${d.period.from}-to-${d.period.to}.csv`;
  a.click();
  URL.revokeObjectURL(a.href);
}

/* ------------------------------------------------------------------ insights */

interface Insight { tone: "up" | "down" | "info"; text: string }

function buildInsights(d: Analysis, money: (v: number) => string): Insight[] {
  const out: Insight[] = [];
  const t = d.totals, p = d.previous;
  const ch = pctChange(t.gross, p.gross);
  if (ch != null && Math.abs(ch) >= 1) {
    out.push({ tone: ch > 0 ? "up" : "down", text: `Sales are ${ch > 0 ? "up" : "down"} ${Math.abs(ch).toFixed(0)}% on the previous period: ${money(t.gross)} against ${money(p.gross)}.` });
  } else if (t.gross > 0 && !p.gross) {
    out.push({ tone: "up", text: `You made ${money(t.gross)} in this period, and nothing in the period before it.` });
  }
  const pk = d.byPackage.filter((r) => r.gross > 0);
  if (pk.length && t.gross > 0) {
    const share = (pk[0].gross / t.gross) * 100;
    out.push({ tone: "info", text: `${pk[0].name} is your best seller, with ${share.toFixed(0)}% of sales (${pk[0].sales} sold).` });
  }
  const dead = d.byPackage.filter((r) => r.gross === 0 && r.id !== 0);
  if (dead.length && t.sales > 0) out.push({ tone: "info", text: `${dead.length === 1 ? `${dead[0].name} sold` : `${dead.length} packages sold`} nothing in this period. Consider changing ${dead.length === 1 ? "its" : "their"} price or removing ${dead.length === 1 ? "it" : "them"}.` });
  const falling = d.byRouter.filter((r) => r.previousGross > 0 && pctChange(r.gross, r.previousGross)! <= -30)
    .sort((a, b) => (b.previousGross - b.gross) - (a.previousGross - a.gross))[0];
  if (falling) {
    out.push({ tone: "down", text: `Sales at ${falling.name} fell ${Math.abs(pctChange(falling.gross, falling.previousGross)!).toFixed(0)}% (${money(falling.previousGross)} → ${money(falling.gross)}). Check that it is online and working well.` });
  }
  if (t.sales >= 5) {
    const dayTotals = d.heatmapGross.map((row) => row.reduce((a, b) => a + b, 0));
    const bestDay = dayTotals.indexOf(Math.max(...dayTotals));
    const hourTotals = Array.from({ length: 24 }, (_, h) => d.heatmapGross.reduce((a, row) => a + row[h], 0));
    let bestH = 0, bestSum = -1;
    for (let h = 0; h <= 21; h++) { const s = hourTotals[h] + hourTotals[h + 1] + hourTotals[h + 2]; if (s > bestSum) { bestSum = s; bestH = h; } }
    out.push({ tone: "info", text: `Customers spend the most on ${DAY_NAMES[bestDay]}, and between ${String(bestH).padStart(2, "0")}:00 and ${String(bestH + 3).padStart(2, "0")}:00. A good time to make sure everything works, or to run a promotion.` });
  }
  const lostValue = d.lost.failedValue + d.lost.abandonedValue;
  if (lostValue > 0) {
    const worst = [...d.lost.byNetwork].sort((a, b) => b.value - a.value)[0];
    out.push({ tone: "down", text: `${money(lostValue)} of mobile money payments failed or were never approved (${d.lost.failed + d.lost.abandoned} payments${worst?.value ? `, most on ${worst.label}` : ""}).` });
  }
  if (d.customers.lapsed > 0) {
    out.push({ tone: "down", text: `${d.customers.lapsed} customer${d.customers.lapsed === 1 ? "" : "s"} who bought in the previous period ${d.customers.lapsed === 1 ? "hasn't" : "haven't"} bought again (they spent ${money(d.customers.lapsedPreviousGross)} before).` });
  }
  if (t.gross > 0) {
    const fees = t.gatewayFees + t.platformFees;
    if (fees > 0) out.push({ tone: "info", text: `Fees took ${((fees / t.gross) * 100).toFixed(1)}% of your sales (${money(fees)}). You kept ${money(t.net)}.` });
  }
  const tg = d.target;
  if (tg.monthlyTarget && tg.dayOfMonth >= 3) {
    const pct = (tg.projected / tg.monthlyTarget) * 100;
    out.push({ tone: pct >= 100 ? "up" : "down", text: pct >= 100
      ? `At this pace you'll make about ${money(tg.projected)} in ${tg.month}, beating your ${money(tg.monthlyTarget)} target.`
      : `At this pace you'll make about ${money(tg.projected)} in ${tg.month}, ${(100 - pct).toFixed(0)}% short of your ${money(tg.monthlyTarget)} target.` });
  }
  return out.slice(0, 7);
}

/* ------------------------------------------------------------------ page */

export default function FinancialAnalysisPage() {
  const [period, setPeriod] = useState("30d");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [routerId, setRouterId] = useState("");
  const [packageId, setPackageId] = useState("");
  const [channel, setChannel] = useState("");
  const [group, setGroup] = useState("");
  const [compare, setCompare] = useState(true);
  const [metric, setMetric] = useState<"gross" | "net" | "fees" | "sales">("gross");
  const hidden = useSyncExternalStore(hideStore.subscribe, hideStore.get, () => false);

  const customReady = period !== "custom" || (from && to && from <= to);
  const url = useMemo(() => {
    if (!customReady) return null;
    const q = new URLSearchParams({ period });
    if (period === "custom") { q.set("from", from); q.set("to", to); }
    if (routerId) q.set("routerId", routerId);
    if (packageId) q.set("packageId", packageId);
    if (channel) q.set("channel", channel);
    if (group) q.set("group", group);
    return `/api/finance/analysis?${q}`;
  }, [customReady, period, from, to, routerId, packageId, channel, group]);
  const res = useApi<Analysis>(url, 120000, { keepPrevious: true });
  const d = res.data;

  const money = (v: number | null | undefined) => ugx(v, { hidden });
  const moneyShort = (v: number) => (hidden ? "•••" : compactNumber(v));
  const filtered = !!(routerId || packageId || channel || group);

  if (res.loading && !d && !res.error) return <DashboardLoading />;

  return (
    <div className={`mx-auto max-w-7xl space-y-6 transition-opacity ${res.placeholder ? "opacity-60" : ""}`} aria-busy={res.placeholder || res.refreshing}>
      <PageHeader
        title="Financial analysis"
        description="How your business is doing: what you sell, what you keep after fees, where the money comes from and where it's being lost."
        meta={d ? <span>{d.period.label} · {shortDay(d.period.from)}{d.period.to !== d.period.from ? ` – ${shortDay(d.period.to)}` : ""}{d.period.partial ? " (so far)" : ""}</span> : undefined}
        action={
          <div className="flex flex-wrap gap-2 print:hidden">
            <button onClick={() => hideStore.set(!hidden)} className={`${buttonGhost} !px-3`} aria-pressed={hidden} aria-label={hidden ? "Show amounts" : "Hide amounts"} title={hidden ? "Show amounts" : "Hide amounts"}>
              {hidden ? <EyeSlash className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
            <button onClick={() => res.load()} disabled={res.refreshing} className={`${buttonGhost} !px-3`} aria-label="Refresh" title="Refresh">
              <ArrowClockwise className={`h-4 w-4 ${res.refreshing ? "animate-spin motion-reduce:animate-none" : ""}`} />
            </button>
            <button onClick={() => d && downloadCsv(d)} disabled={!d} className={buttonGhost}><Download className="h-4 w-4" />CSV</button>
            <button onClick={() => window.print()} disabled={!d} className={buttonGhost}><Printer className="h-4 w-4" />Print / PDF</button>
          </div>
        }
      />

      {/* One filter row above everything it scopes */}
      <section aria-label="Filters" className="panel-shadow rounded-xl border border-[var(--border)] bg-[var(--surface)] p-4 print:hidden">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-6">
          <label className="text-xs font-semibold text-[var(--fg-muted)]">Period
            <select value={period} onChange={(e) => setPeriod(e.target.value)} className={field}>
              {PERIODS.map((p) => <option key={p.key} value={p.key}>{p.label}</option>)}
            </select>
          </label>
          {period === "custom" && (
            <>
              <label className="text-xs font-semibold text-[var(--fg-muted)]">From
                <input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} className={field} />
              </label>
              <label className="text-xs font-semibold text-[var(--fg-muted)]">To
                <input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} className={field} />
              </label>
            </>
          )}
          <label className="text-xs font-semibold text-[var(--fg-muted)]">Router / location
            <select value={routerId} onChange={(e) => setRouterId(e.target.value)} className={field}>
              <option value="">All locations</option>
              {d?.options.routers.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
            </select>
          </label>
          <label className="text-xs font-semibold text-[var(--fg-muted)]">Package
            <select value={packageId} onChange={(e) => setPackageId(e.target.value)} className={field}>
              <option value="">All packages</option>
              {d?.options.packages.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </label>
          <label className="text-xs font-semibold text-[var(--fg-muted)]">Payment method
            <select value={channel} onChange={(e) => setChannel(e.target.value)} className={field}>
              {CHANNELS.map((c) => <option key={c.key} value={c.key}>{c.label}</option>)}
            </select>
          </label>
          {d?.school && (
            <label className="text-xs font-semibold text-[var(--fg-muted)]">Customer type
              <select value={group} onChange={(e) => setGroup(e.target.value)} className={field}>
                {GROUPS.map((g) => <option key={g.key} value={g.key}>{g.label}</option>)}
              </select>
            </label>
          )}
        </div>
        <div className="mt-3 flex flex-wrap items-center justify-between gap-3 text-xs text-[var(--fg-muted)]">
          <label className="inline-flex items-center gap-2">
            <input type="checkbox" checked={compare} onChange={(e) => setCompare(e.target.checked)} className="h-4 w-4 accent-[var(--chart-1)]" />
            Compare with the previous period{d ? ` (${shortDay(d.period.previousFrom)}${d.period.previousTo !== d.period.previousFrom ? ` – ${shortDay(d.period.previousTo)}` : ""})` : ""}
          </label>
          {filtered && (
            <button onClick={() => { setRouterId(""); setPackageId(""); setChannel(""); setGroup(""); }} className="font-semibold text-[var(--chart-1)] hover:underline">
              Clear filters
            </button>
          )}
        </div>
        {period === "custom" && !customReady && <p className="mt-2 text-xs text-[var(--fg-muted)]">Choose a start and an end date.</p>}
      </section>

      {res.error && <ErrorNotice text={res.error} />}

      {d && (
        <>
          {/* Headline numbers */}
          <section aria-label="Headline numbers" className="grid grid-cols-2 gap-3 sm:gap-4 xl:grid-cols-4">
            <StatCard label="Sales" value={money(d.totals.gross)}
              hint={compare ? <Delta cur={d.totals.gross} prev={d.previous.gross} /> : `${d.totals.sales.toLocaleString()} sales`} />
            <StatCard label="Net income (what you keep)" tone="green" value={money(d.totals.net)}
              hint={compare ? <Delta cur={d.totals.net} prev={d.previous.net} /> : "After MarzPay and platform fees"} />
            <StatCard label="Fees paid" value={money(d.totals.gatewayFees + d.totals.platformFees)}
              hintText={`MarzPay ${money(d.totals.gatewayFees)} · Platform ${money(d.totals.platformFees)}${d.totals.gross > 0 ? ` · ${(((d.totals.gatewayFees + d.totals.platformFees) / d.totals.gross) * 100).toFixed(1)}% of sales` : ""}`} />
            <StatCard label="Number of sales" value={d.totals.sales.toLocaleString()}
              hint={compare ? <Delta cur={d.totals.sales} prev={d.previous.sales} /> : undefined} />
            <StatCard label="Average sale" value={money(d.totals.avgSale)}
              hint={compare ? <Delta cur={d.totals.avgSale} prev={d.previous.avgSale} /> : undefined} />
            <StatCard label="Paying customers" value={d.totals.customers.toLocaleString()}
              hint={compare ? <Delta cur={d.totals.customers} prev={d.previous.customers} /> : "Different phone numbers that paid"} />
            <StatCard label="Income per customer" value={money(d.totals.perCustomer)}
              hint={compare ? <Delta cur={d.totals.perCustomer} prev={d.previous.perCustomer} /> : undefined} />
            <StatCard label="New customers" value={d.customers.newCustomers.toLocaleString()}
              hintText={`${d.customers.returning.toLocaleString()} returning · ${d.customers.repeatBuyers.toLocaleString()} bought more than once`} />
          </section>

          <Insights items={buildInsights(d, money)} />

          {/* Gauges */}
          <section aria-label="Targets and health" className="grid grid-cols-1 gap-4 lg:grid-cols-3">
            <TargetCard d={d} money={money} onSaved={() => res.load()} />
            <SuccessCard d={d} />
            <Card title="Month-end forecast" description={`${d.target.month}, if sales continue at this month's pace`}>
              <p className="text-[1.9rem] font-semibold leading-tight tracking-tight text-[var(--fg)]">{money(d.target.projected)}</p>
              <dl className="mt-4 space-y-2 text-[13px]">
                <div className="flex justify-between gap-3"><dt className="text-[var(--fg-muted)]">So far this month (day {d.target.dayOfMonth} of {d.target.daysInMonth})</dt><dd className="num font-semibold">{money(d.target.monthToDate)}</dd></div>
                <div className="flex justify-between gap-3"><dt className="text-[var(--fg-muted)]">Last month by the same day</dt><dd className="num font-semibold">{money(d.target.lastMonthSameDay)}</dd></div>
                <div className="flex justify-between gap-3"><dt className="text-[var(--fg-muted)]">Last month in total</dt><dd className="num font-semibold">{money(d.target.lastMonthTotal)}</dd></div>
              </dl>
              <p className="mt-3 text-xs text-[var(--fg-muted)]"><Delta cur={d.target.monthToDate} prev={d.target.lastMonthSameDay} suffix="vs last month by the same day" /></p>
              {d.target.dayOfMonth < 3 && <p className="mt-2 text-xs text-[var(--fg-muted)]">The forecast gets more reliable after the first few days of the month.</p>}
            </Card>
          </section>

          {/* Trend + where the money comes from */}
          <section className="grid grid-cols-1 gap-4 lg:grid-cols-3">
            <Card className="lg:col-span-2" title="Income over time" description={`Per ${d.period.bucket} · ${d.period.label.toLowerCase()}`}
              action={
                <select aria-label="Measure" value={metric} onChange={(e) => setMetric(e.target.value as typeof metric)} className={`${field} !mt-0 !min-h-9 !py-1.5 text-xs print:hidden`}>
                  <option value="gross">Sales</option><option value="net">Net income</option><option value="fees">Fees</option><option value="sales">Number of sales</option>
                </select>
              }>
              <TrendChart
                label={`${metric === "gross" ? "Sales" : metric === "net" ? "Net income" : metric === "fees" ? "Fees" : "Number of sales"}, ${d.period.label}`}
                dim={res.placeholder}
                data={d.series.map((s) => ({ key: s.start, label: d.period.bucket === "day" ? s.label.replace(/^\S+ /, "") : s.label, detail: s.label, value: s[metric], previous: metric === "gross" ? s.previousGross : null }))}
                format={metric === "sales" ? (v) => `${v.toLocaleString()} sale${v === 1 ? "" : "s"}` : (v) => money(v)}
                tickFormat={metric === "sales" ? compactNumber : moneyShort}
                showPrevious={compare && metric === "gross"}
                currentName={d.period.label} previousName="Previous period"
              />
            </Card>
            <Card title="How customers pay" description="Sales by payment method" bodyClassName="p-5 [&>div]:!flex-col [&>div]:!items-stretch">
              {hidden ? <HiddenNote /> : (
                <DonutChart label="Sales by payment method" centerLabel="Sales" centerValue={moneyShort(d.totals.gross)} format={(v) => money(v)}
                  segments={d.byChannel.map((c) => ({ key: c.key, label: c.label, value: c.gross, color: CHANNEL_COLOR[c.key],
                    sublabel: `${c.sales} sale${c.sales === 1 ? "" : "s"}${compare && c.previousGross ? ` · was ${money(c.previousGross)}` : ""}` }))} />
              )}
            </Card>
          </section>

          {d.school && d.byGroup.length > 0 && (
            <Card title="Who pays" description="Students, other paying customers and walk-in / cash sales">
              {hidden ? <HiddenNote /> : (
                <DonutChart label="Sales by customer type" centerLabel="Sales" centerValue={moneyShort(d.totals.gross)} format={(v) => money(v)}
                  segments={d.byGroup.map((g) => ({ key: g.key, label: g.label, value: g.gross, color: GROUP_COLOR[g.key], sublabel: `${g.sales} sale${g.sales === 1 ? "" : "s"}` }))} />
              )}
            </Card>
          )}

          {/* When people buy */}
          <Card title="When customers buy" description="Sales by day of the week and hour of the day (Kampala time). Darker means more money.">
            {d.totals.sales === 0 ? <p className="py-8 text-center text-sm text-[var(--fg-muted)]">No sales in this period.</p> : (
              <Heatmap rows={d.heatmapGross} rowLabels={DAYS} label="Sales by day and hour" format={(v) => money(v)}
                detail={(r, h) => `${DAY_NAMES[r].slice(0, -1)} ${String(h).padStart(2, "0")}:00–${String((h + 1) % 24).padStart(2, "0")}:00 · ${d.heatmapSales[r][h]} sale${d.heatmapSales[r][h] === 1 ? "" : "s"}`} />
            )}
          </Card>

          {/* Packages and locations */}
          <section className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <Card title="Package performance" description="Best sellers first. Packages that sold nothing are listed in grey.">
              <RankedBars label="Sales by package" format={(v) => money(v)} empty="No packages yet."
                rows={d.byPackage.map((r) => ({
                  key: String(r.id), label: r.name, value: r.gross, muted: r.gross === 0,
                  sublabel: `${r.sales} sold${r.price ? ` at ${money(r.price)}` : ""} · ${r.customers} customer${r.customers === 1 ? "" : "s"}${d.totals.gross > 0 && r.gross > 0 ? ` · ${((r.gross / d.totals.gross) * 100).toFixed(0)}% of sales` : ""}`,
                  aside: compare ? <MiniDelta cur={r.gross} prev={r.previousGross} /> : undefined,
                }))} />
            </Card>
            <Card title="Location performance" description="What each router earns. Use the filter above to look at one location.">
              <RankedBars label="Sales by router" format={(v) => money(v)} empty="No routers yet."
                rows={d.byRouter.map((r) => ({
                  key: String(r.id), label: r.name, value: r.gross, muted: r.gross === 0,
                  sublabel: `${r.sales} sale${r.sales === 1 ? "" : "s"} · ${r.customers} customer${r.customers === 1 ? "" : "s"} · net ${money(r.net)}`,
                  aside: compare ? <MiniDelta cur={r.gross} prev={r.previousGross} /> : undefined,
                }))} />
            </Card>
          </section>

          {/* Customers */}
          <CustomersCard d={d} money={money} compare={compare} />

          {/* Lost money + money flow */}
          <section className="grid grid-cols-1 gap-4 lg:grid-cols-5">
            <Card className="lg:col-span-2" title="Money lost to failed payments" description="Mobile money payments that failed, or that customers never approved on their phone">
              <div className="grid grid-cols-2 gap-3">
                <Figure label={`Failed (${d.lost.failed})`} value={money(d.lost.failedValue)} />
                <Figure label={`Never approved (${d.lost.abandoned})`} value={money(d.lost.abandonedValue)} />
              </div>
              <div className="mt-5">
                <RankedBars label="Lost money by network" format={(v) => money(v)} empty="No failed payments."
                  rows={d.lost.byNetwork.filter((n) => n.failed + n.abandoned > 0).map((n) => ({
                    key: n.key, label: n.label, value: n.value, sublabel: `${n.failed} failed · ${n.abandoned} never approved`,
                  }))} />
              </div>
              <p className="mt-4 text-xs leading-relaxed text-[var(--fg-muted)]">&ldquo;Never approved&rdquo; means the customer didn&apos;t enter their PIN within 30 minutes. Many of these are customers who changed their mind or had too little balance.</p>
            </Card>
            <Card className="lg:col-span-3" title="Money flow" description={`From sales to what's left, ${d.period.label.toLowerCase()}. Covers the whole business: the filters don't apply here.`}>
              <Waterfall label="Money flow" format={moneyShort} steps={[
                { key: "gross", label: "Sales", value: d.moneyFlow.gross, kind: "total" },
                { key: "gw", label: "MarzPay fees", value: d.moneyFlow.gatewayFees, kind: "minus" },
                ...(d.moneyFlow.platformFees > 0 ? [{ key: "plat", label: "Platform fee", value: d.moneyFlow.platformFees, kind: "minus" as const }] : []),
                { key: "net", label: "Net income", value: d.moneyFlow.net, kind: "total" },
                { key: "subs", label: "Subscription", value: d.moneyFlow.subscriptionPaid, kind: "minus" },
                { key: "left", label: "Left after costs", value: Math.max(0, d.moneyFlow.net - d.moneyFlow.subscriptionPaid), kind: "total" },
              ]} />
              <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-3">
                <Figure label={`Withdrawn to your phone (${d.moneyFlow.withdrawals})`} value={money(d.moneyFlow.withdrawn)} note={d.moneyFlow.withdrawalFees ? `Withdrawal fees ${money(d.moneyFlow.withdrawalFees)}` : undefined} />
                <Figure label="Wallet: available now" value={money(d.moneyFlow.walletAvailable)} />
                <Figure label="Wallet: on the way" value={money(d.moneyFlow.walletPending + d.moneyFlow.walletReserved)} note="Still settling, or held for a withdrawal" />
              </div>
            </Card>
          </section>
        </>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ pieces */

function HiddenNote() {
  return <p className="py-14 text-center text-sm text-[var(--fg-muted)]">Amounts are hidden. Use the eye button to show them.</p>;
}

function Figure({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div className="rounded-lg border border-[var(--border)] bg-[var(--subtle)] px-3.5 py-3">
      <p className="text-[11.5px] text-[var(--fg-muted)]">{label}</p>
      <p className="num mt-0.5 truncate text-[15px] font-semibold text-[var(--fg)]">{value}</p>
      {note && <p className="mt-0.5 text-[11px] text-[var(--fg-muted)]">{note}</p>}
    </div>
  );
}

function MiniDelta({ cur, prev }: { cur: number; prev: number }) {
  const p = pctChange(cur, prev);
  if (p == null) return cur > 0 ? <span className="text-[11px] font-semibold text-emerald-700 dark:text-emerald-400">New</span> : null;
  if (Math.abs(p) < 0.5) return <span className="text-[11px] text-[var(--fg-muted)]">±0%</span>;
  return <span className={`num text-[11px] font-semibold ${p > 0 ? "text-emerald-700 dark:text-emerald-400" : "text-red-700 dark:text-red-400"}`}>{p > 0 ? "▲" : "▼"} {Math.abs(p).toFixed(0)}%</span>;
}

function Insights({ items }: { items: Insight[] }) {
  if (!items.length) return null;
  return (
    <Card title={<span className="inline-flex items-center gap-2"><Lightbulb className="h-4 w-4 text-[var(--chart-1)]" aria-hidden />What stands out</span>} bodyClassName="p-0">
      <ul className="divide-y divide-[var(--border-soft)]">
        {items.map((it, i) => {
          const Icon = it.tone === "up" ? TrendingUp : it.tone === "down" ? TrendingDown : Lightbulb;
          return (
            <li key={i} className="flex items-start gap-3 px-5 py-3 text-[13.5px] leading-relaxed text-[var(--fg-body)]">
              <span className={`mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-md ${it.tone === "up" ? "bg-emerald-50 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-400" : it.tone === "down" ? "bg-amber-50 text-amber-700 dark:bg-amber-500/10 dark:text-amber-400" : "bg-[var(--fill)] text-[var(--fg-muted)]"}`}>
                <Icon className="h-3.5 w-3.5" aria-hidden />
              </span>
              <span>{it.text}</span>
            </li>
          );
        })}
      </ul>
    </Card>
  );
}

function TargetCard({ d, money, onSaved }: { d: Analysis; money: (v: number) => string; onSaved: () => void }) {
  const t = d.target;
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const save = async (target: number | null) => {
    setBusy(true); setError("");
    try {
      await api.put("/api/finance/analysis/target", { target });
      setEditing(false); onSaved();
    } catch (e) { setError(message(e)); } finally { setBusy(false); }
  };
  const goal = t.monthlyTarget;
  const expectedByNow = goal ? Math.round(goal * (t.dayOfMonth / t.daysInMonth)) : null;
  const pct = goal ? (t.monthToDate / goal) * 100 : 0;
  const onTrack = goal ? t.projected >= goal : false;
  return (
    <Card title="Monthly sales target" description={t.month}
      action={!editing && <button onClick={() => { setValue(goal ? String(goal) : ""); setEditing(true); }} className="text-xs font-semibold text-[var(--chart-1)] hover:underline print:hidden">{goal ? "Change" : "Set target"}</button>}>
      {editing ? (
        <form onSubmit={(e) => { e.preventDefault(); const n = Number(value.replace(/[^0-9]/g, "")); if (n > 0) save(n); else setError("Enter an amount in UGX."); }} className="space-y-3">
          <label className="block text-xs font-semibold text-[var(--fg-muted)]">How much do you want to sell this month? (UGX)
            <input inputMode="numeric" autoFocus value={value} onChange={(e) => setValue(e.target.value)} placeholder="e.g. 3000000" className={field} />
          </label>
          {error && <p className="text-xs text-red-700">{error}</p>}
          <div className="flex flex-wrap gap-2">
            <button type="submit" disabled={busy} className={button}>{busy ? "Saving…" : "Save target"}</button>
            <button type="button" onClick={() => setEditing(false)} className={buttonGhost}>Cancel</button>
            {goal && <button type="button" disabled={busy} onClick={() => save(null)} className={buttonGhost}>Remove target</button>}
          </div>
        </form>
      ) : goal ? (
        <Gauge value={t.monthToDate} max={goal} label="Progress to monthly sales target" display={`${pct.toFixed(0)}%`}
          tone={onTrack ? "good" : "warn"} marker={expectedByNow} markerLabel={`Where you should be by day ${t.dayOfMonth}: ${money(expectedByNow || 0)}`}
          caption={<>
            <span className="num font-semibold text-[var(--fg)]">{money(t.monthToDate)}</span> of {money(goal)}<br />
            {onTrack ? "On track to reach it" : `Behind: about ${money(Math.max(0, goal - t.monthToDate))} still to go`} · the black tick is where you should be by today
          </>} />
      ) : (
        <div className="flex flex-col items-center gap-3 py-4 text-center">
          <Target className="h-8 w-8 text-[var(--fg-faint)]" aria-hidden />
          <p className="text-sm text-[var(--fg-muted)]">Set a monthly goal to see how close you are and whether you&apos;re on track.</p>
          <button onClick={() => { setValue(""); setEditing(true); }} className={`${button} print:hidden`}>Set a target</button>
        </div>
      )}
    </Card>
  );
}

function SuccessCard({ d }: { d: Analysis }) {
  const l = d.lost;
  const rate = l.successRate;
  const pct = rate == null ? 0 : rate * 100;
  return (
    <Card title="Payment success rate" description="Mobile money payments that went through">
      {rate == null ? (
        <p className="py-10 text-center text-sm text-[var(--fg-muted)]">No mobile money payments in this period.</p>
      ) : (
        <Gauge value={pct} max={100} label="Payment success rate" display={`${pct.toFixed(0)}%`}
          tone={pct >= 90 ? "good" : pct >= 75 ? "warn" : "bad"}
          caption={<>
            {(l.attempts - l.failed - l.abandoned).toLocaleString()} of {l.attempts.toLocaleString()} payments succeeded
            {l.previousSuccessRate != null && <><br />Previous period: {(l.previousSuccessRate * 100).toFixed(0)}%</>}
            <br />{pct >= 90 ? "Healthy" : pct >= 75 ? "Fair: some customers are struggling to pay" : "Low: many payments are failing"}
          </>} />
      )}
    </Card>
  );
}

function CustomersCard({ d, money, compare }: { d: Analysis; money: (v: number) => string; compare: boolean }) {
  const c = d.customers;
  return (
    <Card title="Customers" description={d.school ? "Customers are counted by phone number. Students are counted but never listed by name here." : "Customers are counted by phone number"} bodyClassName="p-0">
      <div className="grid grid-cols-1 gap-5 p-5 lg:grid-cols-5">
        <div className="space-y-4 lg:col-span-2">
          <div>
            <p className="mb-2 text-xs font-semibold text-[var(--fg-muted)]">Income from new and returning customers</p>
            <StatusBar label="Income from new and returning customers" segments={[
              { key: "new", label: `New (${c.newCustomers})`, value: c.newGross, tone: "info" },
              { key: "ret", label: `Returning (${c.returning})`, value: c.returningGross, tone: "muted" },
            ]} legend={false} />
            <div className="mt-2 flex justify-between text-xs text-[var(--fg-muted)]">
              <span><span className="mr-1.5 inline-block h-2 w-2 rounded-[3px] bg-[var(--chart-1)]" aria-hidden />New · <span className="num font-semibold text-[var(--fg)]">{money(c.newGross)}</span></span>
              <span><span className="mr-1.5 inline-block h-2 w-2 rounded-[3px] bg-[var(--chart-muted)]" aria-hidden />Returning · <span className="num font-semibold text-[var(--fg)]">{money(c.returningGross)}</span></span>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Figure label="Paying customers" value={c.total.toLocaleString()} note={compare ? `Previous period: ${c.previousTotal.toLocaleString()}` : undefined} />
            <Figure label="Bought more than once" value={c.repeatBuyers.toLocaleString()} note={c.total ? `${((c.repeatBuyers / c.total) * 100).toFixed(0)}% of customers` : undefined} />
            <Figure label="New customers" value={c.newCustomers.toLocaleString()} note="First purchase ever" />
            <Figure label="Stopped buying" value={c.lapsed.toLocaleString()} note={c.lapsed ? `Spent ${money(c.lapsedPreviousGross)} the period before` : "Everyone came back"} />
          </div>
        </div>
        <div className="min-w-0 lg:col-span-3">
          <p className="mb-2 text-xs font-semibold text-[var(--fg-muted)]">Top customers</p>
          {c.top.length === 0 ? <p className="py-8 text-center text-sm text-[var(--fg-muted)]">No customers to list in this period.</p> : (
            <div className="overflow-x-auto rounded-lg border border-[var(--border)]">
              <table className="w-full text-left text-[13px]">
                <thead className="bg-[var(--subtle)] text-[11.5px] text-[var(--fg-muted)]">
                  <tr><th className="px-3 py-2 font-semibold">Customer</th><th className="px-3 py-2 text-right font-semibold">Purchases</th><th className="px-3 py-2 text-right font-semibold">Spent</th><th className="hidden px-3 py-2 text-right font-semibold sm:table-cell">Last bought</th></tr>
                </thead>
                <tbody className="divide-y divide-[var(--border-soft)]">
                  {c.top.map((t) => (
                    <tr key={t.phone}>
                      <td className="px-3 py-2"><span className="block font-medium text-[var(--fg)]">{t.name || t.phone}</span>{t.name && <span className="num block text-[11.5px] text-[var(--fg-muted)]">{t.phone}</span>}</td>
                      <td className="num px-3 py-2 text-right">{t.sales}</td>
                      <td className="num px-3 py-2 text-right font-semibold">{money(t.gross)}</td>
                      <td className="hidden px-3 py-2 text-right text-[var(--fg-muted)] sm:table-cell">{t.lastPurchase ? new Date(t.lastPurchase).toLocaleDateString(undefined, { day: "numeric", month: "short" }) : "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </Card>
  );
}
