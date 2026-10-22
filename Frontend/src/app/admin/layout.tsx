"use client";

import React, { useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import {
  Activity,
  Users,
  Router as RouterIcon,
  Server,
  LogOut,
  Radio,
  ShieldCheck,
  Menu,
} from "lucide-react";

const NAVY = "#0B1F36";

export default function AdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const { user, loading, logout } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const [menuOpen, setMenuOpen] = useState(false);

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
      <div className="flex h-screen items-center justify-center bg-[#F5F7FA] text-slate-500">
        <div className="flex flex-col items-center gap-3">
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-blue-700 border-t-transparent" />
          <p className="text-sm">Verifying platform access…</p>
        </div>
      </div>
    );
  }

  const navItems = [
    { name: "Platform overview", href: "/admin", icon: Activity, group: "Platform" },
    { name: "Tenants", href: "/admin/tenants", icon: Users, group: "Platform" },
    { name: "Router fleet", href: "/admin/routers", icon: RouterIcon, group: "Devices" },
    { name: "Network health", href: "/admin/health", icon: Server, group: "Devices" },
  ];

  const activeItem = navItems.find(
    (item) => item.href === "/admin" ? pathname === "/admin" : pathname?.startsWith(item.href)
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
          <div className="flex h-16 items-center gap-3 border-b border-white/10 px-5">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-white/10 text-slate-900">
              <Radio className="h-4.5 w-4.5" />
            </div>
            <div>
              <span className="block text-sm font-bold tracking-tight text-slate-900">WiFi-Billing</span>
              <span className="block text-[10px] font-semibold uppercase tracking-widest text-blue-200/70">
                Platform admin
              </span>
            </div>
          </div>

          <nav className="space-y-0.5 p-4">
            {navItems.map((item, index) => {
              const Icon = item.icon;
              const isActive =
                item.href === "/admin" ? pathname === "/admin" : pathname?.startsWith(item.href);
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

        <div className="border-t border-white/10 p-4">
          <div className="mb-3 flex items-center gap-3 px-1">
            <div className="flex h-9 w-9 items-center justify-center rounded-full bg-white/10 text-slate-900">
              <ShieldCheck className="h-4 w-4" />
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
            <span className="truncate text-sm font-semibold text-slate-900">
              {activeItem?.name || "Platform"}
            </span>
          </div>
          <div className="flex items-center gap-3">
            <a href="/dashboard"
              className="rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-xs font-semibold text-slate-700 transition hover:bg-slate-50">
              Tenant workspace →
            </a>
          </div>
        </header>

        <main className="flex-1 overflow-y-auto p-4 md:p-8 lg:p-10">
          <div className="mx-auto max-w-6xl">{children}</div>
        </main>
      </div>
    </div>
  );
}
