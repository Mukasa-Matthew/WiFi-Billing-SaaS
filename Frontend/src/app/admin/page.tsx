"use client";

import React, { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import api from "@/lib/api";
import { Wallet, Users, CellTower, Radio, ArrowClockwise } from "@phosphor-icons/react";
import { Badge, ErrorNotice, PageHeader, StatCard, Table, buttonGhost, panel, ugx, date } from "@/components/BillingUI";
import { DashboardLoading, LoadingIndicator } from "@/components/LoadingUI";

interface Metrics {
  totalTenants: number; totalRouters: number; onlineRouters: number;
  totalVouchers: number; totalTransactions: number; totalRevenueUgx: number; activeHotspotSessions: number;
}
interface Tenant {
  id: number; name: string; email: string; phone: string; status: string;
  routerCount: number; voucherCount: number; createdAt: string;
}

export default function AdminOverviewPage() {
  const [metrics, setMetrics] = useState<Metrics | null>(null);
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [summary, businesses] = await Promise.all([
        api.get<Metrics>("/api/admin/metrics"), api.get<Tenant[]>("/api/admin/tenants"),
      ]);
      setMetrics(summary.data);
      setTenants([...businesses.data].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()).slice(0, 5));
      setError("");
    } catch { setError("Could not refresh platform data. Please try again."); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);
  if (loading && !metrics) return <DashboardLoading label="Loading platform data…" />;

  return <div className="mx-auto max-w-7xl space-y-6">
    <PageHeader title="Platform overview" description="Business activity and router telemetry across your platform."
      action={<button disabled={loading} onClick={load} className={buttonGhost}><ArrowClockwise className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />{loading ? "Refreshing…" : "Refresh data"}</button>} />
    <ErrorNotice text={error} />
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <StatCard label="Recorded revenue" value={metrics ? ugx(metrics.totalRevenueUgx) : "—"} icon={<Wallet className="h-5 w-5" />} hintText="Successful recorded payments" tone="green" />
      <StatCard label="Hotspot businesses" value={metrics?.totalTenants ?? "—"} icon={<Users className="h-5 w-5" />} hintText="Registered business accounts" />
      <StatCard label="Online routers" value={metrics ? `${metrics.onlineRouters} / ${metrics.totalRouters}` : "—"} icon={<CellTower className="h-5 w-5" />} hintText="Based on reported router telemetry" />
      <StatCard label="Open sessions" value={metrics?.activeHotspotSessions ?? "—"} icon={<Radio className="h-5 w-5" />} hintText="Accounting sessions without a stop record" />
    </div>
    <section className={`${panel} flex flex-wrap items-center justify-between gap-4 p-5`}>
      <div><h2 className="text-sm font-semibold text-slate-900">Network and infrastructure</h2><p className="mt-1 max-w-2xl text-sm text-slate-600">These business metrics do not verify WireGuard, RADIUS, or database service health. Live infrastructure probes are not connected to this page.</p></div>
      <Link href="/admin/health" className="text-sm font-semibold text-blue-700">View service details →</Link>
    </section>
    <div className="flex items-center justify-between"><h2 className="text-lg font-semibold text-slate-900">Recently added businesses</h2><Link href="/admin/tenants" className="text-sm font-semibold text-blue-700">View tenants →</Link></div>
    <Table rows={tenants} columns={[
      { label: "Business", render: t => <span className="font-medium text-slate-900">{t.name}</span> },
      { label: "Email", render: t => t.email }, { label: "Phone", render: t => t.phone },
      { label: "Routers", render: t => t.routerCount },
      { label: "Status", render: t => <Badge tone={t.status === "ACTIVE" ? "green" : "red"}>{t.status}</Badge> },
      { label: "Joined", render: t => date(t.createdAt) },
    ]} />
    {loading && <LoadingIndicator label="Refreshing platform data…" />}
  </div>;
}
