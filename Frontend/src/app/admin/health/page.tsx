"use client";

import React from "react";
import { Server, Activity, CheckCircle2, ShieldCheck, Database, Radio } from "lucide-react";

export default function AdminHealthPage() {
  const components = [
    {
      name: "WireGuard Mesh Hub (wg0)",
      description: "Virtual network hub on 10.9.0.1/16, listening on UDP 443",
      status: "HEALTHY",
      icon: Radio,
      badge: "UDP 443",
    },
    {
      name: "FreeRADIUS 3.2.5 Core",
      description: "SQL-backed authorization via radcheck/radreply and RFC 5176 CoA/PoD on port 3799",
      status: "HEALTHY",
      icon: Activity,
      badge: "RADIUS 1812/3799",
    },
    {
      name: "PostgreSQL Database Engine",
      description: "Shared database (radius_db) servicing both Spring Boot backend and FreeRADIUS",
      status: "HEALTHY",
      icon: Database,
      badge: "JDBC 5432",
    },
    {
      name: "Voucher Expiration & Disconnect Worker",
      description: "30-second scheduled background task detecting newly logged-in vouchers and kicking expired sessions",
      status: "ACTIVE",
      icon: Server,
      badge: "WORKER",
    },
  ];

  return (
    <div className="space-y-6 max-w-7xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-white flex items-center gap-2.5">
          <Server className="w-6 h-6 text-emerald-400" />
          <span>System &amp; VPN Health</span>
        </h1>
        <p className="text-xs text-slate-400 mt-1">
          Diagnostics for FreeRADIUS AAA core, WireGuard hub, and automated background workers
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {components.map((c) => {
          const Icon = c.icon;
          return (
            <div
              key={c.name}
              className="p-5 rounded-2xl bg-slate-900/70 border border-slate-800/80 flex items-start gap-4"
            >
              <div className="p-3 rounded-xl bg-slate-950 border border-slate-800 text-indigo-400 shrink-0">
                <Icon className="w-5 h-5" />
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center justify-between mb-1">
                  <h3 className="text-sm font-semibold text-white truncate">{c.name}</h3>
                  <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-indigo-500/10 text-indigo-400 border border-indigo-500/20">
                    {c.badge}
                  </span>
                </div>
                <p className="text-xs text-slate-400 mb-3">{c.description}</p>
                <div className="flex items-center gap-1.5 text-xs text-emerald-400 font-medium">
                  <CheckCircle2 className="w-3.5 h-3.5" />
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
