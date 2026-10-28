"use client";
import { prefetchSuperAdminEssential } from "@/lib/dashboardPrefetch";

import Link from "next/link";
import React, { Suspense, useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import AppShell, { type NavItem } from "@/components/AppShell";
import { useApi } from "@/components/BillingUI";
import {
  CellTower as RouterIcon,
  HardDrives,
  Radio,
  Bank,
  Bell,
  Building2,
  CreditCard,
  LayoutDashboard,
  GearSix,
  ArrowUpRight,
} from "@/components/icons";

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return (
    <Suspense>
      <AdminLayoutInner>{children}</AdminLayoutInner>
    </Suspense>
  );
}

/**
 * Open NOC incidents, refreshed once a minute while the tab is visible.
 * Shares the cached overview query with the prefetch and the network page.
 */
function useOpenIncidents(enabled: boolean) {
  const { data } = useApi<{ openIncidents: number }>(enabled ? "/api/admin/noc/overview" : null, 60000);
  return data ? data.openIncidents ?? 0 : null;
}

function AdminLayoutInner({ children }: { children: React.ReactNode }) {
  const { user, loading, logout } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!loading) {
      if (!user) router.replace("/login");
      else if (user.mustChangePassword) router.replace("/change-password");
      else if (user.role !== "ROLE_SUPERADMIN") router.replace("/dashboard");
    }
  }, [user, loading, router]);

  const ready = !loading && user?.role === "ROLE_SUPERADMIN" && !user.mustChangePassword;

  useEffect(() => {
    if (ready) {
      prefetchSuperAdminEssential();
    }
  }, [ready]);
  const incidents = useOpenIncidents(ready);

  const nav: NavItem[] = [
    { name: "Command center", href: "/admin", icon: LayoutDashboard, group: "" },
    { name: "Tenants", href: "/admin/tenants", icon: Building2, group: "Businesses" },
    { name: "Subscriptions", href: "/admin/subscriptions", icon: CreditCard, group: "Businesses" },
    { name: "Finance", href: "/admin/finance", icon: Bank, group: "Businesses" },
    { name: "Network operations", href: "/admin/network", icon: Radio, group: "Network", badge: { value: incidents || 0, tone: "danger", label: "open incidents" } },
    { name: "Router fleet", href: "/admin/routers", icon: RouterIcon, group: "Network", also: ["/admin/network/routers"] },
    { name: "System health", href: "/admin/health", icon: HardDrives, group: "Network" },
    { name: "Settings & security", href: "/admin/settings", icon: GearSix, group: "Platform" },
  ];

  return (
    <AppShell
      brand={{ title: "AirPairy", subtitle: "Platform console" }}
      nav={nav}
      extraRoutes={[{ name: "Router detail", href: "/admin/network/routers", group: "Network" }]}
      rootHref="/admin"
      headerActions={<>
        <Link
          href="/admin/network"
          aria-label={incidents ? `${incidents} open incidents` : "No open incidents"}
          title={incidents ? `${incidents} open incident${incidents === 1 ? "" : "s"}` : "No open incidents"}
          className="relative rounded-lg p-2 text-[var(--fg-muted)] transition-colors hover:bg-[var(--fill)] hover:text-[var(--fg)]"
        >
          <Bell className="h-[18px] w-[18px]" aria-hidden />
          {!!incidents && <span className="num absolute right-0.5 top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-[var(--status-bad)] px-1 text-[10px] font-semibold leading-none text-white ring-2 ring-[var(--header)]">{incidents > 99 ? "99+" : incidents}</span>}
        </Link>
        <Link href="/dashboard"
          className="hidden items-center gap-1.5 rounded-lg border border-[var(--border)] px-3 py-1.5 text-[12.5px] font-medium text-[var(--fg-body)] transition-colors hover:bg-[var(--fill)] sm:inline-flex">
          Tenant workspace <ArrowUpRight className="h-3.5 w-3.5 text-[var(--fg-muted)]" aria-hidden />
        </Link>
      </>}
      user={user}
      roleLabel="Superadmin"
      settingsHref="/admin/settings"
      onLogout={logout}
      ready={ready}
      readyLabel="Verifying platform access…"
    >
      {children}
    </AppShell>
  );
}
