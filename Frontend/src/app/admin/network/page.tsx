"use client";
import { ListSkeleton } from "@/components/LoadingUI";

import Link from "next/link";
import { useMemo, useState } from "react";
import { ArrowClockwise, CheckCircle, CellTower as RouterIcon } from "@/components/icons";
import {
  Badge, Card, CardLink, ErrorNotice, PageHeader, Segmented, StatusDot, Table, buttonGhost,
  bytes, relativeTime, useApi, useNow, useResource,
} from "@/components/BillingUI";
import { StatusBar, type StatusSegment } from "@/components/charts";
import { routerState, servicesState, severityTone, summarizeRouters } from "@/lib/routerState";

interface Incident {
  id: number; routerId: number; routerName: string; tenantName: string;
  type: string; severity: string; title: string; detail: string | null;
  openedAt: string; resolvedAt: string | null;
}
interface Overview {
  totalTenants: number; totalRouters: number; onlineRouters: number; offlineRouters: number;
  attentionRouters: number; activeSessions: number; staleOpenSessions: number;
  dataBytesLast24h: number; authFailuresLast24h: number; failedTasksLast7d: number;
  openIncidents: number; incidents: Incident[];
}
interface TenantItem {
  tenantId: number; tenantName: string; status: string;
  routerCount: number; onlineRouters: number; attentionRouters: number;
}
interface RouterRow {
  id: number; tenantId: number; tenantName: string; name: string; status: string;
  wgIp: string | null; hotspotReady: boolean | null; radiusReady: boolean | null; portalReady: boolean | null;
  bindingsSynced: boolean; provisioningState: string; lastSeen: string | null;
  openSessions: number; openIncidents: number;
}

const REFRESH = 30000;

/** Why a router is on the attention list, most serious reason first. */
function issueFor(r: RouterRow, now: number): string | null {
  const s = routerState(r, now);
  if (s.state === "offline") return "Not reporting";
  if (s.state === "stale") return "Stored status is stale";
  if (s.state === "degraded") return "Service not ready";
  if (r.openIncidents > 0) return `${r.openIncidents} open incident${r.openIncidents === 1 ? "" : "s"}`;
  if (s.state === "online" && [r.hotspotReady, r.radiusReady, r.portalReady].some((v) => v === false)) return "Service not ready";
  if (s.state === "online" && !r.bindingsSynced) return "IP bindings not synced";
  return null;
}

