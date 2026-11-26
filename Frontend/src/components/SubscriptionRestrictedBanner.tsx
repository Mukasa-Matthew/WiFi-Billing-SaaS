"use client";

import React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useApi } from "@/components/BillingUI";
import { WarningCircle, CreditCard, ArrowRight } from "@/components/icons";

interface SubStatus {
  subscribed: boolean;
  status: string;
  isExpired: boolean;
  inGrace: boolean;
  isSuspended: boolean;
  canAcceptCustomerPayments: boolean;
  reminderMessage?: string;
  daysRemaining: number;
}

export default function SubscriptionRestrictedBanner() {
  // Shares the cached (and prefetched) subscription query instead of a
  // separate request on every workspace load.
  const { data: status } = useApi<SubStatus>("/api/subscription");
  const onSubscriptionPage = usePathname() === "/dashboard/subscription";

  if (!status || !status.isExpired) {
    return null;
  }

  return (
    <div role="alert" className="sticky top-0 z-50 mb-6 flex items-center justify-between gap-4 rounded-lg bg-red-600 px-4 py-2.5 text-white shadow-md">
      <div className="flex items-center gap-2.5 text-xs font-semibold">
        <WarningCircle size={18} className="shrink-0 text-white" />
        <span>
          {status.inGrace
            ? "Your subscription has expired. Customers can’t pay until you renew."
            : "Your account is suspended. Customer payments and router tools are off until you renew."}
        </span>
      </div>
      {!onSubscriptionPage && <Link
        href="/dashboard/subscription"
        className="flex shrink-0 items-center gap-1.5 rounded-md bg-white px-3 py-1 text-xs font-bold text-red-700 shadow-sm transition hover:bg-red-50"
      >
        <CreditCard size={14} />
        Renew
        <ArrowRight size={13} />
      </Link>}
    </div>
  );
}
