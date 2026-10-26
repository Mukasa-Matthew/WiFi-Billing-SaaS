"use client";

import React, { useEffect, useState } from "react";
import Link from "next/link";
import api from "@/lib/api";
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
  const [status, setStatus] = useState<SubStatus | null>(null);

  useEffect(() => {
    api.get<SubStatus>("/api/subscription")
      .then((res) => setStatus(res.data))
      .catch(() => {});
  }, []);

  if (!status || !status.isExpired) {
    return null;
  }

  return (
    <div className="sticky top-0 z-50 flex items-center justify-between gap-4 border-b border-red-500 bg-red-600 px-4 py-2.5 text-white shadow-md">
      <div className="flex items-center gap-2.5 text-xs font-semibold">
        <WarningCircle size={18} className="shrink-0 text-white animate-pulse" />
        <span>
          {status.inGrace
            ? "Your subscription has expired (2-day grace period active). New hotspot customer payments and management features are locked."
            : "Your account is suspended due to unpaid subscription. Customer payments and router tools are disabled."}
        </span>
      </div>
      <Link
        href="/dashboard/subscription"
        className="flex shrink-0 items-center gap-1.5 rounded-md bg-white px-3 py-1 text-xs font-bold text-red-700 shadow-sm transition hover:bg-red-50"
      >
        <CreditCard size={14} />
        Renew Now
        <ArrowRight size={13} />
      </Link>
    </div>
  );
}
