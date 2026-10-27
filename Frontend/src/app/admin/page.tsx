"use client";
import { getQueryClient } from "@/lib/queryClient";

import React, { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import api from "@/lib/api";
import {
  ArrowClockwise, Building2, CellTower, CheckCircle, CreditCard, ShieldWarning, WifiHigh,
} from "@/components/icons";
import {
  Badge, Card, CardLink, ErrorNotice, PageHeader, StatCard, StatusDot, buttonGhost,
  bytes, humanize, relativeTime, ugx,
} from "@/components/BillingUI";
import { BarChart, StatusBar, type StatusSegment } from "@/components/charts";
import { DashboardLoading } from "@/components/LoadingUI";
import { routerState, severityTone, summarizeRouters } from "@/lib/routerState";

interface Tenant {
  id: number; name: string; email: string; phone: string; status: string;
  routerCount: number; voucherCount: number; createdAt: string;
}
interface SubscriptionOverview { active: number; overdue: number; monthRevenue: number; outstanding: number }
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

const REFRESH_MS = 20000;

const reconTone = (s: string) => (s === "CLEAN" ? "green" : s === "DISCREPANCY" ? "red" : "amber");
const statusTone = (s: string) => (s === "ACTIVE" ? "green" : s === "SUSPENDED" ? "amber" : "red");

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

export default function AdminOverviewPage() {
  const [data, setData] = useState<Data | null>(null);
  const [failed, setFailed] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);

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
  const recent = useMemo(() => [...(tenants || [])].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()).slice(0, 6), [tenants]);

  if (loading && !data) return <DashboardLoading label="Loading platform data…" />;

  const subs = data?.subscriptions;
  const fin = data?.finance;
  const plat = data?.platform;
  const dash = "—";

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <PageHeader
        title="Command center"
        description="Platform health, tenant growth and revenue across every AirPairy business."
        meta={updatedAt && <>
          <span className="inline-flex items-center gap-1.5"><span className="h-1.5 w-1.5 rounded-full bg-[var(--status-good)]" aria-hidden />Updated {new Date(updatedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</span>
          <span>Refreshes every minute</span>
        </>}
        action={<button disabled={loading} onClick={() => load(false)} className={buttonGhost}><ArrowClockwise className={`h-4 w-4 ${loading ? "animate-spin motion-reduce:animate-none" : ""}`} />{loading ? "Refreshing…" : "Refresh"}</button>}
      />

      {failed.length > 0 && <ErrorNotice text={`Couldn't load ${failed.join(", ")}. Those sections show the last data available or a dash.`} />}

      {/* Headline KPIs: one per domain, each drilling into its console. */}
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Businesses" href="/admin/tenants" icon={<Building2 />}
          value={tenants ? tenantCounts.total : dash}
          hint={tenants ? <>{tenantCounts.active} active · {tenantCounts.suspended} suspended{tenantCounts.disabled ? ` · ${tenantCounts.disabled} disabled` : ""}</> : "Tenant list unavailable"}
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
          label="Subscription revenue" href="/admin/subscriptions" icon={<CreditCard />}
          tone={subs?.overdue ? "amber" : "default"}
          value={subs ? ugx(subs.monthRevenue) : dash}
          hint={subs ? `This month · ${subs.active} active, ${subs.overdue} overdue` : "Subscriptions unavailable"}
        />
      </div>

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
        <Card
          className="lg:col-span-2"
          title="Tenant onboarding"
          description="New businesses registered per month, last 12 months"
          action={tenants && <span className="text-xs text-[var(--fg-muted)]"><span className="num font-semibold text-[var(--fg)]">+{tenantCounts.last30}</span> in the last 30 days</span>}
        >
          {!tenants ? (
            <p className="py-16 text-center text-sm text-[var(--fg-muted)]">Tenant data is unavailable.</p>
          ) : onboarding.every((m) => m.value === 0) ? (
            <p className="py-16 text-center text-sm text-[var(--fg-muted)]">No businesses registered in the last 12 months.</p>
          ) : (
            <BarChart data={onboarding} label="New businesses per month" height={340} format={(v) => `${v} new`} dim={loading} integer />
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

        <Card title="Money" description="Platform earnings and funds held for tenants" action={<CardLink href="/admin/finance">Finance</CardLink>} bodyClassName="px-5 pb-4">
          <dl className="divide-y divide-[var(--border-soft)]">
            {[
              { label: "Platform earnings available", value: plat ? ugx(plat.safeWithdrawable) : dash, extra: plat && <Badge tone={reconTone(plat.reconcileStatus)} dot>{humanize(plat.reconcileStatus)}</Badge> },
              { label: "Tenant wallet liabilities", value: fin ? ugx(fin.tenantLiabilities + fin.reservedFunds) : dash, note: "Available plus reserved" },
              { label: "Collected for tenants", value: fin ? ugx(fin.totalCollected) : dash, note: "Not platform revenue" },
              { label: "Outstanding invoices", value: subs ? ugx(subs.outstanding) : dash },
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

      <Card title="Recently onboarded" description="Newest businesses and how their routers are doing" action={<CardLink href="/admin/tenants">All tenants</CardLink>} bodyClassName="">
        {!tenants ? (
          <p className="px-5 py-12 text-center text-sm text-[var(--fg-muted)]">Tenant data is unavailable.</p>
        ) : recent.length === 0 ? (
          <p className="px-5 py-12 text-center text-sm text-[var(--fg-muted)]">No businesses yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px] text-left text-sm">
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

      <p className="flex items-start gap-2 text-[11.5px] leading-relaxed text-[var(--fg-faint)]">
        <ShieldWarning className="mt-px h-3.5 w-3.5 shrink-0" aria-hidden />
        Session, authentication and transaction trends need platform-wide time-series endpoints that the backend doesn&apos;t provide yet, so they aren&apos;t charted here.
      </p>
    </div>
  );
}
