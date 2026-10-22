"use client";

import React, { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { DashboardLoading, LoadingIndicator } from "@/components/LoadingUI";
import { EmptyState } from "@/components/BillingUI";
import { useAuth } from "@/context/AuthContext";
import api from "@/lib/api";
import { ArrowUpRight, Eye, EyeOff, RefreshCw, Router, Ticket, Users, Wallet, Activity, AlertCircle } from "lucide-react";

interface WeeklySale { date: string; day: string; revenue: number; transactions: number }
interface Consumer { radAcctId: number; username: string; framedIpAddress: string; totalOctets: number; acctSessionTime: number }
interface RouterRevenue { routerId: number; name: string; status: string; revenueToday: number; revenueMonth: number; revenueTotal: number; transactions: number; activeUsers: number }
interface DashboardStats {
  revenueToday: number; revenueMonth: number; activeUsers: number; totalDataConsumedBytes: number;
  totalRouters: number; onlineRouters: number; totalVouchers: number; activeVouchers: number;
  usedVouchers: number; expiredVouchers: number; weeklySales: WeeklySale[]; routerRevenue: RouterRevenue[]; topConsumers: Consumer[];
}
const panel = "rounded-xl border border-slate-200 bg-white shadow-sm";
const number = (value: number) => Number(value).toLocaleString();
function bytes(value: number) {
  if (!value) return "0 MB";
  const unit = Math.min(Math.floor(Math.log(value) / Math.log(1024)), 4);
  return `${(value / 1024 ** unit).toLocaleString(undefined, { maximumFractionDigits: 2 })} ${["B", "KB", "MB", "GB", "TB"][unit]}`;
}

export default function TenantDashboardOverview() {
  const { user } = useAuth();
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [hidden, setHidden] = useState(false);
  const [updated, setUpdated] = useState<string | null>(null);
  const fetchStats = useCallback(async () => {
    setLoading(true);
    try {
      const response = await api.get<DashboardStats>("/api/dashboard/stats");
      setStats(response.data);
      setError(false);
      setUpdated(new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }));
    } catch { setError(true); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => {
    void fetchStats();
    const timer = window.setInterval(fetchStats, 30000);
    return () => window.clearInterval(timer);
  }, [fetchStats]);

  const sales = stats?.weeklySales || [];
  const weeklyRevenue = sales.reduce((sum, day) => sum + Number(day.revenue), 0);
  const transactions = sales.reduce((sum, day) => sum + day.transactions, 0);
  const maxRevenue = Math.max(...sales.map((day) => Number(day.revenue)), 1);
  const maxTransactions = Math.max(...sales.map((day) => day.transactions), 1);
  const money = (value: number) => hidden ? "••••••" : `UGX ${number(value)}`;
  const display = (value: string | number) => stats ? value : loading ? "…" : "—";
  const cards = [
    { label: "Today's revenue", value: money(stats?.revenueToday || 0), detail: "Successful payments today", icon: Wallet, color: "text-emerald-700" },
    { label: "Last 7 days revenue", value: money(weeklyRevenue), detail: `${number(transactions)} successful payments`, icon: Wallet, color: "text-amber-700" },
    { label: "Monthly revenue", value: money(stats?.revenueMonth || 0), detail: "Successful payments this month", icon: Wallet, color: "text-blue-700" },
    { label: "Active users", value: number(stats?.activeUsers || 0), detail: "Current hotspot sessions", icon: Users, color: "text-blue-700" },
    { label: "Data in active sessions", value: bytes(stats?.totalDataConsumedBytes || 0), detail: "Combined upload and download", icon: Activity, color: "text-emerald-700" },
    { label: "Total vouchers", value: number(stats?.totalVouchers || 0), detail: "All vouchers created", icon: Ticket, color: "text-blue-700" },
    { label: "Active vouchers", value: number(stats?.activeVouchers || 0), detail: "Vouchers marked active", icon: Ticket, color: "text-emerald-700" },
    { label: "Router health", value: `${stats?.totalRouters || 0} router${stats?.totalRouters === 1 ? "" : "s"}`, detail: `${stats?.onlineRouters || 0} online · ${Math.max(0, (stats?.totalRouters || 0) - (stats?.onlineRouters || 0))} offline / pending`, icon: Router, color: "text-amber-700" },
  ];

  if (loading && !stats) return <DashboardLoading />;

  return (
    <div className="mx-auto max-w-7xl space-y-5">
      <section className={`${panel} flex flex-wrap items-center justify-between gap-4 bg-white px-6 py-5`}>
        <div>
          <p className="mb-1 text-xs font-medium text-slate-500">YOUR BUSINESS AT A GLANCE</p>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Welcome back, {user?.name || "Hotspot Admin"}</h1>
          <p className="mt-1 text-sm text-slate-500">Sales, customers, and your hotspot network in one place.</p>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={() => setHidden(!hidden)} aria-label={hidden ? "Show revenue" : "Hide revenue"} className="rounded-lg border border-slate-300 p-2.5 text-slate-500 hover:text-slate-900">{hidden ? <EyeOff size={16} /> : <Eye size={16} />}</button>
          <button onClick={fetchStats} disabled={loading} aria-label="Refresh dashboard" className="rounded-lg border border-slate-300 p-2.5 text-slate-500 hover:text-slate-900 disabled:opacity-50"><RefreshCw size={16} className={loading ? "animate-spin" : ""} /></button>
        </div>
      </section>

      {error && <div role="alert" className="flex items-center gap-2 rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-700"><AlertCircle size={16} />{stats ? "Unable to refresh. Showing the last available data." : "Dashboard data could not be loaded. Please try refreshing."}</div>}

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4" aria-busy={loading}>
        {cards.map(({ label, value, detail, icon: Icon, color }) => (
          <section key={label} className={`${panel} min-h-36 p-5 lg:p-6`}>
            <p className="text-sm font-medium text-slate-600">{label}</p>
            <p className="mt-3 text-3xl font-semibold tracking-tight tabular-nums text-slate-900">{display(value)}</p>
            <p className={`mt-3 flex items-center gap-2 text-xs ${color}`}><Icon size={14} />{stats ? detail : "Awaiting dashboard data"}</p>
          </section>
        ))}
      </div>

      <div className="grid gap-5 lg:grid-cols-2">
        {[{ title: "Revenue", subtitle: "Successful payments · UGX", max: maxRevenue, color: "bg-emerald-500", value: (day: WeeklySale) => Number(day.revenue), format: (day: WeeklySale) => money(Number(day.revenue)) },
          { title: "Completed sales", subtitle: "Number of successful payments", max: maxTransactions, color: "bg-blue-500", value: (day: WeeklySale) => day.transactions, format: (day: WeeklySale) => `${day.transactions} payments` }].map((chart) => (
          <section key={chart.title} className={panel}>
            <div className="flex items-center justify-between gap-3 border-b border-slate-200 px-5 py-4"><div><h2 className="text-sm font-semibold text-slate-900">{chart.title}</h2><p className="mt-1 text-xs text-slate-500">{chart.subtitle}</p></div><span className="rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs text-slate-700">Last 7 days</span></div>
            <div className="p-5">
              {chart.title === "Revenue" && hidden ? (
                <EmptyState title="Revenue hidden" description="Use the eye button to show your revenue chart." />
              ) : !stats ? (
                <EmptyState title="Sales data unavailable" description="Refresh the dashboard to try again." />
              ) : !sales.some(day => chart.value(day) > 0) ? (
                <EmptyState title="No successful payments yet" description="Your last seven days of sales will appear here as payments are recorded." action={<Link href="/dashboard/payments" className="mt-2 text-sm font-semibold text-blue-700">View payments →</Link>} />
              ) : (
                <div className="flex h-56 items-end gap-3" role="img" aria-label={`${chart.title} for the last seven days`}>
                  {sales.map(day => (
                    <div key={day.date} className="flex h-full min-w-0 flex-1 flex-col items-center justify-end">
                      <div className="flex w-full flex-1 items-end justify-center border-b border-slate-200">
                        <div tabIndex={0} title={`${day.day}: ${chart.format(day)}`} aria-label={`${day.day}: ${chart.format(day)}`}
                          style={{ height: `${chart.value(day) / chart.max * 100}%` }}
                          className={`w-full max-w-12 rounded-t ${chart.color} transition-opacity hover:opacity-80`} />
                      </div>
                      <span className="mt-3 text-xs font-medium text-slate-600">{day.day}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </section>
        ))}
      </div>

      <div className="grid gap-5 lg:grid-cols-2">
        <section className={panel}>
          <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4"><div><h2 className="text-sm font-semibold text-slate-900">Top data users</h2><p className="mt-1 text-xs text-slate-500">Highest usage among current sessions</p></div><Link href="/dashboard/sessions" className="flex items-center gap-1 text-xs text-blue-700">View sessions<ArrowUpRight size={14} /></Link></div>
          <div className="divide-y divide-slate-100 px-5">
            {stats?.topConsumers?.map((session, index) => <div key={session.radAcctId} className="flex items-center gap-3 py-4"><span className="flex h-7 w-7 items-center justify-center rounded-full bg-slate-100 text-xs text-slate-500">{index + 1}</span><div className="min-w-0 flex-1"><p className="truncate text-sm font-medium text-slate-700">{session.username}</p><p className="mt-1 text-xs text-slate-500">{session.framedIpAddress || "IP unavailable"}</p></div><span className="rounded-md bg-blue-50 px-2 py-1 text-xs font-medium text-blue-700">{bytes(session.totalOctets)}</span></div>)}
            {!stats?.topConsumers?.length && <div className="py-12 text-center"><Users className="mx-auto mb-3 h-7 w-7 text-slate-600" /><p className="text-sm text-slate-500">{loading ? "Loading sessions…" : "No active users yet"}</p><p className="mt-1 text-xs text-slate-500">Connected customers will appear here.</p></div>}
          </div>
        </section>
        <section className={panel}>
          <div className="border-b border-slate-200 px-5 py-4"><h2 className="text-sm font-semibold text-slate-900">Voucher inventory</h2><p className="mt-1 text-xs text-slate-500">Manage customer access to your hotspot</p></div>
          <div className="space-y-4 p-5">
            {[{ label: "Active", value: stats?.activeVouchers, color: "bg-emerald-500" }, { label: "Used", value: stats?.usedVouchers, color: "bg-blue-500" }, { label: "Expired or revoked", value: stats?.expiredVouchers, color: "bg-amber-500" }].map((item) => <div key={item.label} className="flex items-center justify-between text-sm"><span className="flex items-center gap-2 text-slate-500"><span className={`h-2 w-2 rounded-full ${item.color}`} />{item.label}</span><span className="font-medium text-slate-900">{display(item.value || 0)}</span></div>)}
            <Link href="/dashboard/vouchers" className="mt-6 flex items-center justify-center gap-2 rounded-lg border border-slate-200 bg-white py-2.5 text-xs font-medium text-slate-700 hover:bg-slate-100"><Ticket size={14} />Manage vouchers<ArrowUpRight size={14} /></Link>
          </div>
        </section>
      </div>

      <section className={panel}>
        <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4">
          <div><h2 className="text-sm font-semibold text-slate-900">Revenue by router</h2><p className="mt-1 text-xs text-slate-500">How much each location is earning</p></div>
          <Link href="/dashboard/routers" className="flex items-center gap-1 text-xs text-blue-700">Manage routers<ArrowUpRight size={14} /></Link>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-left text-xs text-slate-500">
                <th className="px-5 py-3 font-medium">Router</th>
                <th className="px-5 py-3 font-medium">Status</th>
                <th className="px-5 py-3 text-right font-medium">Users</th>
                <th className="px-5 py-3 text-right font-medium">Today</th>
                <th className="px-5 py-3 text-right font-medium">This month</th>
                <th className="px-5 py-3 text-right font-medium">All time</th>
                <th className="px-5 py-3 text-right font-medium">Sales</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {stats?.routerRevenue?.map((router) => (
                <tr key={router.routerId} className="hover:bg-slate-50">
                  <td className="px-5 py-3.5 font-medium text-slate-700">{router.name}</td>
                  <td className="px-5 py-3.5">
                    <span className={`rounded-full px-2 py-0.5 text-[11px] font-medium ${router.status === "online" ? "bg-emerald-50 text-emerald-700" : router.status === "registered" ? "bg-amber-50 text-amber-700" : "bg-slate-100 text-slate-600"}`}>{router.status}</span>
                  </td>
                  <td className="px-5 py-3.5 text-right text-slate-700">{router.activeUsers}</td>
                  <td className="px-5 py-3.5 text-right font-medium text-slate-900">{money(router.revenueToday)}</td>
                  <td className="px-5 py-3.5 text-right text-slate-700">{money(router.revenueMonth)}</td>
                  <td className="px-5 py-3.5 text-right text-slate-700">{money(router.revenueTotal)}</td>
                  <td className="px-5 py-3.5 text-right text-slate-500">{router.transactions}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {!stats?.routerRevenue?.length && <div className="py-12 text-center"><Router className="mx-auto mb-3 h-7 w-7 text-slate-600" /><p className="text-sm text-slate-500">{loading ? "Loading routers…" : "No routers linked yet"}</p><p className="mt-1 text-xs text-slate-500">Revenue will be tracked per router once you link one.</p></div>}
        </div>
      </section>

      {loading && <LoadingIndicator label="Refreshing dashboard…" />}
      <p className="text-right text-xs text-slate-600">{updated ? `Updated ${updated} · Refreshes every 30 seconds` : "Waiting for first update"}</p>
    </div>
  );
}
