"use client";

import { getQueryClient } from "@/lib/queryClient";
import api from "@/lib/api";

/**
 * Intelligent Initial & Intent-based Data Prefetching:
 *
 * Requirements:
 * - When Business Admin logs in / lands on dashboard:
 *   preload essential dashboard items:
 *   - /api/dashboard/stats?range=7d
 *   - /api/routers
 *   - /api/packages
 *   - /api/subscription
 *   - /api/wallet
 *   - /api/finance/payments?limit=6
 *
 * - For Super Admin:
 *   - /api/admin/noc/overview
 *   - /api/admin/tenants
 *   - /api/admin/subscriptions/overview
 *   - /api/admin/finance/overview
 *   - /api/admin/finance/platform
 *   - /api/admin/noc/routers
 *
 * - Idle / Navigation intent prefetcher for sub-pages:
 *   prefetchSubPage(path)
 */

export function prefetchTenantEssential(tenantId: number) {
  if (typeof window === "undefined") return;
  const qc = getQueryClient();
  const scope = `tenant-${tenantId}`;

  const fetcher = (url: string) => async () => {
    const res = await api.get(url);
    return res.data;
  };

  // Prefetch with staleTime so queries consider it fresh. Stats default to 7d,
  // so the range-less URL is the same (expensive) aggregate; fetch it once.
  qc.prefetchQuery({ queryKey: [scope, "/api/dashboard/stats?range=7d"], queryFn: fetcher("/api/dashboard/stats?range=7d") });
  qc.prefetchQuery({ queryKey: [scope, "/api/routers"], queryFn: fetcher("/api/routers") });
  qc.prefetchQuery({ queryKey: [scope, "/api/packages"], queryFn: fetcher("/api/packages") });
  qc.prefetchQuery({ queryKey: [scope, "/api/subscription"], queryFn: fetcher("/api/subscription") });
  qc.prefetchQuery({ queryKey: [scope, "/api/wallet"], queryFn: fetcher("/api/wallet") });
  qc.prefetchQuery({ queryKey: [scope, "/api/finance/payments?limit=6"], queryFn: fetcher("/api/finance/payments?limit=6") });
}

export function prefetchSuperAdminEssential() {
  if (typeof window === "undefined") return;
  const qc = getQueryClient();
  const scope = "superadmin";

  const fetcher = (url: string) => async () => {
    const res = await api.get(url);
    return res.data;
  };

  qc.prefetchQuery({ queryKey: [scope, "/api/admin/noc/overview"], queryFn: fetcher("/api/admin/noc/overview") });
  qc.prefetchQuery({ queryKey: [scope, "/api/admin/tenants"], queryFn: fetcher("/api/admin/tenants") });
  qc.prefetchQuery({ queryKey: [scope, "/api/admin/subscriptions/overview"], queryFn: fetcher("/api/admin/subscriptions/overview") });
  qc.prefetchQuery({ queryKey: [scope, "/api/admin/finance/overview"], queryFn: fetcher("/api/admin/finance/overview") });
  qc.prefetchQuery({ queryKey: [scope, "/api/admin/finance/platform"], queryFn: fetcher("/api/admin/finance/platform") });
  qc.prefetchQuery({ queryKey: [scope, "/api/admin/noc/routers"], queryFn: fetcher("/api/admin/noc/routers") });
}

export function prefetchRouteData(href: string, role?: string, tenantId?: number) {
  if (typeof window === "undefined") return;
  const qc = getQueryClient();
  const scope = role === "ROLE_SUPERADMIN" ? "superadmin" : `tenant-${tenantId || 0}`;

  const fetcher = (url: string) => async () => {
    const res = await api.get(url);
    return res.data;
  };

  switch (href) {
    case "/dashboard/vouchers":
      qc.prefetchQuery({ queryKey: [scope, "/api/vouchers"], queryFn: fetcher("/api/vouchers") });
      qc.prefetchQuery({ queryKey: [scope, "/api/customers"], queryFn: fetcher("/api/customers") });
      qc.prefetchQuery({ queryKey: [scope, "/api/routers"], queryFn: fetcher("/api/routers") });
      break;
    case "/dashboard/customers":
      qc.prefetchQuery({ queryKey: [scope, "/api/customers"], queryFn: fetcher("/api/customers") });
      break;
    case "/dashboard/packages":
      qc.prefetchQuery({ queryKey: [scope, "/api/packages"], queryFn: fetcher("/api/packages") });
      break;
    case "/dashboard/routers":
      qc.prefetchQuery({ queryKey: [scope, "/api/routers"], queryFn: fetcher("/api/routers") });
      break;
    case "/dashboard/payments":
      qc.prefetchQuery({ queryKey: [scope, "/api/finance/payments"], queryFn: fetcher("/api/finance/payments") });
      break;
    case "/dashboard/sessions":
      qc.prefetchQuery({ queryKey: [scope, "/api/sessions/active"], queryFn: fetcher("/api/sessions/active") });
      break;
    case "/dashboard/bindings":
      qc.prefetchQuery({ queryKey: [scope, "/api/bindings"], queryFn: fetcher("/api/bindings") });
      break;
    case "/dashboard/logs":
      qc.prefetchQuery({ queryKey: [scope, "/api/sessions/history"], queryFn: fetcher("/api/sessions/history") });
      break;
    case "/dashboard/subscription":
      qc.prefetchQuery({ queryKey: [scope, "/api/subscription"], queryFn: fetcher("/api/subscription") });
      break;
    case "/admin/tenants":
      qc.prefetchQuery({ queryKey: [scope, "/api/admin/tenants"], queryFn: fetcher("/api/admin/tenants") });
      qc.prefetchQuery({ queryKey: [scope, "/api/admin/noc/routers"], queryFn: fetcher("/api/admin/noc/routers") });
      qc.prefetchQuery({ queryKey: [scope, "/api/admin/subscriptions"], queryFn: fetcher("/api/admin/subscriptions") });
      break;
    case "/admin/subscriptions":
      qc.prefetchQuery({ queryKey: [scope, "/api/admin/subscriptions/overview"], queryFn: fetcher("/api/admin/subscriptions/overview") });
      qc.prefetchQuery({ queryKey: [scope, "/api/admin/subscriptions"], queryFn: fetcher("/api/admin/subscriptions") });
      qc.prefetchQuery({ queryKey: [scope, "/api/admin/subscriptions/plans"], queryFn: fetcher("/api/admin/subscriptions/plans") });
      break;
    case "/admin/finance":
      qc.prefetchQuery({ queryKey: [scope, "/api/admin/finance/overview"], queryFn: fetcher("/api/admin/finance/overview") });
      qc.prefetchQuery({ queryKey: [scope, "/api/admin/finance/wallets"], queryFn: fetcher("/api/admin/finance/wallets") });
      qc.prefetchQuery({ queryKey: [scope, "/api/admin/finance/platform"], queryFn: fetcher("/api/admin/finance/platform") });
      break;
    case "/admin/network":
      qc.prefetchQuery({ queryKey: [scope, "/api/admin/noc/overview"], queryFn: fetcher("/api/admin/noc/overview") });
      qc.prefetchQuery({ queryKey: [scope, "/api/admin/noc/tenants"], queryFn: fetcher("/api/admin/noc/tenants") });
      qc.prefetchQuery({ queryKey: [scope, "/api/admin/noc/routers"], queryFn: fetcher("/api/admin/noc/routers") });
      break;
    case "/admin/routers":
      qc.prefetchQuery({ queryKey: [scope, "/api/admin/noc/routers"], queryFn: fetcher("/api/admin/noc/routers") });
      break;
    default:
      break;
  }
}
