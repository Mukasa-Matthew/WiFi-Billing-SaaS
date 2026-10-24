"use client";

import React, { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import api from "@/lib/api";
import { Wallet, Users, CellTower, Radio, ArrowClockwise, CreditCard, Bank, WarningCircle, ChartLine } from "@/components/icons";
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
interface SubscriptionOverview { active: number; overdue: number; monthRevenue: number; outstanding: number }
interface FinanceOverview { tenantLiabilities: number; reservedFunds: number; totalCollected: number; pendingWithdrawals: number }
interface PlatformFinance { settledEarnings: number; reservedEarnings: number; safeWithdrawable: number; reconcileStatus: string }

export default function AdminOverviewPage() {
  const [metrics, setMetrics] = useState<Metrics | null>(null);
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [subscriptions, setSubscriptions] = useState<SubscriptionOverview | null>(null);
  const [finance, setFinance] = useState<FinanceOverview | null>(null);
  const [platform, setPlatform] = useState<PlatformFinance | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [summary, businesses, subscriptionSummary, financeSummary, platformSummary] = await Promise.all([
        api.get<Metrics>("/api/admin/metrics"),
        api.get<Tenant[]>("/api/admin/tenants"),
        api.get<SubscriptionOverview>("/api/admin/subscriptions/overview"),
        api.get<FinanceOverview>("/api/admin/finance/overview"),
        api.get<PlatformFinance>("/api/admin/finance/platform"),
      ]);
      setMetrics(summary.data);
      setTenants([...businesses.data].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()).slice(0, 5));
      setSubscriptions(subscriptionSummary.data);
      setFinance(financeSummary.data);
      setPlatform(platformSummary.data);
      setError("");
    } catch { setError("Could not refresh platform data. Please try again."); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { const initial = window.setTimeout(load, 0); return () => window.clearTimeout(initial); }, [load]);
  if (loading && !metrics) return <DashboardLoading label="Loading platform data…" />;

  return <div className="mx-auto max-w-7xl space-y-6">
    <PageHeader title="AirPairy command center" description="Business health, subscription revenue, tenant liabilities, and network operations across the platform."
      action={<button disabled={loading} onClick={load} className={buttonGhost}><ArrowClockwise className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />{loading ? "Refreshing…" : "Refresh data"}</button>} />
    <ErrorNotice text={error} />
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <StatCard label="Hotspot businesses" value={metrics?.totalTenants ?? "—"} icon={<Users className="h-5 w-5" />} hintText="Registered business accounts" />
      <StatCard label="Active subscriptions" value={subscriptions?.active ?? "—"} icon={<CreditCard className="h-5 w-5" />} hintText={`${subscriptions?.overdue ?? 0} overdue`} tone={subscriptions?.overdue ? "amber" : "green"} />
      <StatCard label="Subscription revenue" value={subscriptions ? ugx(subscriptions.monthRevenue) : "—"} icon={<ChartLine className="h-5 w-5" />} hintText="Verified payments this month" tone="green" />
      <StatCard label="Outstanding invoices" value={subscriptions ? ugx(subscriptions.outstanding) : "—"} icon={<WarningCircle className="h-5 w-5" />} hintText="Unpaid subscription amounts" tone={subscriptions?.outstanding ? "amber" : "default"} />
      <StatCard label="Tenant collection volume" value={finance ? ugx(finance.totalCollected) : metrics ? ugx(metrics.totalRevenueUgx) : "—"} icon={<Wallet className="h-5 w-5" />} hintText="Platform-managed collections; not platform revenue" />
      <StatCard label="Tenant wallet liabilities" value={finance ? ugx(finance.tenantLiabilities + finance.reservedFunds) : "—"} icon={<Bank className="h-5 w-5" />} hintText="Available plus reserved tenant funds" tone="amber" />
      <StatCard label="Available platform earnings" value={platform ? ugx(platform.safeWithdrawable) : "—"} icon={<ChartLine className="h-5 w-5" />} hintText={`Reconciliation: ${platform?.reconcileStatus?.toLowerCase() || "unknown"}`} tone="green" />
      <StatCard label="Pending withdrawals" value={finance?.pendingWithdrawals ?? "—"} icon={<ArrowClockwise className="h-5 w-5" />} hintText="Tenant requests awaiting completion" tone={finance?.pendingWithdrawals ? "amber" : "default"} />
      <StatCard label="Online routers" value={metrics ? `${metrics.onlineRouters} / ${metrics.totalRouters}` : "—"} icon={<CellTower className="h-5 w-5" />} hintText="Based on reported router telemetry" />
      <StatCard label="Open sessions" value={metrics?.activeHotspotSessions ?? "—"} icon={<Radio className="h-5 w-5" />} hintText="Accounting sessions without a stop record" />
    </div>
    <section className={`${panel} flex flex-wrap items-center justify-between gap-4 p-5`}>
      <div><h2 className="text-sm font-semibold text-[var(--fg)]">Network and infrastructure</h2><p className="mt-1 max-w-2xl text-sm text-[var(--fg-muted)]">These business metrics do not verify WireGuard, RADIUS, or database service health. Live infrastructure probes are not connected to this page.</p></div>
      <Link href="/admin/health" className="text-sm font-semibold text-[var(--accent-text)]">View service details →</Link>
    </section>
    <div className="flex items-center justify-between"><h2 className="text-lg font-semibold text-[var(--fg)]">Recently added businesses</h2><Link href="/admin/tenants" className="text-sm font-semibold text-[var(--accent-text)]">View tenants →</Link></div>
    <Table rows={tenants} columns={[
      { label: "Business", render: t => <span className="font-medium text-[var(--fg)]">{t.name}</span> },
      { label: "Email", render: t => t.email }, { label: "Phone", render: t => t.phone },
      { label: "Routers", render: t => t.routerCount },
      { label: "Status", render: t => <Badge tone={t.status === "ACTIVE" ? "green" : "red"}>{t.status}</Badge> },
      { label: "Joined", render: t => date(t.createdAt) },
    ]} />
    {loading && <LoadingIndicator label="Refreshing platform data…" />}
  </div>;
}