export default function NetworkOperationsPage() {
  // The overview endpoint returns one object, not a list.
  const overview = useApi<Overview>("/api/admin/noc/overview", REFRESH);
  const tenants = useResource<TenantItem>("/api/admin/noc/tenants", true);
  const fleet = useResource<RouterRow>("/api/admin/noc/routers", true);
  const now = useNow();
  const [severity, setSeverity] = useState<"ALL" | "CRITICAL" | "WARNING">("ALL");

  const ov = overview.data;
  const incidents = ov?.incidents ?? [];
  const shownIncidents = incidents.filter((i) => severity === "ALL" || i.severity === severity);

  const segments: StatusSegment[] = useMemo(() => {
    const counts = { online: 0, degraded: 0, offline: 0, stale: 0, other: 0 };
    fleet.data.forEach((r) => {
      const s = routerState(r, now).state;
      if (s === "online" || s === "degraded" || s === "offline" || s === "stale") counts[s] += 1; else counts.other += 1;
    });
    return [
      { key: "online", label: "Online", value: counts.online, tone: "good" },
      { key: "degraded", label: "Degraded", value: counts.degraded, tone: "warn" },
      { key: "stale", label: "Stale", value: counts.stale, tone: "warn" },
      { key: "offline", label: "Offline", value: counts.offline, tone: "bad" },
      { key: "other", label: "Not yet online", value: counts.other, tone: "idle" },
    ];
  }, [fleet.data, now]);

  const attention = useMemo(() => fleet.data
    .map((r) => ({ r, issue: issueFor(r, now) }))
    .filter((x): x is { r: RouterRow; issue: string } => !!x.issue), [fleet.data, now]);

  // Rolled up from the fleet so a router still being set up never reads as down.
  const tenantHealth = useMemo(() => {
    const groups = new Map<number, { id: number; name: string; routers: RouterRow[] }>();
    fleet.data.forEach((r) => {
      const g = groups.get(r.tenantId) ?? { id: r.tenantId, name: r.tenantName, routers: [] };
      g.routers.push(r);
      groups.set(r.tenantId, g);
    });
    const rank = { red: 0, amber: 1, slate: 2, green: 3 } as const;
    return [...groups.values()]
      .map((g) => ({ ...g, sum: summarizeRouters(g.routers, now) }))
      .sort((a, b) => rank[a.sum.tone] - rank[b.sum.tone] || a.name.localeCompare(b.name));
  }, [fleet.data, now]);
  const noRouterTenants = Math.max(0, tenants.data.length - tenantHealth.length);

  const dash = "—";
  const refreshing = overview.loading || fleet.refreshing;
  const metric = (v: number | undefined, f: (n: number) => string = (n) => n.toLocaleString()) => (v == null ? dash : f(v));

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <PageHeader
        title="Network operations"
        description="Open incidents and live activity across every tenant's MikroTik routers."
        meta={overview.updatedAt && <span className="inline-flex items-center gap-1.5"><span className="h-1.5 w-1.5 rounded-full bg-[var(--status-good)]" aria-hidden />Updated {new Date(overview.updatedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })} · refreshes every 30 s</span>}
        action={<button onClick={() => { void overview.load(); void fleet.load(); void tenants.load(); }} disabled={refreshing} className={buttonGhost}>
          <ArrowClockwise className={`h-4 w-4 ${refreshing ? "animate-spin motion-reduce:animate-none" : ""}`} />Refresh
        </button>}
      />

      {overview.error && <ErrorNotice text={ov ? `Couldn't refresh the network overview (${overview.error}). Showing data from ${relativeTime(new Date(overview.updatedAt!).toISOString(), now)}.` : `Network overview unavailable: ${overview.error}`} />}
      {(fleet.error || tenants.error) && <ErrorNotice text={`Couldn't load ${[fleet.error && "the router fleet", tenants.error && "tenant health"].filter(Boolean).join(" or ")}.`} />}

      {/* One band of live figures rather than a wall of cards. */}
      <section className="panel-shadow grid overflow-hidden rounded-xl border border-[var(--border)] bg-[var(--surface)] sm:grid-cols-2 lg:grid-cols-[1.4fr_1fr_1fr_1fr]">
        <div className="border-b border-[var(--border)] p-5 sm:col-span-2 lg:col-span-1 lg:border-b-0 lg:border-r">
          <p className="text-[13px] font-medium text-[var(--fg-muted)]">Routers online</p>
          <p className="mt-1.5 text-[1.7rem] font-semibold leading-tight tracking-tight text-[var(--fg)]">
            {ov ? <>{ov.onlineRouters}<span className="text-[var(--fg-faint)]"> / {ov.totalRouters}</span></> : dash}
          </p>
          <div className="mt-3">{fleet.data.length > 0 ? <StatusBar segments={segments} label="Router state" /> : <p className="text-xs text-[var(--fg-muted)]">{fleet.loading ? "Loading routers…" : fleet.error ? "Fleet unavailable" : "No routers registered"}</p>}</div>
        </div>
        {[
          { label: "Live WiFi sessions", value: metric(ov?.activeSessions), hint: ov ? `${ov.staleOpenSessions.toLocaleString()} open with no updates for 15 min` : "" },
          { label: "Data transferred (24h)", value: metric(ov?.dataBytesLast24h, bytes), hint: "RADIUS accounting, all routers" },
          { label: "Auth failures (24h)", value: metric(ov?.authFailuresLast24h), hint: ov ? `${ov.failedTasksLast7d} failed router task${ov.failedTasksLast7d === 1 ? "" : "s"} in 7 days` : "", warn: !!ov?.authFailuresLast24h },
        ].map((m, i) => (
          <div key={m.label} className={`p-5 ${i < 2 ? "border-b sm:border-b-0" : ""} border-[var(--border)] ${i === 0 ? "sm:border-r" : ""} ${i === 1 ? "lg:border-r" : ""} ${i === 2 ? "sm:col-span-2 sm:border-t lg:col-span-1 lg:border-t-0" : ""}`}>
            <p className="text-[13px] font-medium text-[var(--fg-muted)]">{m.label}</p>
            <p className="mt-1.5 text-[1.7rem] font-semibold leading-tight tracking-tight text-[var(--fg)]">{m.value}</p>
            <p className="mt-1.5 text-xs text-[var(--fg-muted)]">{m.hint}</p>
          </div>
        ))}
      </section>

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-3 lg:items-start">
        <Card
          className="lg:col-span-2"
          title={<span className="flex items-center gap-2">Open incidents {ov && <span className="num rounded-md bg-[var(--fill)] px-1.5 py-px text-xs font-medium text-[var(--fg-muted)]">{ov.openIncidents}</span>}</span>}
          description="Raised by the incident evaluator every minute; they close on their own when the condition clears"
          action={incidents.length > 0 && <Segmented label="Severity" value={severity} onChange={setSeverity} options={[
            { key: "ALL", label: "All", count: incidents.length },
            { key: "CRITICAL", label: "Critical", count: incidents.filter((i) => i.severity === "CRITICAL").length },
            { key: "WARNING", label: "Warning", count: incidents.filter((i) => i.severity === "WARNING").length },
          ]} />}
          bodyClassName=""
        >
          {!ov ? (
            (overview.loading ? <ListSkeleton rows={3} avatar={false} /> : <p className="px-5 py-14 text-center text-sm text-[var(--fg-muted)]">Incidents are unavailable right now.</p>)
          ) : shownIncidents.length === 0 ? (
            <div className="flex flex-col items-center gap-2 px-5 py-14 text-center">
              <span className="flex h-10 w-10 items-center justify-center rounded-full bg-emerald-50 text-emerald-700"><CheckCircle className="h-5 w-5" /></span>
              <p className="text-sm font-medium text-[var(--fg-body)]">{incidents.length ? "Nothing at this severity" : "No open incidents"}</p>
              <p className="text-xs text-[var(--fg-muted)]">Routers that go offline, report services down or fail a task show up here.</p>
            </div>
          ) : (
            <ul className="divide-y divide-[var(--border-soft)]">
              {shownIncidents.map((i) => (
                <li key={i.id}>
                  <Link href={`/admin/network/routers/${i.routerId}`} className="flex items-start gap-3 px-5 py-3.5 transition-colors hover:bg-[var(--subtle)]">
                    <span className="w-20 shrink-0 pt-0.5"><Badge tone={severityTone(i.severity)} dot>{i.severity === "CRITICAL" ? "Critical" : i.severity === "WARNING" ? "Warning" : "Info"}</Badge></span>
                    <span className="min-w-0 flex-1">
                      <span className="block text-[13.5px] font-medium text-[var(--fg)]">{i.title}</span>
                      <span className="block truncate text-xs text-[var(--fg-muted)]">{i.tenantName} · {i.routerName}{i.detail ? ` · ${i.detail}` : ""}</span>
                    </span>
                    <span className="shrink-0 text-right text-xs text-[var(--fg-faint)]" title={new Date(i.openedAt).toLocaleString()}>
                      <span className="block">Opened {relativeTime(i.openedAt, now)}</span>
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card title="Tenant network health" description="Businesses with routers, most affected first" bodyClassName="" action={<CardLink href="/admin/tenants">Tenants</CardLink>}>
          {fleet.loading && !fleet.data.length ? (
            <ListSkeleton rows={4} />
          ) : tenantHealth.length === 0 ? (
            <p className="px-5 py-10 text-center text-sm text-[var(--fg-muted)]">{fleet.error ? "Unavailable." : "No tenant has linked a router yet."}</p>
          ) : (
            <ul className="max-h-[26rem] divide-y divide-[var(--border-soft)] overflow-y-auto">
              {tenantHealth.map((t) => (
                <li key={t.id}>
                  <Link href={`/admin/tenants/${t.id}`} className="flex items-center justify-between gap-3 px-5 py-3 transition-colors hover:bg-[var(--subtle)]">
                    <span className="min-w-0">
                      <span className="block truncate text-[13.5px] font-medium text-[var(--fg)]">{t.name}</span>
                      <span className="block text-xs text-[var(--fg-muted)]">{t.sum.detail}</span>
                    </span>
                    <StatusDot tone={t.sum.tone}><span className="num">{t.sum.online}/{t.sum.total}</span></StatusDot>
                  </Link>
                </li>
              ))}
            </ul>
          )}
          {noRouterTenants > 0 && <p className="border-t border-[var(--border)] px-5 py-2.5 text-xs text-[var(--fg-faint)]">{noRouterTenants} business{noRouterTenants === 1 ? "" : "es"} with no router linked</p>}
        </Card>
      </div>

      <Card
        title="Routers needing attention"
        description="Offline, stale, degraded, or with open incidents or unsynced bindings"
        action={<CardLink href="/admin/routers">Full router fleet</CardLink>}
        bodyClassName=""
      >
        <Table
          bare searchable={false} paginated={attention.length > 10}
          rows={attention} loading={fleet.loading}
          rowKey={(x) => x.r.id}
          emptyIcon={<RouterIcon />}
          emptyTitle={fleet.error ? "Router fleet unavailable" : "Every router is healthy"}
          emptyDescription={fleet.error ? "Retry with Refresh." : "Nothing needs a look right now."}
          columns={[
            { label: "Router", render: ({ r }) => (
              <Link href={`/admin/network/routers/${r.id}`} className="group block">
                <span className="block font-medium text-[var(--fg)] group-hover:text-[var(--accent-text)] group-hover:underline">{r.name}</span>
                <span className="block font-mono text-[11px] text-[var(--fg-faint)]">{r.wgIp || "No tunnel IP"}</span>
              </Link>) },
            { label: "Tenant", render: ({ r }) => <Link href={`/admin/tenants/${r.tenantId}`} className="hover:underline">{r.tenantName}</Link>, hideBelow: "md" },
            { label: "State", render: ({ r }) => { const s = routerState(r, now); return <span title={s.detail}><Badge tone={s.tone} dot>{s.label}</Badge></span>; } },
            { label: "Issue", render: ({ issue }) => <span className="text-[13px]">{issue}</span> },
            { label: "Services", render: ({ r }) => { const s = servicesState([r.hotspotReady, r.radiusReady, r.portalReady]); return <StatusDot tone={s.tone}>{s.label}</StatusDot>; }, hideBelow: "lg" },
            { label: "Last report", render: ({ r }) => <span className="text-xs text-[var(--fg-muted)]" title={r.lastSeen ? new Date(r.lastSeen).toLocaleString() : undefined}>{relativeTime(r.lastSeen, now)}</span>, align: "right" },
          ]}
        />
      </Card>
    </div>
  );
}
