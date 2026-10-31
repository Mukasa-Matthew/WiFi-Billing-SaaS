"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import Landing from "@/components/landing/Landing";
import { SignalLoader } from "@/components/SignalLoader";

export default function HomePage() {
  const router = useRouter();
  const { user, loading } = useAuth();

  useEffect(() => {
    if (loading || !user) return;
    router.replace(user.role === "ROLE_SUPERADMIN" ? "/admin" : "/dashboard");
  }, [user, loading, router]);

  if (!user) return <Landing />;

  return (
    <div className="flex h-screen items-center justify-center bg-[var(--canvas)]">
      <SignalLoader label="Loading AirPairy…" />
    </div>
  );
}
