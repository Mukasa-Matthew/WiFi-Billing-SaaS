"use client";
import { prefetchTenantEssential } from "@/lib/dashboardPrefetch";

import Link from "next/link";
import React, { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import AppShell, { type NavItem } from "@/components/AppShell";
import SubscriptionRestrictedBanner from "@/components/SubscriptionRestrictedBanner";
import api from "@/lib/api";
import { routerState } from "@/lib/routerState";
import { useNow } from "@/components/BillingUI";
import {
  SquaresFour,
  CellTower as RouterIcon,
  Stack,
  Ticket,
  Radio,
  ClockCounterClockwise,
  ShieldCheck,
  ArrowRight,
  GearSix,
  Users,
  ChatCircleText,
  Money,
  Wallet,
  CalendarCheck,
  LinkSimple,
  FileText,
  Activity,
} from "@/components/icons";

interface SidebarStats {
  activeUsers?: number;
  totalVouchers?: number;
  activeVouchers?: number;
  totalRouters?: number;
  onlineRouters?: number;
}

export default function TenantDashboardLayout({ children }: { children: React.ReactNode }) {
  const { user, loading, logout } = useAuth();
  const router = useRouter();
  const [stats, setStats] = useState<SidebarStats | null>(null);
  const now = useNow(60000);
  const [routers, setRouters] = useState<{ status: string; lastSeen: string | null }[] | null>(null);

  useEffect(() => {
    if (!user) return;
    if (user.tenantId) prefetchTenantEssential(user.tenantId);
    api.get<SidebarStats>("/api/dashboard/stats")
      .then((r) => setStats(r.data))
      .catch(() => {});
    // Router badge uses the same stale rule as every router view (src/lib/routerState).
    api.get<{ status: string; lastSeen: string | null }[]>("/api/routers")
      .then((r) => setRouters(r.data))
      .catch(() => {});
  }, [user]);

  useEffect(() => {
    if (!loading) {
      if (!user) router.replace("/login");
      else if (user.mustChangePassword) router.replace("/change-password");
    }
  }, [user, loading, router]);

  const ready = !loading && !!user && !user.mustChangePassword;
  const isSuperadmin = user?.role === "ROLE_SUPERADMIN";
  const offlineRouters = routers
    ? routers.filter((r) => { const st = routerState(r, now).state; return st === "offline" || st === "stale"; }).length
    : 0;

  const nav: NavItem[] = [
    { name: "Dashboard", href: "/dashboard", icon: SquaresFour, group: "" },
    { name: "Active users", href: "/dashboard/users", icon: Radio, group: "Customers", badge: { value: stats?.activeUsers || 0, tone: "info", label: "online" } },
    { name: "Customers", href: "/dashboard/customers", icon: Users, group: "Customers" },
    { name: "Expired users", href: "/dashboard/expired", icon: ClockCounterClockwise, group: "Customers" },
    { name: "IP bindings", href: "/dashboard/bindings", icon: LinkSimple, group: "Customers" },
    { name: "Feedback", href: "/dashboard/feedback", icon: ChatCircleText, group: "Customers" },
    { name: "Packages", href: "/dashboard/packages", icon: Stack, group: "Sales" },
    { name: "Vouchers", href: "/dashboard/vouchers", icon: Ticket, group: "Sales", badge: { value: stats?.activeVouchers || 0, tone: "neutral", label: "active" } },
    { name: "Payments", href: "/dashboard/payments", icon: Money, group: "Sales" },
    { name: "Wallet", href: "/dashboard/wallet", icon: Wallet, group: "Sales" },
    { name: "MikroTik routers", href: "/dashboard/routers", icon: RouterIcon, group: "Network", badge: { value: offlineRouters, tone: "danger", label: "offline" } },
    { name: "Sessions", href: "/dashboard/sessions", icon: Activity, group: "Network" },
    { name: "Accounting logs", href: "/dashboard/logs", icon: FileText, group: "Network" },
    { name: "Subscription", href: "/dashboard/subscription", icon: CalendarCheck, group: "Account" },
    { name: "Settings", href: "/dashboard/settings", icon: GearSix, group: "Account" },
  ];

  return (
    <AppShell
      brand={{ title: user?.name || "Hotspot business", subtitle: "AirPairy workspace" }}
      nav={nav}
      extraRoutes={[
        { name: "Add router", href: "/dashboard/routers/new", group: "Network" },
        { name: "New package", href: "/dashboard/packages/new", group: "Sales" },
        { name: "Generate vouchers", href: "/dashboard/vouchers/new", group: "Sales" },
        { name: "New IP binding", href: "/dashboard/bindings/new", group: "Customers" },
      ]}
      rootHref="/dashboard"
      collapsibleGroups
      sidebarNotice={isSuperadmin && (
        <div className="flex items-center justify-between gap-2 rounded-lg border border-amber-300/25 bg-amber-300/10 px-2.5 py-2 text-[var(--sidebar-warn)]">
          <span className="flex min-w-0 items-center gap-1.5 text-[11.5px] font-medium">
            <ShieldCheck className="h-3.5 w-3.5 shrink-0" aria-hidden />
            <span className="truncate">Superadmin view</span>
          </span>
          <Link href="/admin" className="flex shrink-0 items-center gap-1 text-[11.5px] font-semibold text-white hover:text-[var(--sidebar-warn)]">
            Platform <ArrowRight className="h-3 w-3" aria-hidden />
          </Link>
        </div>
      )}
      user={user}
      roleLabel={isSuperadmin ? "Superadmin" : "Business admin"}
      settingsHref="/dashboard/settings"
      onLogout={logout}
      ready={ready}
      readyLabel="Loading workspace…"
    >
      <SubscriptionRestrictedBanner />
      {children}
    </AppShell>
  );
}
