"use client";

import React, { useEffect, useState } from "react";
import api from "@/lib/api";
import {
  DollarSign,
  Users,
  Router as RouterIcon,
  Wifi,
  Server,
  ArrowUpRight,
  ShieldCheck,
  CheckCircle2,
  Clock
} from "lucide-react";

interface PlatformMetrics {
  totalTenants: number;
  totalRouters: number;
  onlineRouters: number;
  totalVouchers: number;
  totalTransactions: number;
  totalRevenueUgx: number;
  activeHotspotSessions: number;
}

interface TenantSummary {
  id: number;
  name: string;
  email: string;
  phone: string;
  status: string;
  routerCount: number;
  voucherCount: number;
  createdAt: string;
}

export default function AdminOverviewPage() {
  const [metrics, setMetrics] = useState<PlatformMetrics | null>(null);
  const [recentTenants, setRecentTenants] = useState<TenantSummary[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchData = async () => {
    try {
      const [metricsRes, tenantsRes] = await Promise.all([
        api.get<PlatformMetrics>("/api/admin/metrics"),
        api.get<TenantSummary[]>("/api/admin/tenants"),
      ]);
      setMetrics(metricsRes.data);
      setRecentTenants(tenantsRes.data.slice(0, 5));
    } catch (err) {
      console.error("Failed to load admin overview data:", err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, []);

  return (
    <div className="space-y-8 max-w-7xl mx-auto">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-white">
            Platform Overview
          </h1>
          <p className="text-xs text-slate-400 mt-1">
            Global metrics, multi-tenant fleet status, and core services health
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => {
              setLoading(true);
              fetchData();
            }}
            className="px-3.5 py-1.5 rounded-xl bg-slate-900 border border-slate-800 text-xs font-medium text-slate-300 hover:text-white hover:bg-slate-800 transition"
          >
            Refresh Data
          </button>
        </div>
      </div>

      {/* Metric Cards Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Revenue */}
        <div className="p-5 rounded-2xl bg-slate-900/70 border border-slate-800/80 backdrop-blur-xl relative overflow-hidden">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-medium text-slate-400">Total System Revenue</span>
            <div className="p-2 rounded-xl bg-emerald-500/10 text-emerald-400">
              <DollarSign className="w-4 h-4" />
            </div>
          </div>
          <div className="text-2xl font-bold text-white mb-1">
            {metrics ? Number(metrics.totalRevenueUgx || 0).toLocaleString() : "0"}{" "}
            <span className="text-xs font-normal text-slate-400">UGX</span>
          </div>
          <p className="text-[11px] text-slate-400">
            {metrics?.totalTransactions || 0} mobile money collections
          </p>
        </div>

        {/* Hotspot Businesses */}
        <div className="p-5 rounded-2xl bg-slate-900/70 border border-slate-800/80 backdrop-blur-xl">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-medium text-slate-400">Hotspot Businesses</span>
            <div className="p-2 rounded-xl bg-indigo-500/10 text-indigo-400">
              <Users className="w-4 h-4" />
            </div>
          </div>
          <div className="text-2xl font-bold text-white mb-1">
            {metrics?.totalTenants ?? 0}
          </div>
          <p className="text-[11px] text-slate-400">Registered SaaS operators</p>
        </div>

        {/* Routers */}
        <div className="p-5 rounded-2xl bg-slate-900/70 border border-slate-800/80 backdrop-blur-xl">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-medium text-slate-400">Routers Provisioned</span>
            <div className="p-2 rounded-xl bg-blue-500/10 text-blue-400">
              <RouterIcon className="w-4 h-4" />
            </div>
          </div>
          <div className="text-2xl font-bold text-white mb-1">
            {metrics?.onlineRouters ?? 0}{" "}
            <span className="text-xs font-normal text-slate-400">
              / {metrics?.totalRouters ?? 0} online
            </span>
          </div>
          <p className="text-[11px] text-emerald-400 flex items-center gap-1">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-400" />
            WireGuard mesh tunnel connected
          </p>
        </div>

        {/* Active Hotspot Guests */}
        <div className="p-5 rounded-2xl bg-slate-900/70 border border-slate-800/80 backdrop-blur-xl">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-medium text-slate-400">Active Guest Sessions</span>
            <div className="p-2 rounded-xl bg-cyan-500/10 text-cyan-400">
              <Wifi className="w-4 h-4" />
            </div>
          </div>
          <div className="text-2xl font-bold text-white mb-1">
            {metrics?.activeHotspotSessions ?? 0}
          </div>
          <p className="text-[11px] text-slate-400">
            {metrics?.totalVouchers ?? 0} vouchers total generated
          </p>
        </div>
      </div>

      {/* Infrastructure Core Status */}
      <div className="p-6 rounded-2xl bg-slate-900/70 border border-slate-800/80">
        <h2 className="text-sm font-semibold text-white mb-4 flex items-center gap-2">
          <Server className="w-4 h-4 text-indigo-400" />
          <span>Core Infrastructure & Network Mesh</span>
        </h2>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="p-4 rounded-xl bg-slate-950/60 border border-slate-800/80 flex items-start justify-between">
            <div>
              <div className="text-xs font-medium text-slate-200">WireGuard Hub (wg0)</div>
              <div className="text-[11px] text-slate-400 mt-0.5">10.9.0.1/16 • UDP :443</div>
              <div className="mt-3 flex items-center gap-1.5 text-[11px] text-emerald-400 font-medium">
                <CheckCircle2 className="w-3.5 h-3.5" />
                <span>Listening & Routing</span>
              </div>
            </div>
            <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-indigo-500/10 text-indigo-400 border border-indigo-500/20">
              VPN HUB
            </span>
          </div>

          <div className="p-4 rounded-xl bg-slate-950/60 border border-slate-800/80 flex items-start justify-between">
            <div>
              <div className="text-xs font-medium text-slate-200">FreeRADIUS 3.2.5 Core</div>
              <div className="text-[11px] text-slate-400 mt-0.5">PoD/CoA port 3799 • Auth :1812</div>
              <div className="mt-3 flex items-center gap-1.5 text-[11px] text-emerald-400 font-medium">
                <CheckCircle2 className="w-3.5 h-3.5" />
                <span>Dynamic NAS Resolution</span>
              </div>
            </div>
            <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
              AAA
            </span>
          </div>

          <div className="p-4 rounded-xl bg-slate-950/60 border border-slate-800/80 flex items-start justify-between">
            <div>
              <div className="text-xs font-medium text-slate-200">PostgreSQL Data Pool</div>
              <div className="text-[11px] text-slate-400 mt-0.5">radius_db • HikariCP</div>
              <div className="mt-3 flex items-center gap-1.5 text-[11px] text-emerald-400 font-medium">
                <CheckCircle2 className="w-3.5 h-3.5" />
                <span>Shared with RADIUS Engine</span>
              </div>
            </div>
            <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
              DB READY
            </span>
          </div>
        </div>
      </div>

      {/* Recent Hotspot Operators */}
      <div className="p-6 rounded-2xl bg-slate-900/70 border border-slate-800/80">
        <div className="flex items-center justify-between mb-4">
          <div>
            <h2 className="text-sm font-semibold text-white">Recently Provisioned Operators</h2>
            <p className="text-xs text-slate-400">Latest hotspot businesses onboarded to the SaaS</p>
          </div>
          <a
            href="/admin/tenants"
            className="text-xs text-indigo-400 hover:text-indigo-300 flex items-center gap-1 font-medium"
          >
            <span>View All Operators</span>
            <ArrowUpRight className="w-3.5 h-3.5" />
          </a>
        </div>

        {recentTenants.length === 0 ? (
          <div className="py-8 text-center text-xs text-slate-500">
            No hotspot operators created yet. Click &quot;Hotspot Operators&quot; to provision the first tenant.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead>
                <tr className="border-b border-slate-800 text-slate-400">
                  <th className="pb-3 font-medium">Business / Name</th>
                  <th className="pb-3 font-medium">Email</th>
                  <th className="pb-3 font-medium">Phone</th>
                  <th className="pb-3 font-medium">Routers</th>
                  <th className="pb-3 font-medium">Status</th>
                  <th className="pb-3 font-medium text-right">Joined</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/50">
                {recentTenants.map((t) => (
                  <tr key={t.id} className="hover:bg-slate-800/20">
                    <td className="py-3 font-medium text-slate-200">{t.name}</td>
                    <td className="py-3 text-slate-400">{t.email}</td>
                    <td className="py-3 text-slate-400">{t.phone}</td>
                    <td className="py-3 text-slate-300 font-semibold">{t.routerCount}</td>
                    <td className="py-3">
                      <span
                        className={`px-2 py-0.5 rounded-full text-[10px] font-semibold ${
                          t.status === "ACTIVE"
                            ? "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20"
                            : "bg-red-500/10 text-red-400 border border-red-500/20"
                        }`}
                      >
                        {t.status}
                      </span>
                    </td>
                    <td className="py-3 text-right text-slate-500">
                      {new Date(t.createdAt).toLocaleDateString()}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
