"use client";

import React, { useEffect } from "react";
import Link from "next/navigation";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import {
  Activity,
  Users,
  Router as RouterIcon,
  Server,
  LogOut,
  Radio,
  Shield,
  Layers
} from "lucide-react";

export default function AdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const { user, loading, logout } = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (!loading) {
      if (!user) {
        router.replace("/login");
      } else if (user.role !== "ROLE_SUPERADMIN") {
        router.replace("/dashboard");
      }
    }
  }, [user, loading, router]);

  if (loading || !user || user.role !== "ROLE_SUPERADMIN") {
    return (
      <div className="flex h-screen items-center justify-center bg-slate-950 text-slate-400">
        <div className="flex flex-col items-center gap-3">
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-indigo-500 border-t-transparent" />
          <p className="text-sm">Verifying Superadmin Access...</p>
        </div>
      </div>
    );
  }

  const navItems = [
    { name: "Platform Overview", href: "/admin", icon: Activity },
    { name: "Hotspot Operators", href: "/admin/tenants", icon: Users },
    { name: "Fleet Routers", href: "/admin/routers", icon: RouterIcon },
    { name: "VPN & Mesh Health", href: "/admin/health", icon: Server },
  ];

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex">
      {/* Sidebar */}
      <aside className="w-64 border-r border-slate-800/80 bg-slate-900/60 backdrop-blur-xl flex flex-col justify-between shrink-0">
        <div>
          {/* Logo */}
          <div className="h-16 flex items-center gap-3 px-6 border-b border-slate-800/80">
            <div className="p-2 rounded-xl bg-gradient-to-tr from-indigo-600 to-emerald-500 text-white shadow-md shadow-indigo-600/20">
              <Radio className="w-5 h-5" />
            </div>
            <div>
              <span className="font-bold text-sm tracking-tight text-white block">
                WiFi-Billing
              </span>
              <span className="text-[10px] text-indigo-400 font-medium tracking-wide uppercase block">
                Superadmin Core
              </span>
            </div>
          </div>

          {/* Navigation Links */}
          <nav className="p-4 space-y-1.5">
            <p className="px-3 pb-2 text-[10px] font-semibold text-slate-400 uppercase tracking-wider">
              Administration
            </p>
            {navItems.map((item) => {
              const Icon = item.icon;
              const isActive =
                item.href === "/admin"
                  ? pathname === "/admin"
                  : pathname?.startsWith(item.href);

              return (
                <a
                  key={item.href}
                  href={item.href}
                  className={`flex items-center gap-3 px-3.5 py-2.5 rounded-xl text-xs font-medium transition-all ${
                    isActive
                      ? "bg-indigo-600 text-white shadow-lg shadow-indigo-600/20 font-semibold"
                      : "text-slate-400 hover:text-white hover:bg-slate-800/60"
                  }`}
                >
                  <Icon className={`w-4 h-4 ${isActive ? "text-white" : "text-slate-400"}`} />
                  <span>{item.name}</span>
                </a>
              );
            })}
          </nav>
        </div>

        {/* User Card & Logout */}
        <div className="p-4 border-t border-slate-800/80 bg-slate-950/40">
          <div className="flex items-center gap-3 mb-3 px-1">
            <div className="w-9 h-9 rounded-full bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center text-indigo-400 font-bold text-xs">
              <Shield className="w-4 h-4" />
            </div>
            <div className="overflow-hidden">
              <p className="text-xs font-semibold text-white truncate">
                {user.name}
              </p>
              <p className="text-[11px] text-slate-400 truncate">{user.email}</p>
            </div>
          </div>

          <button
            onClick={logout}
            className="w-full flex items-center justify-center gap-2 py-2 px-3 rounded-xl bg-slate-900 border border-slate-800 hover:bg-red-500/10 hover:border-red-500/25 hover:text-red-400 text-xs font-medium text-slate-300 transition cursor-pointer"
          >
            <LogOut className="w-3.5 h-3.5" />
            <span>Sign Out</span>
          </button>
        </div>
      </aside>

      {/* Main Area */}
      <div className="flex-1 flex flex-col overflow-hidden">
        {/* Top Navbar */}
        <header className="h-16 border-b border-slate-800/80 bg-slate-900/40 backdrop-blur-xl px-8 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse" />
              WireGuard Tunnel Mesh Active
            </span>
            <span className="text-xs text-slate-400">• Hub IP: 10.9.0.1</span>
          </div>

          <div className="flex items-center gap-3">
            <div className="text-right">
              <span className="text-xs font-semibold text-slate-200 block">
                Platform Cluster
              </span>
              <span className="text-[10px] text-slate-400">cloudserv • v1.0.0</span>
            </div>
            <div className="h-8 w-8 rounded-full bg-slate-800 border border-slate-700 flex items-center justify-center text-xs text-indigo-400 font-bold">
              SA
            </div>
          </div>
        </header>

        {/* Content View */}
        <main className="flex-1 overflow-y-auto p-8">{children}</main>
      </div>
    </div>
  );
}
