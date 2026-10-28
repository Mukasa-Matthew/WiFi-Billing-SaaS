"use client";

import { getQueryClient } from "@/lib/queryClient";

/**
 * Cache invalidation rules:
 * Immediate cache invalidation on successful mutations (creation, updates, deletions).
 */

export function invalidateDashboardData(tenantId?: number, keys?: string[]) {
  if (typeof window === "undefined") return;
  const qc = getQueryClient();
  const scope = tenantId !== undefined ? `tenant-${tenantId}` : undefined;

  if (!keys || keys.length === 0) {
    if (scope) {
      qc.invalidateQueries({ queryKey: [scope] });
    } else {
      qc.invalidateQueries();
    }
    return;
  }

  keys.forEach((key) => {
    if (scope) {
      qc.invalidateQueries({ queryKey: [scope, key] });
    } else {
      qc.invalidateQueries({
        predicate: (query) => query.queryKey.some((k) => typeof k === "string" && k.includes(key)),
      });
    }
  });
}

/**
 * Common mutation invalidation helpers
 */
export const mutateCache = {
  packageChanged: (tenantId?: number) => {
    invalidateDashboardData(tenantId, ["/api/packages", "/api/dashboard/stats"]);
  },
  voucherChanged: (tenantId?: number) => {
    // /api/users is built from vouchers (Active/Expired users pages).
    invalidateDashboardData(tenantId, ["/api/vouchers", "/api/dashboard/stats", "/api/sessions/active", "/api/users"]);
  },
  routerChanged: (tenantId?: number) => {
    invalidateDashboardData(tenantId, ["/api/routers", "/api/dashboard/stats", "/api/admin/noc/routers", "/api/admin/noc/overview"]);
  },
  customerChanged: (tenantId?: number) => {
    invalidateDashboardData(tenantId, ["/api/customers", "/api/dashboard/stats"]);
  },
  subscriptionChanged: (tenantId?: number) => {
    invalidateDashboardData(tenantId, ["/api/subscription", "/api/dashboard/stats", "/api/admin/subscriptions", "/api/admin/subscriptions/overview"]);
  },
  paymentCompleted: (tenantId?: number) => {
    invalidateDashboardData(tenantId, ["/api/finance/payments", "/api/wallet", "/api/dashboard/stats"]);
  },
  tenantStatusChanged: () => {
    invalidateDashboardData(undefined, ["/api/admin/tenants", "/api/admin/noc/overview"]);
  },
};
