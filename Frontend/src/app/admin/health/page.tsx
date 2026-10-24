"use client";

import React from "react";
import { HardDrives, ChartLine, CheckCircle, ShieldCheck, Database, Radio } from "@phosphor-icons/react";

export default function AdminHealthPage() {
  const components = [
    {
      name: "WireGuard Mesh Hub (wg0)",
      description: "Virtual network hub on 10.9.0.1/16, listening on UDP 443",
      status: "NOT VERIFIED",
      icon: Radio,
      badge: "UDP 443",
    },
    {
      name: "FreeRADIUS core",
      description: "SQL-backed authorization via radcheck/radreply and RFC 5176 CoA/PoD on port 3799",
      status: "NOT VERIFIED",
      icon: ChartLine,
      badge: "RADIUS 1812/3799",
    },
    {
      name: "PostgreSQL Database Engine",
      description: "Shared database (radius_db) servicing both Spring Boot backend and FreeRADIUS",
      status: "NOT VERIFIED",
      icon: Database,
      badge: "JDBC 5432",
    },
    {
      name: "Voucher Expiration & Disconnect Worker",
      description: "30-second scheduled background task detecting newly logged-in vouchers and kicking expired sessions",
      status: "NOT VERIFIED",
      icon: HardDrives,
      badge: "WORKER",
    },
  ];

  return (
    <div className="space-y-6 max-w-7xl mx-auto">
      <div>
        <h1 className="text-xl font-bold tracking-tight text-slate-900 flex items-center gap-2.5">
          <HardDrives className="w-6 h-6 text-amber-700" />
          <span>System &amp; VPN Health</span>
        </h1>
        <p className="text-xs text-slate-500 mt-1">
          Configured services. Live probes are not connected; these cards do not report current health.
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {components.map((c) => {
          const Icon = c.icon;
          return (
            <div
              key={c.name}
              className="p-5 rounded-2xl bg-white/70 border border-slate-200 flex items-start gap-4"
            >
              <div className="p-3 rounded-xl bg-[#F5F7FA] border border-slate-200 text-blue-700 shrink-0">
                <Icon className="w-5 h-5" />
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center justify-between mb-1">
                  <h3 className="text-sm font-semibold text-slate-900 truncate">{c.name}</h3>
                  <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-blue-50 text-blue-700 border border-blue-200">
                    {c.badge}
                  </span>
                </div>
                <p className="text-xs text-slate-500 mb-3">{c.description}</p>
                <div className="flex items-center gap-1.5 text-xs text-amber-700 font-medium">
                  <CheckCircle className="w-3.5 h-3.5" />
                  <span>{c.status}</span>
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
