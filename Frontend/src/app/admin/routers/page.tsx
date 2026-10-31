"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { CaretRight, CellTower as RouterIcon } from "@/components/icons";
import { Badge, ErrorNotice, PageHeader, Segmented, StatusDot, Table, field, relativeTime, useNow, useResource } from "@/components/BillingUI";
import { Meter } from "@/components/charts";
import { routerState, servicesState, type RouterState } from "@/lib/routerState";

interface FleetRouter {
  id: number; tenantId: number; tenantName: string; name: string; status: string;
  wgIp: string | null; cpuLoad: number | null; memoryUsage: number | null;
  uptime: string | null; activeUsers: number | null;
  hotspotReady: boolean | null; radiusReady: boolean | null; portalReady: boolean | null;
  bindingsSynced: boolean; provisioningState: string;
  lastSeen: string | null; openSessions: number; openIncidents: number; createdAt: string;
}

type Filter = "ALL" | RouterState;
/** Only these states carry telemetry fresh enough to show load figures. */
const current = (s: RouterState) => s === "online" || s === "degraded";
const FILTERS: { key: Filter; label: string }[] = [
  { key: "ALL", label: "All" }, { key: "online", label: "Online" }, { key: "degraded", label: "Degraded" },
  { key: "stale", label: "Stale" }, { key: "offline", label: "Offline" }, { key: "provisioning", label: "Not yet online" },
];

export default function AdminRoutersPage() {
  const { data, loading, error, load, refreshing } = useResource<FleetRouter>("/api/admin/noc/routers", true);
  const now = useNow();
  const [filter, setFilter] = useState<Filter>("ALL");
  const [tenant, setTenant] = useState("ALL");

  const withState = useMemo(() => data.map((r) => ({ ...r, s: routerState(r, now) })), [data, now]);
  const tenants = useMemo(() => [...new Map(data.map((r) => [r.tenantId, r.tenantName])).entries()].sort((a, b) => a[1].localeCompare(b[1])), [data]);
  const byTenant = withState.filter((r) => tenant === "ALL" || r.tenantId === Number(tenant));
  const rows = byTenant.filter((r) => filter === "ALL" || r.s.state === filter || (filter === "provisioning" && r.s.state === "unknown"));
  const count = (k: Filter) => k === "ALL" ? byTenant.length : byTenant.filter((r) => r.s.state === k || (k === "provisioning" && r.s.state === "unknown")).length;
  const sessions = byTenant.reduce((sum, r) => sum + r.openSessions, 0);

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <PageHeader
        title="Router fleet"
        description="Every MikroTik router on the platform with its latest reported telemetry."
        meta={!loading && <>
          <span className="num">{data.length} router{data.length === 1 ? "" : "s"} · {tenants.length} tenant{tenants.length === 1 ? "" : "s"}</span>
          <span className="num">{sessions.toLocaleString()} open RADIUS session{sessions === 1 ? "" : "s"}</span>
          {refreshing && <span>Refreshing…</span>}
        </>}
      />
      {error && <ErrorNotice text={data.length ? `Couldn't refresh the fleet (${error}). Showing the last data loaded.` : `Router fleet unavailable: ${error}`} />}

      <Table
        rows={rows} loading={loading} rowKey={(r) => r.id}
        searchPlaceholder="Search router, tenant or tunnel IP…"
        emptyIcon={<RouterIcon />}
        emptyTitle={error && !data.length ? "Couldn't load routers" : data.length ? "No routers match these filters" : "No routers registered yet"}
        emptyDescription={error && !data.length ? "Check your connection and reload." : data.length ? "Change the state or tenant filter." : "Routers appear here once a tenant links one."}
        toolbar={<>
          <Segmented label="Router state" value={filter} onChange={setFilter} options={FILTERS.map((f) => ({ ...f, count: loading ? undefined : count(f.key) }))} />
          <select aria-label="Filter by tenant" value={tenant} onChange={(e) => setTenant(e.target.value)} className={`${field} !mt-0 w-full !py-1 text-xs sm:!min-h-8 sm:w-48`}>
            <option value="ALL">All tenants</option>
            {tenants.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
          </select>
          {!loading && <button onClick={() => void load()} className="text-xs font-medium text-[var(--accent-text)] hover:underline">Reload</button>}
        </>}
        columns={[
          { label: "Router", render: (r) => (
            <Link href={`/admin/network/routers/${r.id}`} className="group block">
              <span className="block font-medium text-[var(--fg)] group-hover:text-[var(--accent-text)] group-hover:underline">{r.name}</span>
              <span className="block font-mono text-[11px] text-[var(--fg-faint)]">{r.wgIp || "No tunnel IP"}</span>
            </Link>) },
          { label: "Tenant", render: (r) => <Link href={`/admin/tenants/${r.tenantId}`} className="text-[13px] hover:underline">{r.tenantName}</Link>, hideBelow: "md" },
          { label: "State", render: (r) => (
            <span className="flex items-center gap-1.5" title={r.s.detail}>
              <Badge tone={r.s.tone} dot>{r.s.label}</Badge>
              {r.openIncidents > 0 && <span className="num rounded bg-red-50 px-1 text-[10.5px] font-semibold text-red-700" title={`${r.openIncidents} open incident(s)`}>{r.openIncidents}</span>}
            </span>) },
          { label: "Services", render: (r) => { const s = servicesState([r.hotspotReady, r.radiusReady, r.portalReady]); return <StatusDot tone={s.tone}>{s.label}</StatusDot>; }, hideBelow: "lg" },
          { label: "CPU", render: (r) => current(r.s.state) ? <Meter value={r.cpuLoad} label="CPU load" /> : <span className="text-xs text-[var(--fg-faint)]" title="No current telemetry">—</span>, hideBelow: "lg" },
          { label: "Memory", render: (r) => current(r.s.state) ? <Meter value={r.memoryUsage} label="Memory usage" /> : <span className="text-xs text-[var(--fg-faint)]" title="No current telemetry">—</span>, hideBelow: "lg" },
          { label: "Sessions", render: (r) => r.openSessions.toLocaleString(), align: "right", hideBelow: "sm" },
          { label: "Uptime", render: (r) => <span className="text-xs">{current(r.s.state) ? r.uptime || "—" : "—"}</span>, hideBelow: "lg" },
          { label: "Last report", render: (r) => <span className="text-xs text-[var(--fg-muted)]" title={r.lastSeen ? new Date(r.lastSeen).toLocaleString() : "Never reported"}>{relativeTime(r.lastSeen, now)}</span>, align: "right" },
          { label: "", render: (r) => <Link href={`/admin/network/routers/${r.id}`} aria-label={`Open ${r.name}`} className="inline-flex rounded-md p-1 text-[var(--fg-faint)] hover:bg-[var(--fill)] hover:text-[var(--fg)]"><CaretRight className="h-4 w-4" /></Link>, className: "w-8 !px-2" },
        ]}
      />
      <p className="text-xs leading-5 text-[var(--fg-muted)]">
        Routers report every 2 minutes and are marked offline after 6 minutes of silence. <b className="font-medium">Stale</b> means the stored status still says online but no report has arrived for over 8 minutes, so that status can&apos;t be trusted. CPU, memory and uptime are shown only for routers reporting right now. RouterOS version isn&apos;t in telemetry; use <i>Run live probe</i> on a router to read it.
      </p>
    </div>
  );
}
