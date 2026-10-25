"use client";

import Link from "next/link";
import { LinkPending } from "@/components/LoadingUI";
import React, { useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import ThemeToggle from "@/components/ThemeToggle";
import api from "@/lib/api";
import {
  SquaresFour,
  CellTower as RouterIcon,
  Stack,
  Ticket,
  Radio,
  ClockCounterClockwise,
  SignOut,
  WifiHigh,
  ShieldCheck,
  ArrowRight,
  List,
  GearSix,
  Users,
  ChatCircleText,
  Money,
  Wallet,
  CalendarCheck,
  LinkSimple,
  FileText,
  CaretDown,
} from "@/components/icons";



interface SidebarStats {
  activeUsers?: number;
  totalVouchers?: number;
  activeVouchers?: number;
  totalRouters?: number;
  onlineRouters?: number;
}

export default function TenantDashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const { user, loading, logout } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const [menuOpen, setMenuOpen] = useState(false);
  const [stats, setStats] = useState<SidebarStats | null>(null);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

  useEffect(() => {
    if (!user) return;
    api.get<SidebarStats>("/api/dashboard/stats")
      .then((r) => setStats(r.data))
      .catch(() => {});
  }, [user]);

  useEffect(() => {
    if (!loading) {
      if (!user) router.replace("/login");
      else if (user.mustChangePassword) router.replace("/change-password");
    }
  }, [user, loading, router]);

  const ready = !loading && !!user && !user.mustChangePassword;

  const counts: Record<string, number | undefined> = {
    "Active users": stats?.activeUsers,
    "Vouchers": stats?.activeVouchers,
    "MikroTik routers": stats?.totalRouters,
  };

  const navItems = [
    { name: "Dashboard", href: "/dashboard", icon: SquaresFour, group: "Overview" },
    { name: "Active users", href: "/dashboard/users", icon: Radio, group: "Users" },
    { name: "Customers", href: "/dashboard/customers", icon: Users, group: "Users" },
    { name: "Expired users", href: "/dashboard/expired", icon: ClockCounterClockwise, group: "Users" },
    { name: "IP bindings", href: "/dashboard/bindings", icon: LinkSimple, group: "Users" },
    { name: "Packages", href: "/dashboard/packages", icon: Stack, group: "Finance" },
    { name: "Vouchers", href: "/dashboard/vouchers", icon: Ticket, group: "Finance" },
    { name: "Payments", href: "/dashboard/payments", icon: Money, group: "Finance" },
    { name: "Wallet", href: "/dashboard/wallet", icon: Wallet, group: "Finance" },
    { name: "Subscription", href: "/dashboard/subscription", icon: CalendarCheck, group: "Finance" },
    { name: "MikroTik routers", href: "/dashboard/routers", icon: RouterIcon, group: "Devices" },
    { name: "Sessions", href: "/dashboard/sessions", icon: Radio, group: "Accounting" },
    { name: "Feedback", href: "/dashboard/feedback", icon: ChatCircleText, group: "Users" },
    { name: "Accounting logs", href: "/dashboard/logs", icon: FileText, group: "Accounting" },
    { name: "Settings", href: "/dashboard/settings", icon: GearSix, group: "Settings" },
  ];

  const activeItem = [...navItems, { name: "Add router", href: "/dashboard/routers/new", icon: WifiHigh, group: "Devices" }].sort((a, b) => b.href.length - a.href.length).find(
    (item) => item.href === "/dashboard" ? pathname === item.href : pathname?.startsWith(item.href)
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
        style={{ backgroundColor: "var(--sidebar)" }}
      >
        <div className="sidebar-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain">
          {/* Brand */}
          <div className="flex h-16 items-center gap-3 border-b border-white/10 px-5">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-white/10 text-white">
              <WifiHigh className="h-4.5 w-4.5" />
            </div>
            <div className="min-w-0">
              <span className="block truncate text-sm font-bold tracking-tight text-white">
                {user?.name || "Hotspot Operator"}
              </span>
              <span className="block text-[10px] font-semibold uppercase tracking-widest text-[var(--sidebar-fg-dim)]">
                AirPairy
              </span>
            </div>
          </div>

          {/* Superadmin viewing banner */}
          {user?.role === "ROLE_SUPERADMIN" && (
            <div className="mx-4 mt-3 flex items-center justify-between rounded-lg border border-amber-400/30 bg-amber-400/10 p-2.5 text-[var(--sidebar-warn)]">
              <div className="flex min-w-0 items-center gap-1.5">
                <ShieldCheck className="h-3.5 w-3.5 shrink-0" />
                <span className="truncate text-[11px] font-medium">Superadmin view</span>
              </div>
              <Link href="/admin" className="flex items-center gap-1 text-[11px] font-bold text-white hover:text-[var(--sidebar-warn)]">
                Platform <ArrowRight className="h-3 w-3" />
              </Link>
            </div>
          )}

          {/* Nav */}
          <nav className="space-y-0.5 p-4">
            {navItems.map((item, index) => {
              const Icon = item.icon;
              const newGroup = index === 0 || navItems[index - 1].group !== item.group;
              const isGroupCollapsed = collapsed[item.group];
              const isActive =
                item.href === "/dashboard"
                  ? pathname === "/dashboard"
                  : item.href === "/dashboard/routers"
                    ? pathname === "/dashboard/routers"
                    : pathname?.startsWith(item.href);
              const count = counts[item.name];
              return (
                <React.Fragment key={item.href}>
                  {newGroup && (
                    <button
                      type="button"
                      onClick={() => setCollapsed((c) => ({ ...c, [item.group]: !c[item.group] }))}
                      aria-expanded={!isGroupCollapsed}
                      className={`flex w-full items-center justify-between px-3 pb-1.5 ${index === 0 ? "" : "pt-3"} text-[10px] font-bold uppercase tracking-widest text-[var(--sidebar-fg-faint)]/80 hover:text-[var(--sidebar-fg-dim)]`}
                    >
                      <span>{item.group}</span>
                      <CaretDown className={`h-3 w-3 transition-transform ${isGroupCollapsed ? "-rotate-90" : ""}`} />
                    </button>
                  )}
                  {!isGroupCollapsed && (
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

                        className={`h-5 w-5 shrink-0 ${isActive ? "text-blue-300" : "text-[var(--sidebar-fg-dim)]"}`}
                      />
                      <span className="flex-1">{item.name}</span>
                      {count !== undefined && count > 0 && (
                        <span className={`rounded-full px-2 py-0.5 text-[10px] font-bold ${isActive ? "bg-[var(--sidebar-active-fg)]/12 text-[var(--sidebar-active-fg)]" : "bg-white/15 text-white"}`}>
                          {count > 999 ? `${(count / 1000).toFixed(1)}k` : count}
                        </span>
                      )}
                      <LinkPending />
                    </Link>
                  )}
                </React.Fragment>
              );
            })}
          </nav>
        </div>

        {/* Account footer */}
        <div className="border-t border-white/10 p-4">
          <div className="mb-3 flex items-center gap-3 px-1">
            <div className="flex h-9 w-9 items-center justify-center rounded-full bg-white/10 text-xs font-bold uppercase text-white">
              {user?.name ? user.name.substring(0, 2) : "HO"}
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
          <p className="mt-3 text-center text-[10px] text-[var(--sidebar-fg-dim)]/70">A product by Thewton</p>
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
            <div className="min-w-0">
              <span className="block truncate text-sm font-semibold text-[var(--fg)]">
                {activeItem?.name || "Dashboard"}
              </span>
              {pathname !== "/dashboard" && (
                <span className="block text-[11px] text-[var(--fg-muted)]">
                  Dashboard / {activeItem?.name || ""}
                </span>
              )}
            </div>
          </div>
          <div className="flex items-center gap-2 sm:gap-3">
            <div className="flex h-8 w-8 items-center justify-center rounded-full bg-[#122B49] text-xs font-bold text-white" title={user?.email}>
              {(user?.name ?? "").substring(0, 2).toUpperCase()}
            </div>
          </div>
        </header>

        <main className="flex-1 overflow-y-auto p-4 sm:p-6 lg:p-8">
          <div className="page-enter mx-auto w-full max-w-[1440px]">{children}</div>
        </main>
      </div>

      {/* Covers the workspace instead of replacing it: the layout has to keep
          rendering {children} so Next can validate the segments below it for
          instant navigation. No tenant data is server-rendered — every panel
          fetches with the bearer token — so nothing is exposed behind this. */}
      {!ready && (
        <div role="status" aria-live="polite"
          className="fixed inset-0 z-50 flex items-center justify-center bg-[var(--canvas)] text-[var(--fg-muted)]">
          <div className="flex flex-col items-center gap-3">
            <div className="h-8 w-8 animate-spin rounded-full border-2 border-blue-700 border-t-transparent" />
            <p className="text-sm">Loading workspace…</p>
          </div>
        </div>
      )}
    </div>
  );
}
