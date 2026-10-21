"use client";

import React, { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useAuth } from "@/context/AuthContext";
import api from "@/lib/api";
import { ArrowUpRight, Eye, EyeOff, RefreshCw, Router, Ticket, Users, Wallet, Activity, AlertCircle, Plus } from "lucide-react";

interface WeeklySale { date: string; day: string; revenue: number; transactions: number }
interface Consumer { radAcctId: number; username: string; framedIpAddress: string; totalOctets: number; acctSessionTime: number }
interface DashboardStats {
  revenueToday: number; revenueMonth: number; activeUsers: number; totalDataConsumedBytes: number;
  totalRouters: number; onlineRouters: number; totalVouchers: number; activeVouchers: number;
  usedVouchers: number; expiredVouchers: number; weeklySales: WeeklySale[]; topConsumers: Consumer[];
}
const panel = "rounded-xl border border-[#303034] bg-[#202023]";
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
    { label: "Today's revenue", value: money(stats?.revenueToday || 0), detail: "Successful payments today", icon: Wallet, color: "text-emerald-400" },
    { label: "Last 7 days revenue", value: money(weeklyRevenue), detail: `${number(transactions)} successful payments`, icon: Wallet, color: "text-amber-400" },
    { label: "Monthly revenue", value: money(stats?.revenueMonth || 0), detail: "Successful payments this month", icon: Wallet, color: "text-blue-400" },
    { label: "Active users", value: number(stats?.activeUsers || 0), detail: "Current hotspot sessions", icon: Users, color: "text-blue-400" },
    { label: "Data in active sessions", value: bytes(stats?.totalDataConsumedBytes || 0), detail: "Combined upload and download", icon: Activity, color: "text-emerald-400" },
    { label: "Total vouchers", value: number(stats?.totalVouchers || 0), detail: "All vouchers created", icon: Ticket, color: "text-blue-400" },
    { label: "Active vouchers", value: number(stats?.activeVouchers || 0), detail: "Vouchers marked active", icon: Ticket, color: "text-emerald-400" },
    { label: "Router health", value: `${stats?.totalRouters || 0} router${stats?.totalRouters === 1 ? "" : "s"}`, detail: `${stats?.onlineRouters || 0} online · ${Math.max(0, (stats?.totalRouters || 0) - (stats?.onlineRouters || 0))} offline / pending`, icon: Router, color: "text-amber-400" },
  ];

  return (
    <div className="mx-auto max-w-7xl space-y-5">
      <section className={`${panel} flex flex-wrap items-center justify-between gap-4 bg-[#29292d] px-6 py-5`}>
        <div>
          <p className="mb-1 text-xs font-medium text-slate-400">YOUR BUSINESS AT A GLANCE</p>
          <h1 className="text-xl font-semibold tracking-tight text-white">Welcome back, {user?.name || "Hotspot Admin"}</h1>
          <p className="mt-1 text-sm text-slate-400">Sales, customers, and your hotspot network in one place.</p>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={() => setHidden(!hidden)} aria-label={hidden ? "Show revenue" : "Hide revenue"} className="rounded-lg border border-[#414147] p-2.5 text-slate-400 hover:text-white">{hidden ? <EyeOff size={16} /> : <Eye size={16} />}</button>
          <button onClick={fetchStats} disabled={loading} aria-label="Refresh dashboard" className="rounded-lg border border-[#414147] p-2.5 text-slate-400 hover:text-white disabled:opacity-50"><RefreshCw size={16} className={loading ? "animate-spin" : ""} /></button>
          <Link href="/dashboard/routers/new" className="flex items-center gap-2 rounded-lg bg-blue-600 px-3 py-2.5 text-xs font-semibold text-white hover:bg-blue-500"><Plus size={15} />Add router</Link>
        </div>
      </section>

      {error && <div role="alert" className="flex items-center gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 p-4 text-sm text-amber-300"><AlertCircle size={16} />{stats ? "Unable to refresh. Showing the last available data." : "Dashboard data could not be loaded. Please try refreshing."}</div>}

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4" aria-busy={loading}>
        {cards.map(({ label, value, detail, icon: Icon, color }) => (
          <section key={label} className={`${panel} min-h-36 p-5`}>
            <p className="text-xs font-medium text-slate-400">{label}</p>
            <p className="mt-3 text-2xl font-semibold tracking-tight text-white">{display(value)}</p>
            <p className={`mt-3 flex items-center gap-2 text-xs ${color}`}><Icon size={14} />{stats ? detail : "Awaiting dashboard data"}</p>
          </section>
        ))}
      </div>

      <div className="grid gap-5 lg:grid-cols-2">
        {[{ title: "Revenue", subtitle: "Successful payments · UGX", max: maxRevenue, color: "bg-emerald-500", value: (day: WeeklySale) => Number(day.revenue), format: (day: WeeklySale) => money(Number(day.revenue)) },
          { title: "Completed sales", subtitle: "Number of successful payments", max: maxTransactions, color: "bg-blue-500", value: (day: WeeklySale) => day.transactions, format: (day: WeeklySale) => `${day.transactions} payments` }].map((chart) => (
          <section key={chart.title} className={panel}>
            <div className="flex items-center justify-between gap-3 border-b border-[#303034] px-5 py-4"><div><h2 className="text-sm font-semibold text-white">{chart.title}</h2><p className="mt-1 text-xs text-slate-500">{chart.subtitle}</p></div><span className="rounded-md border border-[#3d3d43] bg-[#29292d] px-3 py-1.5 text-xs text-slate-300">Last 7 days</span></div>
            <div className="relative px-5 pb-5 pt-8">
              <div className="absolute inset-x-5 top-8 bottom-12 flex flex-col justify-between" aria-hidden="true">{[0, 1, 2, 3, 4].map((i) => <div key={i} className="border-t border-[#303034]" />)}</div>
              <div className="relative flex h-48 items-end justify-around gap-3" aria-label={`${chart.title} for the last seven days`}>
                {sales.map((day) => <div key={day.date} className="flex h-full min-w-0 flex-1 flex-col justify-end items-center gap-2"><div tabIndex={0} title={`${day.day}: ${chart.format(day)}`} aria-label={`${day.day}: ${chart.format(day)}`} style={{ height: `${chart.title === "Revenue" && hidden ? 0 : chart.value(day) / chart.max * 100}%`, minHeight: 2 }} className={`w-full max-w-12 rounded-t-sm ${chart.color} hover:opacity-80`} /><span className="text-[11px] text-slate-500">{day.day}</span></div>)}
                {!sales.length && <p className="self-center text-sm text-slate-500">{loading ? "Loading sales…" : "No sales data available"}</p>}
              </div>
              {sales.length > 0 && !sales.some((day) => chart.value(day) > 0) && <p className="absolute inset-x-0 top-24 text-center text-xs text-slate-400">No successful payments in this period</p>}
              {chart.title === "Revenue" && hidden && <p className="absolute inset-x-0 top-24 text-center text-xs text-slate-400">Revenue hidden</p>}
            </div>
          </section>
        ))}
      </div>

      <div className="grid gap-5 lg:grid-cols-2">
        <section className={panel}>
          <div className="flex items-center justify-between border-b border-[#303034] px-5 py-4"><div><h2 className="text-sm font-semibold text-white">Top data users</h2><p className="mt-1 text-xs text-slate-500">Highest usage among current sessions</p></div><Link href="/dashboard/sessions" className="flex items-center gap-1 text-xs text-blue-400">View sessions<ArrowUpRight size={14} /></Link></div>
          <div className="divide-y divide-[#303034] px-5">
            {stats?.topConsumers.map((session, index) => <div key={session.radAcctId} className="flex items-center gap-3 py-4"><span className="flex h-7 w-7 items-center justify-center rounded-full bg-[#303034] text-xs text-slate-400">{index + 1}</span><div className="min-w-0 flex-1"><p className="truncate text-sm font-medium text-slate-200">{session.username}</p><p className="mt-1 text-xs text-slate-500">{session.framedIpAddress || "IP unavailable"}</p></div><span className="rounded-md bg-blue-500/10 px-2 py-1 text-xs font-medium text-blue-400">{bytes(session.totalOctets)}</span></div>)}
            {!stats?.topConsumers.length && <div className="py-12 text-center"><Users className="mx-auto mb-3 h-7 w-7 text-slate-600" /><p className="text-sm text-slate-400">{loading ? "Loading sessions…" : "No active users yet"}</p><p className="mt-1 text-xs text-slate-500">Connected customers will appear here.</p></div>}
          </div>
        </section>
        <section className={panel}>
          <div className="border-b border-[#303034] px-5 py-4"><h2 className="text-sm font-semibold text-white">Voucher inventory</h2><p className="mt-1 text-xs text-slate-500">Manage customer access to your hotspot</p></div>
          <div className="space-y-4 p-5">
            {[{ label: "Active", value: stats?.activeVouchers, color: "bg-emerald-500" }, { label: "Used", value: stats?.usedVouchers, color: "bg-blue-500" }, { label: "Expired or revoked", value: stats?.expiredVouchers, color: "bg-amber-500" }].map((item) => <div key={item.label} className="flex items-center justify-between text-sm"><span className="flex items-center gap-2 text-slate-400"><span className={`h-2 w-2 rounded-full ${item.color}`} />{item.label}</span><span className="font-medium text-white">{display(item.value || 0)}</span></div>)}
            <Link href="/dashboard/vouchers" className="mt-6 flex items-center justify-center gap-2 rounded-lg border border-[#3d3d43] bg-[#29292d] py-2.5 text-xs font-medium text-slate-200 hover:bg-[#303034]"><Ticket size={14} />Manage vouchers<ArrowUpRight size={14} /></Link>
          </div>
        </section>
      </div>
      <p className="text-right text-[11px] text-slate-500">{updated ? `Updated ${updated} · Refreshes every 30 seconds` : "Waiting for first update"}</p>
    </div>
  );
}
