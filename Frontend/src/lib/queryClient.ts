"use client";

import { QueryClient } from "@tanstack/react-query";

/**
 * Shared QueryClient instance configured for WiFi Billing SaaS:
 * - staleTime: 15 seconds (within our ~20s cycle to prevent duplicate refetches during fast multi-page hops)
 * - gcTime (garbage collection cache time): 10 minutes so cached views survive deep navigation
 * - refetchOnWindowFocus: true (smart refetch on tab focus)
 * - retry: 1 (avoid hammering slow/failed networks)
 */
export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 15 * 1000,
        gcTime: 10 * 60 * 1000,
        refetchOnWindowFocus: true,
        refetchOnReconnect: true,
        retry: 1,
      },
    },
  });
}

let browserQueryClient: QueryClient | undefined = undefined;

export function getQueryClient() {
  if (typeof window === "undefined") {
    // Server: always make a new query client
    return createQueryClient();
  }
  // Browser: make a new query client if we don't already have one
  // This ensures data is not shared across users/requests in SSR but is reused across pages in browser
  if (!browserQueryClient) browserQueryClient = createQueryClient();
  return browserQueryClient;
}

/**
 * Helper to wipe all queries on tenant change, logout, or 401 unauthenticated event.
 */
export function resetQueryCache() {
  if (browserQueryClient) {
    browserQueryClient.clear();
  }
}
