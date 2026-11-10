"use client";
import { getQueryClient } from "@/lib/queryClient";

import React, { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import api from "@/lib/api";
import {
  ArrowClockwise, ArrowUpRight, Bank, Building2, CellTower, CheckCircle, ClockCounterClockwise, CreditCard,
  GraduationCap, MagnifyingGlass, Money, Radio, ShieldWarning, UserPlus, WifiHigh,
} from "@/components/icons";
import {
  Badge, Card, CardLink, ErrorNotice, PageHeader, Segmented, StatCard, StatusDot, buttonGhost,
  bytes, compactNumber, humanize, relativeTime, ugx, useApi,
} from "@/components/BillingUI";
import { BarChart, MiniBars, StatusBar, type BarDatum, type StatusSegment } from "@/components/charts";
import { DashboardLoading } from "@/components/LoadingUI";
import { routerState, severityTone, summarizeRouters } from "@/lib/routerState";

interface Tenant {
  id: number; name: string; email: string; phone: string; status: string;
  routerCount: number; voucherCount: number; createdAt: string;
}
interface SubscriptionOverview {
  active: number; trialing: number; grace: number; suspended: number; expiringSoon: number;
  overdue: number; outstanding: number; monthRevenue: number; platformFeeMonth: number; platformFeeToday: number;
}
interface FinanceOverview { tenantLiabilities: number; reservedFunds: number; totalCollected: number; pendingWithdrawals: number }
interface PlatformFinance { settledEarnings: number; reservedEarnings: number; safeWithdrawable: number; reconcileStatus: string }
interface Incident {
  id: number; routerId: number; routerName: string; tenantName: string;
  type: string; severity: string; title: string; detail: string | null; openedAt: string;
}
interface NocOverview {
  totalTenants: number; totalRouters: number; onlineRouters: number; offlineRouters: number;
  attentionRouters: number; activeSessions: number; staleOpenSessions: number;
  dataBytesLast24h: number; authFailuresLast24h: number; failedTasksLast7d: number;
  openIncidents: number; incidents: Incident[];
}
interface NocRouter {
  id: number; tenantId: number; tenantName: string; name: string; status: string;
  hotspotReady: boolean | null; radiusReady: boolean | null; portalReady: boolean | null;
  lastSeen: string | null; openIncidents: number;
}

interface Totals { gross: number; sales: number; failed: number; pending: number; platformFee: number }
interface Analytics {
  days: number; timezone: string; generatedAt: string;
  daily: { date: string; gross: number; sales: number; failed: number; platformFee: number }[];
  period: Totals; previousPeriod: Totals; today: Totals; yesterday: Totals;
  networks: { network: string; gross: number; sales: number }[];
  topTenants: { tenantId: number; name: string; school: boolean; gross: number; sales: number; platformFee: number; lastSaleAt: string | null }[];
  dormant: { tenantId: number; name: string; school: boolean; routers: number; lastSaleAt: string | null; joinedAt: string | null }[];
  dormantCount: number;
  tenantMix: { hotspot: number; school: number; active: number; suspended: number; disabled: number; withRouters: number; withoutRouters: number };
  subscriptionFlags: { tenantId: number; name: string; status: string; expiresAt: string | null; graceUntil: string | null }[];
  activity: { id: number; action: string; actor: string | null; tenantId: number | null; tenantName: string | null; details: string | null; ipAddress: string | null; at: string }[];
}

interface Data {
  tenants: Tenant[] | null;
  subscriptions: SubscriptionOverview | null;
  finance: FinanceOverview | null;
  platform: PlatformFinance | null;
  noc: NocOverview | null;
  routers: NocRouter[] | null;
}

const SOURCES = [
  ["tenants", "/api/admin/tenants", "tenants"],
  ["subscriptions", "/api/admin/subscriptions/overview", "subscriptions"],
  ["finance", "/api/admin/finance/overview", "tenant finance"],
  ["platform", "/api/admin/finance/platform", "platform earnings"],
  ["noc", "/api/admin/noc/overview", "network overview"],
  ["routers", "/api/admin/noc/routers", "router fleet"],
] as const;

const REFRESH_MS = 60000;

type Range = "7" | "30" | "90";
type Metric = "gross" | "sales" | "platformFee";
const RANGES: { key: Range; label: string }[] = [{ key: "7", label: "7 days" }, { key: "30", label: "30 days" }, { key: "90", label: "90 days" }];
const METRICS: { key: Metric; label: string }[] = [{ key: "gross", label: "Volume" }, { key: "sales", label: "Payments" }, { key: "platformFee", label: "Platform fees" }];

const reconTone = (s: string) => (s === "CLEAN" ? "green" : s === "DISCREPANCY" ? "red" : "amber");
const statusTone = (s: string) => (s === "ACTIVE" ? "green" : s === "SUSPENDED" ? "amber" : "red");
const subFlagTone = (s: string) => (s === "SUSPENDED" ? "red" : s === "GRACE_PERIOD" ? "amber" : "blue");
const NETWORK_COLORS: Record<string, string> = { MTN: "#F5B800", AIRTEL: "#E3262E", CASH: "var(--status-good)" };

function monthlyOnboarding(tenants: Tenant[], now: Date) {
  const months = Array.from({ length: 12 }, (_, i) => new Date(now.getFullYear(), now.getMonth() - 11 + i, 1));
  return months.map((m) => {
    const value = tenants.filter((t) => {
      const d = new Date(t.createdAt);
      return d.getFullYear() === m.getFullYear() && d.getMonth() === m.getMonth();
    }).length;
    return {
      key: `${m.getFullYear()}-${m.getMonth()}`,
      label: m.toLocaleDateString(undefined, { month: "short" }),
      value,
      detail: `${m.toLocaleDateString(undefined, { month: "long", year: "numeric" })} · ${value} new`,
    };
  });
}

/** Parse a server "YYYY-MM-DD" as a local calendar date (no UTC shift). */
function calendarDay(iso: string) {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d);
}

