"use client";
import { prefetchTenantEssential } from "@/lib/dashboardPrefetch";

import Link from "next/link";
import React, { Suspense, useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import AppShell, { type NavItem } from "@/components/AppShell";
import SubscriptionRestrictedBanner from "@/components/SubscriptionRestrictedBanner";
import { routerState } from "@/lib/routerState";
import { useApi, useNow, useResource } from "@/components/BillingUI";
import { ArrowRight, ShieldCheck } from "@/components/icons";
import {
  BindingIcon, CustomersIcon, ExpiredIcon, FeedbackIcon, LiveUsersIcon, LogsIcon, OverviewIcon, PackagesIcon, PaymentsIcon,
  RouterIcon, SessionsIcon, SubscriptionIcon, TuneIcon, VoucherIcon, WalletIcon,
} from "@/components/BrandIcons";

interface SidebarStats {
  activeUsers?: number;
  totalVouchers?: number;
  activeVouchers?: number;
  totalRouters?: number;
  onlineRouters?: number;
  totalCustomers?: number;
}

export default function TenantDashboardLayout({ children }: { children: React.ReactNode }) {
  return <Suspense><TenantDashboardLayoutInner>{children}</TenantDashboardLayoutInner></Suspense>;
}

function TenantDashboardLayoutInner({ children }: { children: React.ReactNode }) {
  const { user, loading, logout } = useAuth();
  const router = useRouter();
  const now = useNow(60000);
  // Badges read the same cached queries as the dashboard page (the counts are
  // range-independent, and 7d is the backend default), so neither the stats
  // aggregate nor the router list is fetched a second time.
  const { data: stats } = useApi<SidebarStats>("/api/dashboard/stats?range=7d");
  // Router badge uses the same stale rule as every router view (src/lib/routerState).
  const { data: routers } = useResource<{ status: string; lastSeen: string | null }>("/api/routers");

  useEffect(() => {
    if (user?.tenantId) prefetchTenantEssential(user.tenantId);
  }, [user]);

  useEffect(() => {
    if (!loading) {
      if (!user) router.replace("/login");
      else if (user.mustChangePassword) router.replace("/change-password");
    }
  }, [user, loading, router]);

  const ready = !loading && !!user && !user.mustChangePassword;
  const isSuperadmin = user?.role === "ROLE_SUPERADMIN";
  const offlineRouters = routers.filter((r) => { const st = routerState(r, now).state; return st === "offline" || st === "stale"; }).length;

  const nav: NavItem[] = [
    { name: "Dashboard", href: "/dashboard", icon: OverviewIcon, group: "" },
    { name: "Active users", href: "/dashboard/users", icon: LiveUsersIcon, group: "Customers", badge: { value: stats?.activeUsers || 0, tone: "info", label: "online" } },
    { name: "Customers", href: "/dashboard/customers", icon: CustomersIcon, group: "Customers", badge: { value: stats?.totalCustomers || 0, tone: "neutral", label: "customers" } },
    { name: "Expired users", href: "/dashboard/expired", icon: ExpiredIcon, group: "Customers" },
    { name: "IP bindings", href: "/dashboard/bindings", icon: BindingIcon, group: "Customers" },
    { name: "Feedback", href: "/dashboard/feedback", icon: FeedbackIcon, group: "Customers" },
    { name: "Packages", href: "/dashboard/packages", icon: PackagesIcon, group: "Sales" },
    { name: "Vouchers", href: "/dashboard/vouchers", icon: VoucherIcon, group: "Sales", badge: { value: stats?.activeVouchers || 0, tone: "neutral", label: "active" } },
    { name: "Payments", href: "/dashboard/payments", icon: PaymentsIcon, group: "Sales" },
    { name: "Wallet", href: "/dashboard/wallet", icon: WalletIcon, group: "Sales" },
    { name: "MikroTik routers", href: "/dashboard/routers", icon: RouterIcon, group: "Network", badge: { value: offlineRouters, tone: "danger", label: "offline" } },
    { name: "Sessions", href: "/dashboard/sessions", icon: SessionsIcon, group: "Network" },
    { name: "Accounting logs", href: "/dashboard/logs", icon: LogsIcon, group: "Network" },
    { name: "Subscription", href: "/dashboard/subscription", icon: SubscriptionIcon, group: "Account" },
    { name: "Settings", href: "/dashboard/settings", icon: TuneIcon, group: "Account" },
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
        { name: "New customer", href: "/dashboard/customers/new", group: "Customers" },
        { name: "Router detail", href: "/dashboard/routers/", group: "Network" },
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
