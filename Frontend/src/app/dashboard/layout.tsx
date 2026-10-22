"use client";

import React, { useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import {
  LayoutDashboard,
  Router as RouterIcon,
  Layers,
  Ticket,
  Radio,
  History,
  LogOut,
  Wifi,
  Shield,
  ArrowRight,
  Menu,
  Settings
} from "lucide-react";

export default function TenantDashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const { user, loading, logout } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    if (!loading && !user) {
      router.replace("/login");
    }
  }, [user, loading, router]);

  if (loading || !user) {
    return (
      <div className="flex h-screen items-center justify-center bg-slate-950 text-slate-400">
        <div className="flex flex-col items-center gap-3">
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-indigo-500 border-t-transparent" />
          <p className="text-sm">Loading Hotspot Control Panel...</p>
        </div>
      </div>
    );
  }

  const navItems = [
    { name: "Dashboard", href: "/dashboard", icon: LayoutDashboard, group: "Overview" },
    { name: "Active users", href: "/dashboard/users", icon: Radio, group: "Users" },
    { name: "Customers", href: "/dashboard/customers", icon: Radio, group: "Users" },
    { name: "Expired users", href: "/dashboard/expired", icon: History, group: "Users" },
    { name: "IP bindings", href: "/dashboard/bindings", icon: Wifi, group: "Users" },
    { name: "Packages", href: "/dashboard/packages", icon: Layers, group: "Finance" },
    { name: "Vouchers", href: "/dashboard/vouchers", icon: Ticket, group: "Finance" },
    { name: "Payments", href: "/dashboard/payments", icon: Ticket, group: "Finance" },
    { name: "MikroTik routers", href: "/dashboard/routers", icon: RouterIcon, group: "Devices" },
    { name: "Accounting sessions", href: "/dashboard/sessions", icon: Radio, group: "Accounting" },
    { name: "Accounting logs", href: "/dashboard/logs", icon: History, group: "Accounting" },
    { name: "Settings", href: "/dashboard/settings", icon: Settings, group: "Settings" },
  ];

  return (
    <div className="operator-dashboard h-dvh overflow-hidden bg-[#111113] text-slate-100 flex">
      {menuOpen && <button aria-label="Close navigation" onClick={() => setMenuOpen(false)} className="fixed inset-0 z-30 bg-black/60 md:hidden" />}
      {/* Sidebar */}
      <aside className={`${menuOpen ? "flex" : "hidden"} fixed inset-y-0 left-0 z-40 w-64 border-r border-[#303034] bg-[#151517] flex-col justify-between shrink-0 md:static md:flex`}>
        <div className="min-h-0 flex-1 overflow-y-auto">
          {/* Brand Header */}
          <div className="h-16 flex items-center gap-3 px-6 border-b border-slate-800/80">
            <div className="p-2 rounded-xl bg-blue-600 text-white">
              <Wifi className="w-5 h-5" />
            </div>
            <div className="overflow-hidden">
              <span className="font-bold text-sm tracking-tight text-white block truncate">
                {user.name || "DELUXE WIFI"}
              </span>
              <span className="text-[10px] text-blue-400 font-semibold tracking-wide uppercase block">
                Hotspot Operator • ID #{user.tenantId}
              </span>
            </div>
          </div>

          {/* Superadmin Switcher Notice (if user is superadmin viewing tenant dashboard) */}
          {user.role === "ROLE_SUPERADMIN" && (
            <div className="mx-4 mt-3 p-2.5 rounded-xl bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-between text-xs text-blue-300">
              <div className="flex items-center gap-1.5 truncate">
                <Shield className="w-3.5 h-3.5 text-blue-400 shrink-0" />
                <span className="text-[11px] truncate">Superadmin Mode</span>
              </div>
              <a
                href="/admin"
                className="flex items-center gap-1 text-[11px] font-semibold text-blue-400 hover:text-white"
              >
                <span>Portal</span>
                <ArrowRight className="w-3 h-3" />
              </a>
            </div>
          )}

          {/* Navigation Links */}
          <nav className="p-4 space-y-1.5 overflow-y-auto">
            <p className="px-3 pb-2 text-[10px] font-semibold text-slate-400 uppercase tracking-wider">
              Workspace
            </p>
            {navItems.map((item, index) => {
              const Icon = item.icon;
              const isActive =
                item.href === "/dashboard"
                  ? pathname === "/dashboard"
                  : pathname?.startsWith(item.href);

              return (
                <React.Fragment key={item.href}>
                {(index === 0 || navItems[index - 1].group !== item.group) && <p className="px-3 pb-2 pt-4 text-[10px] font-semibold uppercase tracking-wider text-slate-500">{item.group}</p>}
                <a
                  key={item.href}
                  onClick={() => setMenuOpen(false)}
                  aria-current={isActive ? "page" : undefined}
                  href={item.href}
                  className={`flex items-center gap-3 px-3.5 py-2.5 rounded-xl text-xs font-medium transition-all ${
                    isActive
                      ? "bg-[#27272b] text-blue-400 font-semibold"
                      : "text-slate-400 hover:text-white hover:bg-slate-800/60"
                  }`}
                >
                  <Icon className={`w-4 h-4 ${isActive ? "text-white" : "text-slate-400"}`} />
                  <span>{item.name}</span>
                </a>
                </React.Fragment>
              );
            })}
          </nav>
        </div>

        {/* User Footer & Logout */}
        <div className="p-4 border-t border-slate-800/80 bg-slate-950/40">
          <div className="flex items-center gap-3 mb-3 px-1">
            <div className="w-9 h-9 rounded-full bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center text-blue-400 font-bold text-xs uppercase">
              {user.name ? user.name.substring(0, 2) : "HO"}
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

      {/* Main Content Area */}
      <div className="min-w-0 flex-1 flex flex-col overflow-hidden">
        {/* Top Navbar */}
        <header className="h-16 shrink-0 border-b border-[#303034] bg-[#1e1e21] px-4 md:px-8 flex items-center justify-between gap-3">
          <div className="flex min-w-0 items-center gap-3">
            <button aria-label="Open navigation" onClick={() => setMenuOpen(true)} className="md:hidden p-2 text-slate-400"><Menu className="h-5 w-5" /></button>
            <span className="truncate text-sm font-semibold text-white">{navItems.find((item) => item.href === "/dashboard" ? pathname === item.href : pathname.startsWith(item.href))?.name || "Dashboard"}</span>
          </div>
          <div className="flex items-center gap-4">
            <a href="/dashboard/vouchers" className="rounded-lg bg-blue-600 px-3 py-2 text-xs font-medium text-white hover:bg-blue-500">Create vouchers</a>
            <div className="h-8 w-8 rounded-full bg-[#303034] flex items-center justify-center text-xs text-slate-200 font-semibold" title={user.email}>
              {user.name.substring(0, 2).toUpperCase()}
            </div>
          </div>
        </header>

        {/* Dynamic Route Content */}
        <main className="flex-1 overflow-y-auto p-4 md:p-8 lg:p-10">{children}</main>
      </div>
    </div>
  );
}