/** Percent change, or null when there is no base to compare with. */
function change(current: number, previous: number) {
  if (previous <= 0) return current > 0 ? null : 0;
  return ((current - previous) / previous) * 100;
}

function Delta({ current, previous, against }: { current: number; previous: number; against: string }) {
  const pct = change(current, previous);
  if (pct === null) return <span className="text-[var(--fg-faint)]">No sales {against} to compare</span>;
  const rounded = Math.round(pct);
  const tone = rounded > 0 ? "text-emerald-700 dark:text-emerald-400" : rounded < 0 ? "text-red-700 dark:text-red-400" : "text-[var(--fg-muted)]";
  return (
    <span>
      <span className={`num font-semibold ${tone}`}>{rounded > 0 ? "▲" : rounded < 0 ? "▼" : "•"} {Math.abs(rounded)}%</span>
      <span className="text-[var(--fg-muted)]"> vs {against}</span>
    </span>
  );
}

interface AttentionItem { key: string; tone: string; label: string; title: string; context: string; at: string | null; href: string }

/** Open incidents first, then routers whose current state needs a look but
 *  which have no incident yet — offline, degraded, or reporting stale data. */
function attentionItems(noc: NocOverview | null, routers: NocRouter[] | null, now: number): AttentionItem[] {
  const items: AttentionItem[] = (noc?.incidents || []).map((i) => ({
    key: `i-${i.id}`, tone: severityTone(i.severity), label: humanize(i.severity), title: i.title,
    context: `${i.tenantName} · ${i.routerName}`, at: i.openedAt, href: `/admin/network/routers/${i.routerId}`,
  }));
  const covered = new Set((noc?.incidents || []).map((i) => i.routerId));
  for (const r of routers || []) {
    if (covered.has(r.id)) continue;
    const state = routerState(r, now).state;
    const services = [r.hotspotReady, r.radiusReady, r.portalReady].some((v) => v === false);
    let entry: Pick<AttentionItem, "tone" | "label" | "title"> | null = null;
    if (state === "offline") entry = { tone: "red", label: "Offline", title: "Router is offline" };
    else if (state === "stale") entry = { tone: "amber", label: "Stale", title: "Stored status is online, but reports stopped" };
    else if (state === "degraded") entry = { tone: "amber", label: "Degraded", title: "Router reports a service not ready" };
    else if (state === "online" && services) entry = { tone: "amber", label: "Services", title: "Hotspot, RADIUS or portal not ready" };
    if (entry) items.push({ key: `r-${r.id}`, ...entry, context: `${r.tenantName} · ${r.name}`, at: r.lastSeen, href: `/admin/network/routers/${r.id}` });
  }
  const rank = (t: string) => (t === "red" ? 0 : t === "amber" ? 1 : 2);
  return items.sort((a, b) => rank(a.tone) - rank(b.tone));
}

/** Support lookup: hand the query to the transaction search page. */
function SupportSearch() {
  const router = useRouter();
  const [q, setQ] = useState("");
  return (
    <form
      role="search"
      onSubmit={(e) => { e.preventDefault(); if (q.trim()) router.push(`/admin/transactions?q=${encodeURIComponent(q.trim())}`); }}
      className="flex min-w-0 flex-1 items-center gap-2 rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] px-3 focus-within:border-blue-600 focus-within:ring-2 focus-within:ring-blue-600/15"
    >
      <MagnifyingGlass className="h-4 w-4 shrink-0 text-[var(--fg-muted)]" aria-hidden />
      <label htmlFor="support-search" className="sr-only">Find a payment</label>
      <input
        id="support-search" value={q} onChange={(e) => setQ(e.target.value)}
        placeholder="Find a payment: phone, voucher or reference"
        className="min-h-10 min-w-0 flex-1 bg-transparent text-sm text-[var(--fg)] outline-none placeholder:text-[var(--fg-muted)]"
      />
      <button type="submit" disabled={!q.trim()} className="rounded-md px-2 py-1 text-xs font-semibold text-[var(--accent-text)] disabled:opacity-40">Search</button>
    </form>
  );
}

function QuickAction({ href, icon, label, count, tone = "default" }: { href: string; icon: React.ReactNode; label: string; count?: number; tone?: "default" | "warn" }) {
  return (
    <Link href={href} className="inline-flex min-h-10 items-center gap-2 whitespace-nowrap rounded-lg border border-[var(--border)] bg-[var(--surface)] px-3 text-[12.5px] font-medium text-[var(--fg-body)] transition-colors hover:border-[var(--border-strong)] hover:bg-[var(--subtle)] [&_svg]:h-4 [&_svg]:w-4 [&_svg]:text-[var(--fg-muted)]">
      {icon}{label}
      {!!count && <span className={`num rounded-full px-1.5 py-px text-[11px] font-semibold ${tone === "warn" ? "bg-amber-100 text-amber-800 dark:bg-amber-500/15 dark:text-amber-300" : "bg-[var(--fill)] text-[var(--fg)]"}`}>{count}</span>}
    </Link>
  );
}

