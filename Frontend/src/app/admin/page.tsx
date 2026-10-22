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
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">
            Platform Overview
          </h1>
          <p className="text-xs text-slate-500 mt-1">
            Global metrics, multi-tenant fleet status, and core services health
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => {
              setLoading(true);
              fetchData();
            }}
            className="px-3.5 py-1.5 rounded-xl bg-white border border-slate-200 text-xs font-medium text-slate-700 hover:text-slate-900 hover:bg-slate-100 transition"
          >
            Refresh Data
          </button>
        </div>
      </div>

      {/* Metric Cards Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Revenue */}
        <div className="p-5 rounded-2xl bg-white border border-slate-200 shadow-sm relative overflow-hidden">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-medium text-slate-500">Total System Revenue</span>
            <div className="p-2 rounded-xl bg-emerald-50 text-emerald-700">
              <DollarSign className="w-4 h-4" />
            </div>
          </div>
          <div className="text-2xl font-bold text-slate-900 mb-1">
            {metrics ? Number(metrics.totalRevenueUgx || 0).toLocaleString() : "0"}{" "}
            <span className="text-xs font-normal text-slate-500">UGX</span>
          </div>
          <p className="text-[11px] text-slate-500">
            {metrics?.totalTransactions || 0} mobile money collections
          </p>
        </div>

        {/* Hotspot Businesses */}
        <div className="p-5 rounded-2xl bg-white border border-slate-200 shadow-sm">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-medium text-slate-500">Hotspot Businesses</span>
            <div className="p-2 rounded-xl bg-blue-50 text-blue-700">
              <Users className="w-4 h-4" />
            </div>
          </div>
          <div className="text-2xl font-bold text-slate-900 mb-1">
            {metrics?.totalTenants ?? 0}
          </div>
          <p className="text-[11px] text-slate-500">Registered SaaS operators</p>
        </div>

        {/* Routers */}
        <div className="p-5 rounded-2xl bg-white border border-slate-200 shadow-sm">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-medium text-slate-500">Routers Provisioned</span>
            <div className="p-2 rounded-xl bg-blue-50 text-blue-700">
              <RouterIcon className="w-4 h-4" />
            </div>
          </div>
          <div className="text-2xl font-bold text-slate-900 mb-1">
            {metrics?.onlineRouters ?? 0}{" "}
            <span className="text-xs font-normal text-slate-500">
              / {metrics?.totalRouters ?? 0} online
            </span>
          </div>
          <p className="text-[11px] text-emerald-700 flex items-center gap-1">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-400" />
            WireGuard mesh tunnel connected
          </p>
        </div>

        {/* Active Hotspot Guests */}
        <div className="p-5 rounded-2xl bg-white border border-slate-200 shadow-sm">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs font-medium text-slate-500">Active Guest Sessions</span>
            <div className="p-2 rounded-xl bg-cyan-500/10 text-cyan-400">
              <Wifi className="w-4 h-4" />
            </div>
          </div>
          <div className="text-2xl font-bold text-slate-900 mb-1">
            {metrics?.activeHotspotSessions ?? 0}
          </div>
          <p className="text-[11px] text-slate-500">
            {metrics?.totalVouchers ?? 0} vouchers total generated
          </p>
        </div>
      </div>

      {/* Infrastructure Core Status */}
      <div className="p-6 rounded-xl bg-white border border-slate-200">
        <h2 className="text-sm font-semibold text-slate-900 mb-4 flex items-center gap-2">
          <Server className="w-4 h-4 text-blue-700" />
          <span>Core Infrastructure & Network Mesh</span>
        </h2>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="p-4 rounded-xl bg-[#F5F7FA]/60 border border-slate-200 flex items-start justify-between">
            <div>
              <div className="text-xs font-medium text-slate-700">WireGuard Hub (wg0)</div>
              <div className="text-[11px] text-slate-500 mt-0.5">10.9.0.1/16 • UDP :443</div>
              <div className="mt-3 flex items-center gap-1.5 text-[11px] text-emerald-700 font-medium">
                <CheckCircle2 className="w-3.5 h-3.5" />
                <span>Listening & Routing</span>
              </div>
            </div>
            <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-blue-50 text-blue-700 border border-blue-200">
              VPN HUB
            </span>
          </div>

          <div className="p-4 rounded-xl bg-[#F5F7FA]/60 border border-slate-200 flex items-start justify-between">
            <div>
              <div className="text-xs font-medium text-slate-700">FreeRADIUS 3.2.5 Core</div>
              <div className="text-[11px] text-slate-500 mt-0.5">PoD/CoA port 3799 • Auth :1812</div>
              <div className="mt-3 flex items-center gap-1.5 text-[11px] text-emerald-700 font-medium">
                <CheckCircle2 className="w-3.5 h-3.5" />
                <span>Dynamic NAS Resolution</span>
              </div>
            </div>
            <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
              AAA
            </span>
          </div>

          <div className="p-4 rounded-xl bg-[#F5F7FA]/60 border border-slate-200 flex items-start justify-between">
            <div>
              <div className="text-xs font-medium text-slate-700">PostgreSQL Data Pool</div>
              <div className="text-[11px] text-slate-500 mt-0.5">radius_db • HikariCP</div>
              <div className="mt-3 flex items-center gap-1.5 text-[11px] text-emerald-700 font-medium">
                <CheckCircle2 className="w-3.5 h-3.5" />
                <span>Shared with RADIUS Engine</span>
              </div>
            </div>
            <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-50 text-emerald-700 border border-emerald-200">
              DB READY
            </span>
          </div>
        </div>
      </div>

      {/* Recent Hotspot Operators */}
      <div className="p-6 rounded-xl bg-white border border-slate-200">
        <div className="flex items-center justify-between mb-4">
          <div>
            <h2 className="text-sm font-semibold text-slate-900">Recently Provisioned Operators</h2>
            <p className="text-xs text-slate-500">Latest hotspot businesses onboarded to the SaaS</p>
          </div>
          <a
            href="/admin/tenants"
            className="text-xs text-blue-700 hover:text-blue-600 flex items-center gap-1 font-medium"
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
                <tr className="border-b border-slate-200 text-slate-500">
                  <th className="pb-3 font-medium">Business / Name</th>
                  <th className="pb-3 font-medium">Email</th>
                  <th className="pb-3 font-medium">Phone</th>
                  <th className="pb-3 font-medium">Routers</th>
                  <th className="pb-3 font-medium">Status</th>
                  <th className="pb-3 font-medium text-right">Joined</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100/50">
                {recentTenants.map((t) => (
                  <tr key={t.id} className="hover:bg-slate-100/20">
                    <td className="py-3 font-medium text-slate-700">{t.name}</td>
                    <td className="py-3 text-slate-500">{t.email}</td>
                    <td className="py-3 text-slate-500">{t.phone}</td>
                    <td className="py-3 text-slate-700 font-semibold">{t.routerCount}</td>
                    <td className="py-3">
                      <span
                        className={`px-2 py-0.5 rounded-full text-[10px] font-semibold ${
                          t.status === "ACTIVE"
                            ? "bg-emerald-50 text-emerald-700 border border-emerald-200"
                            : "bg-red-50 text-red-600 border border-red-200"
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
