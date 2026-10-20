"use client";

import { useAuth } from "@/context/AuthContext";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { LogOut, Radio, Wrench } from "lucide-react";

export default function TenantDashboardPlaceholder() {
  const { user, loading, logout } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!loading && !user) {
      router.replace("/login");
    }
  }, [user, loading, router]);

  if (loading || !user) return null;

  return (
    <div className="min-h-screen bg-slate-950 text-white p-8 flex flex-col justify-between">
      <header className="flex justify-between items-center border-b border-slate-800 pb-4">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-indigo-600/20 rounded-xl text-indigo-400">
            <Radio className="w-6 h-6" />
          </div>
          <div>
            <h1 className="text-xl font-bold">{user.name}</h1>
            <p className="text-xs text-slate-400">Hotspot Business Operator ({user.email})</p>
          </div>
        </div>
        <button
          onClick={logout}
          className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-slate-900 border border-slate-800 text-xs text-slate-300 hover:text-white hover:bg-slate-800 transition"
        >
          <LogOut className="w-3.5 h-3.5" />
          <span>Sign Out</span>
        </button>
      </header>

      <main className="max-w-xl mx-auto text-center py-24">
        <div className="w-16 h-16 bg-indigo-500/10 border border-indigo-500/20 text-indigo-400 rounded-3xl mx-auto flex items-center justify-center mb-6">
          <Wrench className="w-8 h-8 animate-bounce" />
        </div>
        <h2 className="text-2xl font-bold mb-2">Hotspot Admin Portal</h2>
        <p className="text-slate-400 text-sm leading-relaxed mb-8">
          Logged in as <b>{user.email}</b>. Full fleet routers, package rate limits, and voucher cut-outs interface ready for integration.
        </p>
        <span className="inline-flex items-center gap-2 px-3 py-1 rounded-full text-xs font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
          ● Ready for Hotspot Fleet Views
        </span>
      </main>

      <footer className="text-center text-xs text-slate-600 border-t border-slate-900 pt-4">
        WiFi-Billing SaaS • FreeRADIUS 3.2.5 Core
      </footer>
    </div>
  );
}