export default function AdminOverviewPage() {
  const [data, setData] = useState<Data | null>(null);
  const [failed, setFailed] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const [range, setRange] = useState<Range>("30");
  const [metric, setMetric] = useState<Metric>("gross");

  const analytics = useApi<Analytics>(`/api/admin/analytics/overview?days=${range}`, REFRESH_MS, { keepPrevious: true });
  const a = analytics.data;

  const qc = getQueryClient();
  const load = useCallback(async (isBackground = false) => {
    if (!isBackground) setLoading(true);
    const results = await Promise.allSettled(SOURCES.map(async ([, url]) => {
      return qc.fetchQuery({
        queryKey: ["superadmin", url],
        queryFn: async () => (await api.get(url)).data,
        staleTime: 15000,
      });
    }));
    setData((prev) => {
      const next = { ...(prev || {}) } as Record<string, unknown>;
      results.forEach((r, i) => {
        if (r.status === "fulfilled") next[SOURCES[i][0]] = r.value;
        else if (!(SOURCES[i][0] in next)) next[SOURCES[i][0]] = null;
      });
      return next as unknown as Data;
    });
    setFailed(results.flatMap((r, i) => (r.status === "rejected" ? [SOURCES[i][2]] : [])));
    setUpdatedAt(Date.now());
    setLoading(false);
  }, [qc]);

  const refreshAll = useCallback(() => { void load(false); void analytics.load(); }, [load, analytics]);

  useEffect(() => {
    // If preloaded data exists in query cache, immediately render it (SWR)
    const cached: Record<string, unknown> = {};
    let hasAnyCache = false;
    SOURCES.forEach(([key, url]) => {
      const val = qc.getQueryData(["superadmin", url]);
      if (val !== undefined) {
        cached[key] = val;
        hasAnyCache = true;
      }
    });
    if (hasAnyCache) {
      setData((prev) => ({ ...(prev || {}), ...cached } as unknown as Data));
      setLoading(false);
    }

    const initial = window.setTimeout(() => load(hasAnyCache), 0);
    const timer = window.setInterval(() => { if (document.visibilityState === "visible") void load(true); }, REFRESH_MS);
    return () => { window.clearTimeout(initial); window.clearInterval(timer); };
  }, [load, qc]);

  const now = updatedAt ?? 0;
  const tenants = data?.tenants;
  const noc = data?.noc;
  const routers = data?.routers;

  const tenantCounts = useMemo(() => {
    const list = tenants || [];
    return {
      total: list.length,
      active: list.filter((t) => t.status === "ACTIVE").length,
      suspended: list.filter((t) => t.status === "SUSPENDED").length,
      disabled: list.filter((t) => t.status !== "ACTIVE" && t.status !== "SUSPENDED").length,
      last30: list.filter((t) => now - new Date(t.createdAt).getTime() < 30 * 86400000).length,
    };
  }, [tenants, now]);

  const onboarding = useMemo(() => (tenants && now ? monthlyOnboarding(tenants, new Date(now)) : []), [tenants, now]);

  const fleet: StatusSegment[] = useMemo(() => {
    const counts = { online: 0, degraded: 0, stale: 0, offline: 0, other: 0 };
    (routers || []).forEach((r) => {
      const st = routerState(r, now).state;
      if (st === "online" || st === "degraded" || st === "stale" || st === "offline") counts[st] += 1; else counts.other += 1;
    });
    return [
      { key: "online", label: "Online", value: counts.online, tone: "good" },
      { key: "degraded", label: "Degraded", value: counts.degraded, tone: "warn" },
      { key: "stale", label: "Stale", value: counts.stale, tone: "warn" },
      { key: "offline", label: "Offline", value: counts.offline, tone: "bad" },
      { key: "other", label: "Not yet online", value: counts.other, tone: "idle" },
    ];
  }, [routers, now]);

  const attention = useMemo(() => attentionItems(noc ?? null, routers ?? null, now), [noc, routers, now]);
  const fleetByTenant = useMemo(() => {
    const groups = new Map<number, NocRouter[]>();
    (routers || []).forEach((r) => groups.set(r.tenantId, [...(groups.get(r.tenantId) ?? []), r]));
    return new Map([...groups].map(([id, rs]) => [id, summarizeRouters(rs, now)]));
  }, [routers, now]);
  const recent = useMemo(() => [...(tenants || [])].sort((x, y) => new Date(y.createdAt).getTime() - new Date(x.createdAt).getTime()).slice(0, 6), [tenants]);

  const volumeBars: BarDatum[] = useMemo(() => (a?.daily || []).map((d) => {
    const day = calendarDay(d.date);
    const value = d[metric];
    const shown = metric === "sales" ? `${value.toLocaleString()} payments` : ugx(value);
    return {
      key: d.date,
      label: day.toLocaleDateString(undefined, { day: "numeric", month: "short" }),
      value,
      detail: `${day.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "long" })} · ${shown}${d.failed ? ` · ${d.failed} failed` : ""}`,
    };
  }), [a, metric]);

  const weekBars: BarDatum[] = useMemo(() => (a?.daily || []).slice(-7).map((d) => {
    const day = calendarDay(d.date);
    return { key: d.date, label: day.toLocaleDateString(undefined, { weekday: "narrow" }), value: d.gross, detail: day.toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "short" }) };
  }), [a]);

  if (loading && !data) return <DashboardLoading label="Loading platform data…" />;

  const subs = data?.subscriptions;
  const fin = data?.finance;
  const plat = data?.platform;
  const dash = "—";
  const period = a?.period;
  const attempts = period ? period.sales + period.failed : 0;
  const successRate = period && attempts > 0 ? (period.sales / attempts) * 100 : null;
  const avgTicket = period && period.sales > 0 ? Math.round(period.gross / period.sales) : 0;
  const platformMonth = subs ? (subs.monthRevenue || 0) + (subs.platformFeeMonth || 0) : null;
  const rangeLabel = RANGES.find((r) => r.key === range)?.label ?? `${range} days`;
  const atRiskCount = (a?.dormantCount || 0) + (a?.subscriptionFlags.length || 0);

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <PageHeader
        title="Command center"
        description="Platform health, tenant growth and revenue across every AirPairy business."
        meta={updatedAt && <>
          <span className="inline-flex items-center gap-1.5"><span className="h-1.5 w-1.5 rounded-full bg-[var(--status-good)]" aria-hidden />Updated {new Date(updatedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</span>
          <span>Refreshes every minute</span>
          {a && <span>Days in {a.timezone.replace("_", " ")}</span>}
        </>}
        action={<button disabled={loading} onClick={refreshAll} className={buttonGhost}><ArrowClockwise className={`h-4 w-4 ${loading || analytics.refreshing ? "animate-spin motion-reduce:animate-none" : ""}`} />{loading ? "Refreshing…" : "Refresh"}</button>}
      />

      {failed.length > 0 && <ErrorNotice text={`Couldn't load ${failed.join(", ")}. Those sections show the last data available or a dash.`} />}
      {analytics.error && !a && <ErrorNotice text={`Couldn't load payment analytics: ${analytics.error}`} />}

      {/* Support lookup and the jobs a superadmin does most. */}
      <div className="flex flex-col gap-3 lg:flex-row lg:items-center">
        <SupportSearch />
        <div className="-mx-1 flex gap-2 overflow-x-auto px-1 pb-0.5">
          <QuickAction href="/admin/tenants?new=1" icon={<UserPlus />} label="Add business" />
          <QuickAction href="/admin/finance" icon={<Bank />} label="Withdrawals" count={fin?.pendingWithdrawals} tone="warn" />
          <QuickAction href="/admin/subscriptions" icon={<CreditCard />} label="Billing" count={subs?.overdue} tone="warn" />
          <QuickAction href="/admin/network" icon={<Radio />} label="Incidents" count={noc?.openIncidents} tone="warn" />
        </div>
      </div>

      {/* Headline KPIs: one per domain, each drilling into its console. */}
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Businesses" href="/admin/tenants" icon={<Building2 />}
          value={tenants ? tenantCounts.total : dash}
          hint={tenants ? <>
            {tenantCounts.active} active · {tenantCounts.suspended} suspended{tenantCounts.disabled ? ` · ${tenantCounts.disabled} disabled` : ""}
            {a && <span className="block">{a.tenantMix.hotspot} hotspot · {a.tenantMix.school} school{a.tenantMix.withoutRouters ? ` · ${a.tenantMix.withoutRouters} without a router` : ""}</span>}
          </> : "Tenant list unavailable"}
        />
        <StatCard
          label="Routers online" href="/admin/routers" icon={<CellTower />}
          tone={noc && noc.offlineRouters > 0 ? "amber" : "default"}
          value={noc ? <>{noc.onlineRouters}<span className="text-[var(--fg-faint)]"> / {noc.totalRouters}</span></> : dash}
          hint={noc ? `${noc.offlineRouters} offline · ${noc.attentionRouters} need attention` : "Network overview unavailable"}
        >
          {routers && routers.length > 0 && <StatusBar segments={fleet} label="Router status" legend={false} />}
        </StatCard>
        <StatCard
          label="Live WiFi sessions" href="/admin/network" icon={<WifiHigh />}
          value={noc ? noc.activeSessions.toLocaleString() : dash}
          hint={noc ? `${bytes(noc.dataBytesLast24h)} transferred in 24h` : "Network overview unavailable"}
        />
        <StatCard
          label="Platform revenue" href="/admin/subscriptions" icon={<CreditCard />}
          tone={subs?.overdue ? "amber" : "default"}
          value={platformMonth != null ? ugx(platformMonth) : dash}
          hint={subs ? <>
            This month · {ugx(subs.monthRevenue)} subscriptions + {ugx(subs.platformFeeMonth)} fees
            <span className="block">{subs.active} active · {subs.overdue} overdue{subs.expiringSoon ? ` · ${subs.expiringSoon} expiring soon` : ""}</span>
          </> : "Subscriptions unavailable"}
        />
      </div>

      {/* Payment KPIs across every tenant. */}
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Payments today" href="/admin/transactions" icon={<Money />} loading={analytics.loading && !a}
          value={a ? ugx(a.today.gross) : dash}
          hint={a ? <>{a.today.sales.toLocaleString()} paid{a.today.pending ? ` · ${a.today.pending} pending` : ""}{a.today.failed ? ` · ${a.today.failed} failed` : ""}<span className="block"><Delta current={a.today.gross} previous={a.yesterday.gross} against="yesterday" /></span></> : "Analytics unavailable"}
        >
          {weekBars.length > 0 && <MiniBars data={weekBars} label="Payment volume, last 7 days" format={ugx} height={40} />}
        </StatCard>
        <StatCard
          label={`Volume, last ${rangeLabel}`} icon={<ArrowUpRight />} loading={analytics.loading && !a}
          value={period ? ugx(period.gross) : dash}
          hint={a && period ? <>{period.sales.toLocaleString()} payments · avg {ugx(avgTicket)}<span className="block"><Delta current={period.gross} previous={a.previousPeriod.gross} against={`previous ${rangeLabel}`} /></span></> : "Analytics unavailable"}
        />
        <StatCard
          label="Payment success rate" href="/admin/transactions" icon={<CheckCircle />} loading={analytics.loading && !a}
          tone={successRate != null && successRate < 80 ? "red" : successRate != null && successRate < 92 ? "amber" : "default"}
          value={successRate != null ? `${successRate.toFixed(1)}%` : dash}
          hint={period ? `${period.failed.toLocaleString()} failed of ${attempts.toLocaleString()} attempts · ${rangeLabel}` : "Analytics unavailable"}
        >
          {successRate != null && (
            <div className="h-1.5 w-full overflow-hidden rounded-full bg-[var(--fill)]" role="meter" aria-label="Payment success rate" aria-valuenow={Math.round(successRate)} aria-valuemin={0} aria-valuemax={100}>
              <div className="h-full rounded-full" style={{ width: `${successRate}%`, background: successRate < 80 ? "var(--status-bad)" : successRate < 92 ? "var(--status-warn)" : "var(--status-good)" }} />
            </div>
          )}
        </StatCard>
        <StatCard
          label={`Platform fees, last ${rangeLabel}`} href="/admin/finance" icon={<Bank />} loading={analytics.loading && !a}
          value={period ? ugx(period.platformFee) : dash}
          hint={a && period ? <>Today {ugx(a.today.platformFee)}<span className="block"><Delta current={period.platformFee} previous={a.previousPeriod.platformFee} against={`previous ${rangeLabel}`} /></span></> : "Analytics unavailable"}
        />
      </div>

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
        <Card
          className="lg:col-span-2"
          title="Payment volume"
          description={`Successful payments across all businesses, per day · last ${rangeLabel}`}
          action={<div className="flex flex-wrap gap-2">
            <Segmented label="Metric" options={METRICS} value={metric} onChange={setMetric} />
            <Segmented label="Period" options={RANGES} value={range} onChange={setRange} />
          </div>}
        >
          {!a ? (
            <p className="py-16 text-center text-sm text-[var(--fg-muted)]">{analytics.loading ? "Loading payments…" : "Payment analytics are unavailable."}</p>
          ) : a.period.sales === 0 && a.period.failed === 0 ? (
            <p className="py-16 text-center text-sm text-[var(--fg-muted)]">No payments in the last {rangeLabel}.</p>
          ) : (
            <BarChart
              data={volumeBars} label={`${METRICS.find((m) => m.key === metric)?.label} per day`} height={320}
              format={metric === "sales" ? (v) => `${v.toLocaleString()} payments` : ugx}
              tickFormat={metric === "sales" ? undefined : (v) => compactNumber(v)}
              integer={metric === "sales"} dim={analytics.placeholder || analytics.refreshing}
            />
          )}
        </Card>

        <Card title="Payment mix" description={`By mobile-money network · last ${rangeLabel}`} action={<CardLink href="/admin/transactions">Transactions</CardLink>}>
          {!a ? (
            <p className="text-sm text-[var(--fg-muted)]">Analytics unavailable.</p>
          ) : a.networks.length === 0 ? (
            <p className="py-10 text-center text-sm text-[var(--fg-muted)]">No successful payments yet.</p>
          ) : (
            <ul className="space-y-3.5" aria-label="Payment volume by network">
              {a.networks.map((n) => {
                const share = a.period.gross > 0 ? (n.gross / a.period.gross) * 100 : 0;
                return (
                  <li key={n.network}>
                    <div className="flex items-baseline justify-between gap-3">
                      <span className="flex items-center gap-2 text-[13px] font-medium text-[var(--fg)]">
                        <span className="h-2.5 w-2.5 rounded-[3px]" style={{ background: NETWORK_COLORS[n.network] ?? "var(--chart-1)" }} aria-hidden />
                        {n.network === "MTN" ? "MTN" : humanize(n.network)}
                      </span>
                      <span className="num text-[13px] font-semibold text-[var(--fg)]">{ugx(n.gross)}</span>
                    </div>
                    <div className="mt-1.5 h-1.5 w-full overflow-hidden rounded-full bg-[var(--fill)]">
                      <div className="h-full rounded-full" style={{ width: `${share}%`, background: NETWORK_COLORS[n.network] ?? "var(--chart-1)" }} />
                    </div>
                    <p className="num mt-1 text-[11.5px] text-[var(--fg-muted)]">{share.toFixed(share < 10 ? 1 : 0)}% · {n.sales.toLocaleString()} payments</p>
                  </li>
                );
              })}
            </ul>
          )}
          {a && (
            <dl className="mt-5 divide-y divide-[var(--border-soft)] border-t border-[var(--border)]">
              {[
                { label: "Successful", value: a.period.sales, tone: "bg-[var(--status-good)]" },
                { label: "Failed", value: a.period.failed, tone: "bg-[var(--status-bad)]" },
                { label: "Still pending", value: a.period.pending, tone: "bg-[var(--status-warn)]" },
              ].map((row) => (
                <div key={row.label} className="flex items-center justify-between gap-3 py-2.5">
                  <dt className="flex items-center gap-2 text-[13px] text-[var(--fg-muted)]"><span className={`h-2 w-2 rounded-[3px] ${row.tone}`} aria-hidden />{row.label}</dt>
                  <dd className="num text-sm font-semibold text-[var(--fg)]">{row.value.toLocaleString()}</dd>
                </div>
              ))}
            </dl>
          )}
        </Card>
      </div>

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
        <Card
          className="lg:col-span-2"
          title="Top businesses"
          description={`Ranked by customer payment volume · last ${rangeLabel}`}
          action={<CardLink href="/admin/tenants">All tenants</CardLink>}
          bodyClassName=""
        >
          {!a ? (
            <p className="px-5 py-12 text-center text-sm text-[var(--fg-muted)]">Analytics unavailable.</p>
          ) : a.topTenants.length === 0 ? (
            <p className="px-5 py-12 text-center text-sm text-[var(--fg-muted)]">No business has sold anything in the last {rangeLabel}.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[600px] text-left text-sm">
                <thead className="border-b border-[var(--border)] bg-[var(--subtle)] text-xs text-[var(--fg-muted)]">
                  <tr>
                    <th scope="col" className="w-10 px-5 py-2.5 font-medium">#</th>
                    <th scope="col" className="px-2 py-2.5 font-medium">Business</th>
                    <th scope="col" className="px-4 py-2.5 font-medium">Share of volume</th>
                    <th scope="col" className="px-4 py-2.5 text-right font-medium">Payments</th>
                    <th scope="col" className="px-4 py-2.5 text-right font-medium">Volume</th>
                    <th scope="col" className="px-5 py-2.5 text-right font-medium">Last sale</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--border-soft)]">
                  {a.topTenants.map((t, i) => {
                    const share = a.period.gross > 0 ? (t.gross / a.period.gross) * 100 : 0;
                    return (
                      <tr key={t.tenantId} className="transition-colors hover:bg-[var(--subtle)]">
                        <td className="num px-5 py-3 text-xs text-[var(--fg-faint)]">{i + 1}</td>
                        <td className="px-2 py-3">
                          <Link href={`/admin/tenants/${t.tenantId}`} className="inline-flex items-center gap-1.5 font-medium text-[var(--fg)] hover:text-[var(--accent-text)] hover:underline">
                            {t.name}{t.school && <GraduationCap className="h-3.5 w-3.5 text-[var(--fg-muted)]" aria-label="School" />}
                          </Link>
                          {t.platformFee > 0 && <span className="block text-xs text-[var(--fg-muted)]">{ugx(t.platformFee)} platform fees</span>}
                        </td>
                        <td className="px-4 py-3">
                          <span className="flex items-center gap-2">
                            <span className="h-1.5 w-24 overflow-hidden rounded-full bg-[var(--fill)]"><span className="block h-full rounded-full bg-[var(--chart-1)]" style={{ width: `${share}%` }} /></span>
                            <span className="num text-xs text-[var(--fg-muted)]">{share.toFixed(share < 10 ? 1 : 0)}%</span>
                          </span>
                        </td>
                        <td className="num px-4 py-3 text-right text-[var(--fg-body)]">{t.sales.toLocaleString()}</td>
                        <td className="num px-4 py-3 text-right font-medium text-[var(--fg)]">{ugx(t.gross)}</td>
                        <td className="px-5 py-3 text-right text-xs text-[var(--fg-muted)]" title={t.lastSaleAt ? new Date(t.lastSaleAt).toLocaleString() : undefined}>{relativeTime(t.lastSaleAt, now)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <Card title="Money" description="Platform earnings and funds held for tenants" action={<CardLink href="/admin/finance">Finance</CardLink>} bodyClassName="px-5 pb-4">
          <dl className="divide-y divide-[var(--border-soft)]">
            {[
              { label: "Platform earnings available", value: plat ? ugx(plat.safeWithdrawable) : dash, extra: plat && <Badge tone={reconTone(plat.reconcileStatus)} dot>{humanize(plat.reconcileStatus)}</Badge> },
              { label: "Tenant wallet liabilities", value: fin ? ugx(fin.tenantLiabilities + fin.reservedFunds) : dash, note: "Available plus reserved" },
              { label: "Collected for tenants", value: fin ? ugx(fin.totalCollected) : dash, note: "Not platform revenue" },
              { label: "Outstanding invoices", value: subs ? ugx(subs.outstanding) : dash, warn: !!subs?.outstanding },
              { label: "Pending withdrawals", value: fin ? fin.pendingWithdrawals.toLocaleString() : dash, warn: !!fin?.pendingWithdrawals },
            ].map((row) => (
              <div key={row.label} className="py-3">
                <dt className="flex items-center justify-between gap-2 text-[13px] text-[var(--fg-muted)]">{row.label}{row.extra}</dt>
                <dd className="num mt-0.5 flex items-center gap-2 text-[15px] font-semibold text-[var(--fg)]">
                  {row.warn && <span className="h-1.5 w-1.5 rounded-full bg-[var(--status-warn)]" aria-hidden />}
                  {row.value}
                  {row.note && <span className="text-[11.5px] font-normal text-[var(--fg-faint)]">{row.note}</span>}
                </dd>
              </div>
            ))}
          </dl>
        </Card>
      </div>

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
        <Card
          className="lg:col-span-2"
          title="Needs attention"
          description="Open incidents, then routers that are offline, degraded or have stopped reporting"
          action={attention.length > 0 && <span className="num text-xs font-medium text-[var(--fg-muted)]">{attention.length} item{attention.length === 1 ? "" : "s"}</span>}
          bodyClassName=""
        >
          {!noc && !routers ? (
            <p className="px-5 py-12 text-center text-sm text-[var(--fg-muted)]">Network data is unavailable.</p>
          ) : attention.length === 0 ? (
            <div className="flex flex-col items-center gap-2 px-5 py-12 text-center">
              <span className="flex h-10 w-10 items-center justify-center rounded-full bg-emerald-50 text-emerald-700"><CheckCircle className="h-5 w-5" /></span>
              <p className="text-sm font-medium text-[var(--fg-body)]">All clear</p>
              <p className="text-xs text-[var(--fg-muted)]">No open incidents and every router is reporting.</p>
            </div>
          ) : (
            <>
              <ul className="divide-y divide-[var(--border-soft)]">
                {attention.slice(0, 7).map((item) => (
                  <li key={item.key}>
                    <Link href={item.href} className="flex items-center gap-3 px-5 py-3 transition-colors hover:bg-[var(--subtle)]">
                      <span className="w-[5.5rem] shrink-0"><Badge tone={item.tone} dot>{item.label}</Badge></span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-[13.5px] font-medium text-[var(--fg)]">{item.title}</span>
                        <span className="block truncate text-xs text-[var(--fg-muted)]">{item.context}</span>
                      </span>
                      <span className="hidden shrink-0 text-xs text-[var(--fg-faint)] sm:block" title={item.at ? new Date(item.at).toLocaleString() : undefined}>{relativeTime(item.at, now)}</span>
                    </Link>
                  </li>
                ))}
              </ul>
              {attention.length > 7 && <div className="border-t border-[var(--border)] px-5 py-3"><CardLink href="/admin/network">See all {attention.length} in Network operations</CardLink></div>}
            </>
          )}
        </Card>

        <Card title="Fleet connectivity" description="Last reported router status" action={<CardLink href="/admin/network">Network ops</CardLink>}>
          {routers && routers.length > 0 ? (
            <StatusBar segments={fleet} label="Router status" />
          ) : (
            <p className="text-sm text-[var(--fg-muted)]">{routers ? "No routers registered yet." : "Router fleet unavailable."}</p>
          )}
          <dl className="mt-5 divide-y divide-[var(--border-soft)] border-t border-[var(--border)]">
            {[
              { label: "Open incidents", value: noc?.openIncidents, bad: true },
              { label: "Auth failures (24h)", value: noc?.authFailuresLast24h },
              { label: "Failed router tasks (7d)", value: noc?.failedTasksLast7d },
              { label: "Open sessions with stale data", value: noc?.staleOpenSessions },
            ].map((row) => (
              <div key={row.label} className="flex items-center justify-between gap-3 py-2.5">
                <dt className="text-[13px] text-[var(--fg-muted)]">{row.label}</dt>
                <dd className="num flex items-center gap-2 text-sm font-semibold text-[var(--fg)]">
                  {row.value != null && row.value > 0 && <span className={`h-1.5 w-1.5 rounded-full ${row.bad ? "bg-[var(--status-bad)]" : "bg-[var(--status-warn)]"}`} aria-hidden />}
                  {row.value ?? dash}
                </dd>
              </div>
            ))}
          </dl>
          <p className="mt-3 text-[11.5px] leading-relaxed text-[var(--fg-faint)]">
            WireGuard, RADIUS and database probes aren&apos;t connected, so service health isn&apos;t shown here. <Link href="/admin/health" className="underline hover:text-[var(--fg-muted)]">System health</Link>
          </p>
        </Card>
      </div>

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
        <Card
          title="Businesses at risk"
          description="Accounts that may churn: no sales for a week, or a subscription that is lapsing"
          action={a && atRiskCount > 0 && <span className="num text-xs font-medium text-[var(--fg-muted)]">{atRiskCount} account{atRiskCount === 1 ? "" : "s"}</span>}
          bodyClassName=""
        >
          {!a ? (
            <p className="px-5 py-12 text-center text-sm text-[var(--fg-muted)]">Analytics unavailable.</p>
          ) : atRiskCount === 0 ? (
            <div className="flex flex-col items-center gap-2 px-5 py-12 text-center">
              <span className="flex h-10 w-10 items-center justify-center rounded-full bg-emerald-50 text-emerald-700"><CheckCircle className="h-5 w-5" /></span>
              <p className="text-sm font-medium text-[var(--fg-body)]">Every business is selling</p>
              <p className="text-xs text-[var(--fg-muted)]">No dormant routers and no lapsing subscriptions.</p>
            </div>
          ) : (
            <ul className="divide-y divide-[var(--border-soft)]">
              {a.subscriptionFlags.map((s) => (
                <li key={`s-${s.tenantId}`}>
                  <Link href={`/admin/tenants/${s.tenantId}`} className="flex items-center gap-3 px-5 py-3 transition-colors hover:bg-[var(--subtle)]">
                    <span className="w-[6.5rem] shrink-0"><Badge tone={subFlagTone(s.status)} dot>{s.status === "GRACE_PERIOD" ? "Grace" : s.status === "SUSPENDED" ? "Suspended" : "Expiring"}</Badge></span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[13.5px] font-medium text-[var(--fg)]">{s.name}</span>
                      <span className="block truncate text-xs text-[var(--fg-muted)]">
                        {s.status === "GRACE_PERIOD" && s.graceUntil ? `Grace ends ${new Date(s.graceUntil).toLocaleDateString()}`
                          : s.status === "SUSPENDED" ? "Subscription suspended for non-payment"
                          : s.expiresAt ? `Subscription expires ${new Date(s.expiresAt).toLocaleDateString()}` : "Subscription needs attention"}
                      </span>
                    </span>
                  </Link>
                </li>
              ))}
              {a.dormant.map((d) => (
                <li key={`d-${d.tenantId}`}>
                  <Link href={`/admin/tenants/${d.tenantId}`} className="flex items-center gap-3 px-5 py-3 transition-colors hover:bg-[var(--subtle)]">
                    <span className="w-[6.5rem] shrink-0"><Badge tone="slate" dot>{d.lastSaleAt ? "No sales" : "Never sold"}</Badge></span>
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-1.5 truncate text-[13.5px] font-medium text-[var(--fg)]">{d.name}{d.school && <GraduationCap className="h-3.5 w-3.5 text-[var(--fg-muted)]" aria-label="School" />}</span>
                      <span className="block truncate text-xs text-[var(--fg-muted)]">{d.routers} router{d.routers === 1 ? "" : "s"} · {d.lastSaleAt ? `last sale ${relativeTime(d.lastSaleAt, now)}` : `joined ${relativeTime(d.joinedAt, now)}`}</span>
                    </span>
                  </Link>
                </li>
              ))}
              {a.dormantCount > a.dormant.length && (
                <li className="px-5 py-3 text-xs text-[var(--fg-muted)]">and {a.dormantCount - a.dormant.length} more with no sales for 7 days</li>
              )}
            </ul>
          )}
        </Card>

        <Card
          title="Recent activity"
          description="Sign-ins, security changes and admin actions across the platform"
          action={<CardLink href="/admin/settings">Audit log</CardLink>}
          bodyClassName=""
        >
          {!a ? (
            <p className="px-5 py-12 text-center text-sm text-[var(--fg-muted)]">Analytics unavailable.</p>
          ) : a.activity.length === 0 ? (
            <p className="px-5 py-12 text-center text-sm text-[var(--fg-muted)]">No activity recorded yet.</p>
          ) : (
            <ol className="divide-y divide-[var(--border-soft)]">
              {a.activity.slice(0, 8).map((e) => (
                <li key={e.id} className="flex items-start gap-3 px-5 py-3">
                  <span className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-[var(--fill)] text-[var(--fg-muted)]"><ClockCounterClockwise className="h-3.5 w-3.5" aria-hidden /></span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[13.5px] font-medium text-[var(--fg)]">{humanize(e.action)}</span>
                    <span className="block truncate text-xs text-[var(--fg-muted)]" title={e.details || undefined}>
                      {[e.tenantName, e.actor, e.details].filter(Boolean).join(" · ") || "System"}
                    </span>
                  </span>
                  <span className="shrink-0 text-xs text-[var(--fg-faint)]" title={new Date(e.at).toLocaleString()}>{relativeTime(e.at, now)}</span>
                </li>
              ))}
            </ol>
          )}
        </Card>
      </div>

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
        <Card
          title="Tenant onboarding"
          description="New businesses per month, last 12 months"
          action={tenants && <span className="text-xs text-[var(--fg-muted)]"><span className="num font-semibold text-[var(--fg)]">+{tenantCounts.last30}</span> in 30 days</span>}
        >
          {!tenants ? (
            <p className="py-16 text-center text-sm text-[var(--fg-muted)]">Tenant data is unavailable.</p>
          ) : onboarding.every((m) => m.value === 0) ? (
            <p className="py-16 text-center text-sm text-[var(--fg-muted)]">No businesses registered in the last 12 months.</p>
          ) : (
            <BarChart data={onboarding} label="New businesses per month" height={260} format={(v) => `${v} new`} dim={loading} integer />
          )}
        </Card>

        <Card className="lg:col-span-2" title="Recently onboarded" description="Newest businesses and how their routers are doing" action={<CardLink href="/admin/tenants">All tenants</CardLink>} bodyClassName="">
          {!tenants ? (
            <p className="px-5 py-12 text-center text-sm text-[var(--fg-muted)]">Tenant data is unavailable.</p>
          ) : recent.length === 0 ? (
            <p className="px-5 py-12 text-center text-sm text-[var(--fg-muted)]">No businesses yet.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[600px] text-left text-sm">
                <thead className="border-b border-[var(--border)] bg-[var(--subtle)] text-xs text-[var(--fg-muted)]">
                  <tr>
                    <th scope="col" className="px-5 py-2.5 font-medium">Business</th>
                    <th scope="col" className="px-4 py-2.5 font-medium">Account</th>
                    <th scope="col" className="px-4 py-2.5 font-medium">Routers</th>
                    <th scope="col" className="px-4 py-2.5 text-right font-medium">Active vouchers</th>
                    <th scope="col" className="px-5 py-2.5 text-right font-medium">Joined</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--border-soft)]">
                  {recent.map((t) => {
                    const net = fleetByTenant.get(t.id);
                    return (
                      <tr key={t.id} className="transition-colors hover:bg-[var(--subtle)]">
                        <td className="px-5 py-3">
                          <Link href={`/admin/tenants/${t.id}`} className="block font-medium text-[var(--fg)] hover:text-[var(--accent-text)] hover:underline">{t.name}</Link>
                          <span className="block text-xs text-[var(--fg-muted)]">{t.email}</span>
                        </td>
                        <td className="px-4 py-3"><Badge tone={statusTone(t.status)} dot>{humanize(t.status)}</Badge></td>
                        <td className="px-4 py-3">
                          {!t.routerCount ? <span className="text-xs text-[var(--fg-faint)]">None linked</span>
                            : !net ? <span className="num text-[13px]">{t.routerCount}</span>
                            : <StatusDot tone={net.tone}><span className="num">{net.label}</span></StatusDot>}
                        </td>
                        <td className="num px-4 py-3 text-right text-[var(--fg-body)]">{t.voucherCount.toLocaleString()}</td>
                        <td className="px-5 py-3 text-right text-xs text-[var(--fg-muted)]" title={new Date(t.createdAt).toLocaleString()}>{relativeTime(t.createdAt, now)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>

      <p className="flex items-start gap-2 text-[11.5px] leading-relaxed text-[var(--fg-faint)]">
        <ShieldWarning className="mt-px h-3.5 w-3.5 shrink-0" aria-hidden />
        Payment volume is what customers paid and belongs to the businesses; platform revenue is subscriptions plus platform fees. Cash sales recorded by tenants are included.
      </p>
    </div>
  );
}
