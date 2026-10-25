"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import {
  Activity, Building2, CellTower as RouterIcon, HardDrives, MagnifyingGlass,
  ShieldWarning, Users, WifiHigh, WifiSlash, Warning,
} from "@/components/icons";
import {
  Badge, ErrorNotice, PageHeader, StatCard, Table, date, bytes, field, panel, useResource,
} from "@/components/BillingUI";

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
  wgIp: string | null; cpuLoad: number | null; memoryUsage: number | null;
  uptime: string | null; activeUsers: number | null;
  hotspotReady: boolean | null; radiusReady: boolean | null; portalReady: boolean | null;
  bindingsSynced: boolean; provisioningState: string;
  lastSeen: string | null; bindingsSyncedAt: string | null; hostsSeenAt: string | null;
  openSessions: number; openIncidents: number; createdAt: string;
}

const sevTone = (s: string) => s === "CRITICAL" ? "red" : s === "WARNING" ? "amber" : "slate";
const statusTone = (s: string) =>
  s?.toLowerCase() === "online" ? "green" :
  s?.toLowerCase() === "degraded" ? "amber" :
  s?.toLowerCase() === "offline" ? "red" : "slate";

export default function NetworkOperationsPage() {
  const overview = useResource<Overview>("/api/admin/noc/overview", true);
  const tenants = useResource<TenantItem>("/api/admin/noc/tenants", true);
  const fleet = useResource<RouterRow>("/api/admin/noc/routers", true);

  const [query, setQuery] = useState("");
  const [tenantId, setTenantId] = useState<number | "ALL">("ALL");
  const [status, setStatus] = useState("ALL");

  const ov = overview.data[0];
  const rows = useMemo(() => fleet.data.filter((r) => {
    if (tenantId !== "ALL" && r.tenantId !== tenantId) return false;
    if (status !== "ALL" && (r.status || "").toUpperCase() !== status) return false;
    if (!query) return true;
    const q = query.toLowerCase();
    return r.name.toLowerCase().includes(q)
        || r.tenantName.toLowerCase().includes(q)
        || (r.wgIp || "").includes(q);
  }), [fleet.data, tenantId, status, query]);

  return <div className="mx-auto max-w-7xl space-y-6">
    <PageHeader title="Network operations"
      description="Platform-wide MikroTik visibility: fleet health, live sessions, accounting, incidents and diagnostics."
      action={<Badge tone={ov && ov.openIncidents > 0 ? "red" : "green"}>
        {overview.loading ? "…" : `${ov?.openIncidents ?? 0} open incident(s)`}
      </Badge>} />
    <ErrorNotice text={overview.error || fleet.error || tenants.error} />

    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      <StatCard label="Tenants" value={tenants.loading ? "—" : ov?.totalTenants ?? 0} icon={<Building2 size={20} />} hintText="Registered businesses" />
      <StatCard label="Routers" value={fleet.loading ? "—" : `${ov?.onlineRouters ?? 0} / ${ov?.totalRouters ?? 0}`} icon={<RouterIcon size={20} />} hintText={`${ov?.offlineRouters ?? 0} offline · ${ov?.attentionRouters ?? 0} need attention`} tone={ov && ov.offlineRouters > 0 ? "amber" : "default"} />
      <StatCard label="Live WiFi sessions" value={overview.loading ? "—" : ov?.activeSessions ?? 0} icon={<WifiHigh size={20} />} tone="green" hintText={`${ov?.staleOpenSessions ?? 0} open but stale`} />
      <StatCard label="Data (24h)" value={overview.loading ? "—" : bytes(ov?.dataBytesLast24h ?? 0)} icon={<Activity size={20} />} hintText={`${ov?.authFailuresLast24h ?? 0} auth failures · ${ov?.failedTasksLast7d ?? 0} failed tasks (7d)`} />
    </div>

    {ov && ov.incidents.length > 0 && (
      <section className={`${panel} p-5`}>
        <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold text-[var(--fg)]">
          <ShieldWarning size={16} className="text-amber-500" /> Active incidents
        </h2>
        <div className="space-y-2">
          {ov.incidents.map((i) => (
            <Link key={i.id} href={`/admin/network/routers/${i.routerId}`}
              className="flex flex-wrap items-center gap-3 rounded-lg border border-[var(--border)] px-3 py-2.5 transition hover:bg-[var(--subtle)]">
              <Badge tone={sevTone(i.severity)}>{i.severity}</Badge>
              <span className="font-medium text-[var(--fg)]">{i.title}</span>
              <span className="text-xs text-[var(--fg-muted)]">{i.tenantName} · {i.routerName} · {date(i.openedAt)}</span>
            </Link>
          ))}
        </div>
      </section>
    )}

    <section className={`${panel} p-5`}>
      <h2 className="mb-3 text-sm font-semibold text-[var(--fg)]">Tenants</h2>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {tenants.data.map((t) => (
          <button key={t.tenantId} onClick={() => setTenantId(tenantId === t.tenantId ? "ALL" : t.tenantId)}
            className={`rounded-lg border px-4 py-3 text-left transition ${tenantId === t.tenantId ? "border-blue-500 bg-blue-500/10" : "border-[var(--border)] hover:bg-[var(--subtle)]"}`}>
            <div className="flex items-center justify-between gap-2">
              <span className="truncate font-medium text-[var(--fg)]">{t.tenantName}</span>
              {t.attentionRouters > 0
                ? <Badge tone="amber">{t.attentionRouters} need attention</Badge>
                : <Badge tone="green">{t.onlineRouters}/{t.routerCount} online</Badge>}
            </div>
            <div className="mt-1 text-xs text-[var(--fg-muted)]">
              {t.routerCount} router(s) · {t.onlineRouters} online
            </div>
          </button>
        ))}
        {!tenants.loading && tenants.data.length === 0 && (
          <p className="text-sm text-[var(--fg-muted)]">No tenants registered yet.</p>
        )}
      </div>
    </section>

    <section className="space-y-3">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="relative flex-1">
          <MagnifyingGlass size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-[var(--fg-muted)]" />
          <input value={query} onChange={(e) => setQuery(e.target.value)}
            placeholder="Search router, tenant or tunnel IP…" className={`${field} !mt-0 pl-9`} />
        </div>
        <select value={status} onChange={(e) => setStatus(e.target.value)} className={`${field} !mt-0 sm:max-w-[180px]`} aria-label="Filter by status">
          {["ALL", "ONLINE", "DEGRADED", "OFFLINE", "PENDING", "PROVISIONING", "REGISTERED"].map((s) => (
            <option key={s} value={s}>{s === "ALL" ? "All statuses" : s.toLowerCase()}</option>
          ))}
        </select>
      </div>

      <Table rows={rows} loading={fleet.loading}
        emptyTitle="No routers match" emptyDescription="Adjust search or filters."
        columns={[
          { label: "Router", render: (r) => (
            <Link href={`/admin/network/routers/${r.id}`} className="group">
              <span className="block font-semibold text-[var(--fg)] group-hover:text-blue-600 group-hover:underline">{r.name}</span>
              <span className="mt-0.5 block font-mono text-[11px] text-[var(--fg-faint)]">{r.wgIp || "No tunnel IP"}</span>
            </Link>) },
          { label: "Tenant", render: (r) => <span className="font-medium">{r.tenantName}</span> },
          { label: "Status", render: (r) => (
            <span className="flex items-center gap-1.5">
              <Badge tone={statusTone(r.status)}>{r.status || "unknown"}</Badge>
              {r.openIncidents > 0 && <Warning size={14} className="text-amber-500" />}
            </span>) },
          { label: "Services", render: (r) => {
            const flags = [r.hotspotReady, r.radiusReady, r.portalReady];
            if (flags.every((v) => v === true)) return <Badge tone="green">All ready</Badge>;
            if (flags.some((v) => v === false)) return <Badge tone="amber">Degraded</Badge>;
            return <Badge tone="slate">Unknown</Badge>;
          } },
          { label: "Provisioning", render: (r) => (
            <span>
              <span className="block text-xs font-medium">{r.provisioningState}</span>
              <span className="block text-[11px] text-[var(--fg-faint)]">
                {r.bindingsSynced ? "bindings synced" : "bindings pending"}
                {r.bindingsSyncedAt ? ` · ${date(r.bindingsSyncedAt)}` : ""}
              </span>
            </span>) },
          { label: "Load", render: (r) => <span className="text-xs">CPU {r.cpuLoad ?? "—"}% · RAM {r.memoryUsage ?? "—"}%</span> },
          { label: "Sessions", render: (r) => r.openSessions },
          { label: "Uptime", render: (r) => <span className="text-xs">{r.uptime || "—"}</span> },
          { label: "Last seen", render: (r) => date(r.lastSeen) },
        ]} />
      <p className="text-xs text-[var(--fg-muted)]">
        Router model / RouterOS version / serial are not reported by the current telemetry agent, so they are intentionally not shown rather than invented.
        Values marked — are unavailable from stored records.
      </p>
    </section>
  </div>;
}
