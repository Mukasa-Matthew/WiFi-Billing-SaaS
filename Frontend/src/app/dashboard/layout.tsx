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
  ShieldCheck,
  ArrowRight,
  Menu,
  Settings,
  Users,
  Banknote,
  Link2,
  FileText,
} from "lucide-react";

const NAVY = "#122B49";

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
      <div className="flex h-screen items-center justify-center bg-[#F5F7FA] text-slate-500">
        <div className="flex flex-col items-center gap-3">
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-blue-700 border-t-transparent" />
          <p className="text-sm">Loading workspace…</p>
        </div>
      </div>
    );
  }

  const navItems = [
    { name: "Dashboard", href: "/dashboard", icon: LayoutDashboard, group: "Overview" },
    { name: "Active users", href: "/dashboard/users", icon: Radio, group: "Users" },
    { name: "Customers", href: "/dashboard/customers", icon: Users, group: "Users" },
    { name: "Expired users", href: "/dashboard/expired", icon: History, group: "Users" },
    { name: "IP bindings", href: "/dashboard/bindings", icon: Link2, group: "Users" },
    { name: "Packages", href: "/dashboard/packages", icon: Layers, group: "Finance" },
    { name: "Vouchers", href: "/dashboard/vouchers", icon: Ticket, group: "Finance" },
    { name: "Payments", href: "/dashboard/payments", icon: Banknote, group: "Finance" },
    { name: "MikroTik routers", href: "/dashboard/routers", icon: RouterIcon, group: "Devices" },
    { name: "Add router", href: "/dashboard/routers/new", icon: Wifi, group: "Devices" },
    { name: "Sessions", href: "/dashboard/sessions", icon: Radio, group: "Accounting" },
    { name: "Accounting logs", href: "/dashboard/logs", icon: FileText, group: "Accounting" },
    { name: "Settings", href: "/dashboard/settings", icon: Settings, group: "Settings" },
  ];

  const activeItem = navItems.find(
    (item) => item.href === "/dashboard" ? pathname === item.href : pathname?.startsWith(item.href)
  );

  return (
    <div className="h-dvh overflow-hidden bg-[#F5F7FA] text-slate-900 flex">
      {menuOpen && (
        <button aria-label="Close navigation" onClick={() => setMenuOpen(false)}
          className="fixed inset-0 z-30 bg-white/50 md:hidden" />
      )}

      {/* Sidebar */}
      <aside
        className={`${menuOpen ? "flex" : "hidden"} fixed inset-y-0 left-0 z-40 w-64 flex-col justify-between shrink-0 md:static md:flex`}
        style={{ backgroundColor: NAVY }}
      >
        <div className="min-h-0 flex-1 overflow-y-auto">
          {/* Brand */}
          <div className="flex h-16 items-center gap-3 border-b border-white/10 px-5">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-white/10 text-slate-900">
              <Wifi className="h-4.5 w-4.5" />
            </div>
            <div className="min-w-0">
              <span className="block truncate text-sm font-bold tracking-tight text-slate-900">
                {user.name || "Hotspot Operator"}
              </span>
              <span className="block text-[10px] font-semibold uppercase tracking-widest text-blue-200/70">
                Tenant #{user.tenantId}
              </span>
            </div>
          </div>

          {/* Superadmin viewing banner */}
          {user.role === "ROLE_SUPERADMIN" && (
            <div className="mx-4 mt-3 flex items-center justify-between rounded-lg border border-amber-400/30 bg-amber-400/10 p-2.5 text-amber-200">
              <div className="flex min-w-0 items-center gap-1.5">
                <ShieldCheck className="h-3.5 w-3.5 shrink-0" />
                <span className="truncate text-[11px] font-medium">Superadmin view</span>
              </div>
              <a href="/admin" className="flex items-center gap-1 text-[11px] font-bold text-slate-900 hover:text-amber-100">
                Platform <ArrowRight className="h-3 w-3" />
              </a>
            </div>
          )}

          {/* Nav */}
          <nav className="space-y-0.5 p-4">
            {navItems.map((item, index) => {
              const Icon = item.icon;
              const isActive =
                item.href === "/dashboard"
                  ? pathname === "/dashboard"
                  : item.href === "/dashboard/routers"
                    ? pathname === "/dashboard/routers"
                    : pathname?.startsWith(item.href);
              return (
                <React.Fragment key={item.href}>
                  {(index === 0 || navItems[index - 1].group !== item.group) && (
                    <p className={`px-3 pb-1.5 ${index === 0 ? "" : "pt-4"} text-[10px] font-bold uppercase tracking-widest text-blue-200/50`}>
                      {item.group}
                    </p>
                  )}
                  <a
                    onClick={() => setMenuOpen(false)}
                    aria-current={isActive ? "page" : undefined}
                    href={item.href}
                    className={`relative flex items-center gap-3 rounded-lg px-3.5 py-2.5 text-[13px] font-medium transition ${
                      isActive
                        ? "bg-white/10 text-slate-900"
                        : "text-blue-100/70 hover:bg-white/5 hover:text-slate-900"
                    }`}
                  >
                    {isActive && <span className="absolute left-0 top-1/2 h-5 w-1 -translate-y-1/2 rounded-r bg-blue-400" />}
                    <Icon className="h-4 w-4 shrink-0" />
                    <span>{item.name}</span>
                  </a>
                </React.Fragment>
              );
            })}
          </nav>
        </div>

        {/* Account footer */}
        <div className="border-t border-white/10 p-4">
          <div className="mb-3 flex items-center gap-3 px-1">
            <div className="flex h-9 w-9 items-center justify-center rounded-full bg-white/10 text-xs font-bold uppercase text-slate-900">
              {user.name ? user.name.substring(0, 2) : "HO"}
            </div>
            <div className="min-w-0">
              <p className="truncate text-xs font-semibold text-slate-900">{user.name}</p>
              <p className="truncate text-[11px] text-blue-200/60">{user.email}</p>
            </div>
          </div>
          <button
            onClick={logout}
            className="flex w-full items-center justify-center gap-2 rounded-lg border border-white/15 bg-white/5 py-2 px-3 text-xs font-semibold text-blue-100 transition hover:bg-red-500/20 hover:text-white"
          >
            <LogOut className="h-3.5 w-3.5" />
            <span>Sign out</span>
          </button>
        </div>
      </aside>

      {/* Main */}
      <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
        <header className="flex h-16 shrink-0 items-center justify-between gap-3 border-b border-slate-200 bg-white px-4 md:px-8">
          <div className="flex min-w-0 items-center gap-3">
            <button aria-label="Open navigation" onClick={() => setMenuOpen(true)}
              className="rounded-lg p-2 text-slate-500 hover:bg-slate-100 md:hidden">
              <Menu className="h-5 w-5" />
            </button>
            <div className="min-w-0">
              <span className="block truncate text-sm font-semibold text-slate-900">
                {activeItem?.name || "Dashboard"}
              </span>
              {pathname !== "/dashboard" && (
                <span className="block text-[11px] text-slate-500">
                  Dashboard / {activeItem?.name || ""}
                </span>
              )}
            </div>
          </div>
          <div className="flex items-center gap-3">
            <a href="/dashboard/vouchers/new"
              className="rounded-lg bg-blue-700 px-3.5 py-2 text-xs font-semibold text-white transition hover:bg-blue-600">
              Create vouchers
            </a>
            <div className="flex h-8 w-8 items-center justify-center rounded-full bg-[#122B49] text-xs font-bold text-slate-900" title={user.email}>
              {user.name.substring(0, 2).toUpperCase()}
            </div>
          </div>
        </header>

        <main className="flex-1 overflow-y-auto p-4 md:p-8 lg:p-10">
          <div className="mx-auto max-w-6xl">{children}</div>
        </main>
      </div>
    </div>
  );
}
