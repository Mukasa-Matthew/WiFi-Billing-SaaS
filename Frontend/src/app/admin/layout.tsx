"use client";

import Link from "next/link";
import { LinkPending } from "@/components/LoadingUI";
import React, { useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import ThemeToggle from "@/components/ThemeToggle";
import {
  ChartLine,
  Users,
  CellTower as RouterIcon,
  HardDrives,
  SignOut,
  Radio,
  ShieldCheck,
  List,
  Bank,
} from "@phosphor-icons/react";



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

  const ready = !loading && user?.role === "ROLE_SUPERADMIN";

  const navItems = [
    { name: "Platform overview", href: "/admin", icon: ChartLine, group: "Platform" },
    { name: "Tenants", href: "/admin/tenants", icon: Users, group: "Platform" },
    { name: "Finance", href: "/admin/finance", icon: Bank, group: "Platform" },
    { name: "Subscriptions", href: "/admin/subscriptions", icon: ShieldCheck, group: "Platform" },
    { name: "Router fleet", href: "/admin/routers", icon: RouterIcon, group: "Devices" },
    { name: "Network health", href: "/admin/health", icon: HardDrives, group: "Devices" },
  ];

  const activeItem = [...navItems].sort((a, b) => b.href.length - a.href.length).find(
    (item) => item.href === "/admin" ? pathname === "/admin" : pathname?.startsWith(item.href)
  );

  return (
    <div className="h-dvh overflow-hidden bg-[var(--canvas)] text-[var(--fg)] flex">
      {menuOpen && (
        <button aria-label="Close navigation" onClick={() => setMenuOpen(false)}
          className="fixed inset-0 z-30 bg-slate-950/50 md:hidden" />
      )}

      {/* Sidebar */}
      <aside
        className={`${menuOpen ? "flex" : "hidden"} fixed inset-y-0 left-0 z-40 w-64 flex-col justify-between shrink-0 md:static md:flex`}
        style={{ backgroundColor: "var(--sidebar-admin)" }}
      >
        <div className="sidebar-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain">
          <div className="flex h-16 items-center gap-3 border-b border-white/10 px-5">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-white/10 text-white">
              <Radio className="h-4.5 w-4.5" />
            </div>
            <div>
              <span className="block text-sm font-bold tracking-tight text-white">AirPairy</span>
              <span className="block text-[10px] font-semibold uppercase tracking-widest text-[var(--sidebar-fg-dim)]">
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
                    <p className={`px-3 pb-1.5 ${index === 0 ? "" : "pt-3"} text-[10px] font-bold uppercase tracking-widest text-[var(--sidebar-fg-faint)]`}>
                      {item.group}
                    </p>
                  )}
                  <Link
                    onClick={() => setMenuOpen(false)}
                    aria-current={isActive ? "page" : undefined}
                    href={item.href}
                    className={`relative flex items-center gap-3 rounded-lg px-3.5 py-2 text-[13px] font-medium transition ${
                      isActive
                        ? "bg-[var(--sidebar-active)] text-[var(--sidebar-active-fg)] shadow-sm"
                        : "text-slate-200 hover:bg-white/10 hover:text-white"
                    }`}
                  >
                    {isActive && <span className="absolute left-0 top-1/2 h-5 w-1 -translate-y-1/2 rounded-r bg-blue-400" />}
                    <Icon
                      aria-hidden="true"
                      weight="regular"
                      className={`h-5 w-5 shrink-0 ${isActive ? "text-blue-300" : "text-[var(--sidebar-fg-dim)]"}`}
                    />
                    <span className="flex-1">{item.name}</span><LinkPending />
                  </Link>
                </React.Fragment>
              );
            })}
          </nav>
        </div>

        <div className="border-t border-white/10 p-4">
          <div className="mb-3 flex items-center gap-3 px-1">
            <div className="flex h-9 w-9 items-center justify-center rounded-full bg-white/10 text-white">
              <ShieldCheck className="h-4 w-4" />
            </div>
            <div className="min-w-0">
              <p className="truncate text-xs font-semibold text-white">{user?.name}</p>
              <p className="truncate text-[11px] text-[var(--sidebar-fg-dim)]">{user?.email}</p>
            </div>
          </div>
          <div className="mb-3 flex justify-center">
            <ThemeToggle compact />
          </div>
          <button
            onClick={logout}
            className="flex w-full items-center justify-center gap-2 rounded-lg border border-white/15 bg-white/5 py-2 px-3 text-xs font-semibold text-[var(--sidebar-fg-dim)] transition hover:bg-red-500/20 hover:text-white"
          >
            <SignOut className="h-3.5 w-3.5" />
            <span>Sign out</span>
          </button>
        </div>
      </aside>

      {/* Main */}
      <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
        <header className="flex h-16 shrink-0 items-center justify-between gap-3 border-b border-[var(--border)] bg-[var(--surface)] px-4 md:px-8">
          <div className="flex min-w-0 items-center gap-3">
            <button aria-label="Open navigation" onClick={() => setMenuOpen(true)}
              className="rounded-lg p-2 text-[var(--fg-muted)] hover:bg-[var(--fill)] md:hidden">
              <List className="h-5 w-5" />
            </button>
            <span className="truncate text-sm font-semibold text-[var(--fg)]">
              {activeItem?.name || "Platform"}
            </span>
          </div>
          <div className="flex items-center gap-2 sm:gap-3">
            <Link href="/dashboard"
              className="rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] px-3.5 py-2 text-xs font-semibold text-[var(--fg-body)] transition hover:bg-[var(--subtle)]">
              Tenant workspace →
            </Link>
          </div>
        </header>

        <main className="flex-1 overflow-y-auto p-4 sm:p-6 lg:p-8">
          <div className="page-enter mx-auto w-full max-w-[1440px]">{children}</div>
        </main>
      </div>

      {/* Covers the console instead of replacing it: the layout has to keep
          rendering {children} so Next can validate the segments below it for
          instant navigation. Platform data is fetched with the bearer token,
          so a non-superadmin never receives it behind this. */}
      {!ready && (
        <div role="status" aria-live="polite"
          className="fixed inset-0 z-50 flex items-center justify-center bg-[var(--canvas)] text-[var(--fg-muted)]">
          <div className="flex flex-col items-center gap-3">
            <div className="h-8 w-8 animate-spin rounded-full border-2 border-blue-700 border-t-transparent" />
            <p className="text-sm">Verifying platform access…</p>
          </div>
        </div>
      )}
    </div>
  );
}
