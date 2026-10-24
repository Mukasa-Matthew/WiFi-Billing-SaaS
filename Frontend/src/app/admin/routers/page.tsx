"use client";

import { useMemo, useState } from "react";
import { Activity, Router, Users, WifiHigh, WifiSlash } from "@/components/icons";
import { Badge, ErrorNotice, FilterTabs, PageHeader, StatCard, Table, date, field, useResource } from "@/components/BillingUI";

interface FleetRouter {
  id: number; tenantId: number; tenantName: string; name: string; status: string;
  managementIp: string | null; cpuLoad: number | null; memoryUsage: number | null;
  uptime: string | null; activeUsers: number | null; hotspotReady: boolean | null;
  radiusReady: boolean | null; portalReady: boolean | null; lastSeen: string | null; createdAt: string;
}

const statusTone = (status: string) => status.toLowerCase() === "online" ? "green" : status.toLowerCase() === "degraded" ? "amber" : status.toLowerCase() === "offline" ? "red" : "slate";
const readiness = (router: FleetRouter) => {
  const values = [router.hotspotReady, router.radiusReady, router.portalReady];
  if (values.every((value) => value === true)) return { label: "Ready", tone: "green" };
  if (values.some((value) => value === false)) return { label: "Needs attention", tone: "amber" };
  return { label: "Unknown", tone: "slate" };
};

export default function AdminRoutersPage() {
  const { data, loading, error } = useResource<FleetRouter>("/api/admin/routers", true);
  const [status, setStatus] = useState("ALL");
  const [tenant, setTenant] = useState("ALL");
  const tenants = useMemo(() => [...new Map(data.map((router) => [router.tenantId, router.tenantName])).entries()].sort((a, b) => a[1].localeCompare(b[1])), [data]);
  const rows = data.filter((router) => (status === "ALL" || router.status.toUpperCase() === status) && (tenant === "ALL" || router.tenantId === Number(tenant)));
  const online = data.filter((router) => router.status.toLowerCase() === "online").length;
  const offline = data.filter((router) => router.status.toLowerCase() === "offline").length;
  const activeUsers = data.reduce((sum, router) => sum + (router.activeUsers || 0), 0);

  return <div className="mx-auto max-w-7xl space-y-6">
    <PageHeader title="Router fleet" description="Platform-wide MikroTik inventory and the latest telemetry reported by each tenant router." />
    <ErrorNotice text={error} />
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <StatCard label="Registered routers" value={loading ? "—" : data.length} icon={<Router size={20} />} hintText="Across all tenant businesses" />
      <StatCard label="Online" value={loading ? "—" : online} icon={<WifiHigh size={20} />} tone="green" hintText="Latest stored status" />
      <StatCard label="Offline" value={loading ? "—" : offline} icon={<WifiSlash size={20} />} tone={offline ? "red" : "default"} hintText="Telemetry marked offline" />
      <StatCard label="Active users" value={loading ? "—" : activeUsers} icon={<Users size={20} />} hintText="Reported by fleet telemetry" />
    </div>
    <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
      <div className="overflow-x-auto pb-1"><FilterTabs value={status} onChange={setStatus} options={["ALL", "ONLINE", "DEGRADED", "OFFLINE", "PENDING"].map((key) => ({ key, label: key === "ALL" ? "All statuses" : key.toLowerCase(), count: key === "ALL" ? data.length : data.filter((router) => router.status.toUpperCase() === key).length }))} /></div>
      <select aria-label="Filter routers by tenant" value={tenant} onChange={(event) => setTenant(event.target.value)} className={`${field} !mt-0 lg:max-w-xs`}><option value="ALL">All tenants</option>{tenants.map(([id, name]) => <option key={id} value={id}>{name}</option>)}</select>
    </div>
    <Table rows={rows} loading={loading} emptyTitle="No routers match these filters" emptyDescription="Adjust the tenant or status filter to see other fleet records." columns={[
      { label: "Router", render: (router) => <span><span className="block font-semibold text-[var(--fg)]">{router.name}</span><span className="mt-0.5 block font-mono text-[11px] text-[var(--fg-faint)]">{router.managementIp || "Management IP unavailable"}</span></span> },
      { label: "Tenant", render: (router) => <span className="font-medium">{router.tenantName}</span> },
      { label: "Connection", render: (router) => <Badge tone={statusTone(router.status)}>{router.status || "unknown"}</Badge> },
      { label: "Integration", render: (router) => { const state = readiness(router); return <Badge tone={state.tone}>{state.label}</Badge>; } },
      { label: "Load", render: (router) => <span className="flex items-center gap-1.5"><Activity size={13} aria-hidden="true" />CPU {router.cpuLoad ?? "—"}% · RAM {router.memoryUsage ?? "—"}%</span> },
      { label: "Users", render: (router) => router.activeUsers ?? "—" },
      { label: "Uptime", render: (router) => router.uptime || "—" },
      { label: "Last seen", render: (router) => date(router.lastSeen) },
    ]} />
    <p className="text-xs leading-5 text-[var(--fg-muted)]">RouterOS version is not currently reported by telemetry, so it is intentionally not shown. No remote command capability is exposed here.</p>
  </div>;
}
