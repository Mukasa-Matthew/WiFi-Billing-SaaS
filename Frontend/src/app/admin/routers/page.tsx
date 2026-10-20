"use client";

import React from "react";
import { Router as RouterIcon, Signal, CheckCircle2, ShieldCheck, Terminal } from "lucide-react";

export default function AdminRoutersPage() {
  return (
    <div className="space-y-6 max-w-7xl mx-auto">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-white flex items-center gap-2.5">
            <RouterIcon className="w-6 h-6 text-blue-400" />
            <span>Global Fleet Routers</span>
          </h1>
          <p className="text-xs text-slate-400 mt-1">
            Real-time status of all MikroTik routers across all tenant hotspot networks
          </p>
        </div>
      </div>

      <div className="p-6 rounded-2xl bg-slate-900/70 border border-slate-800/80">
        <div className="flex items-center gap-3 mb-4">
          <div className="p-2 rounded-xl bg-blue-500/10 text-blue-400">
            <Signal className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-sm font-semibold text-white">WireGuard Mesh Status</h2>
            <p className="text-xs text-slate-400">Subnet: 10.9.0.0/16 • Hub Endpoint: 10.9.0.1:443</p>
          </div>
        </div>

        <p className="text-xs text-slate-300 leading-relaxed max-w-3xl">
          Routers establish persistent WireGuard client tunnels to the billing server. The backend updates peers via <code>wg set wg0 peer</code> and handles heartbeats through <code>/api/router/telemetry</code> every 2 minutes.
        </p>

        <div className="mt-6 p-4 rounded-xl bg-slate-950/80 border border-slate-800 font-mono text-xs text-indigo-400 flex items-center gap-2">
          <Terminal className="w-4 h-4 text-slate-500" />
          <span>RouterOS Setup: /tool fetch url=&quot;http://&lt;SERVER_IP&gt;:8080/api/router/provision/&lt;TOKEN&gt;&quot; dst-path=wb.rsc &amp;&amp; /import wb.rsc</span>
        </div>
      </div>
    </div>
  );
}
