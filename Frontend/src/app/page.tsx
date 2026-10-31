"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";
import Landing from "@/components/landing/Landing";

export default function HomePage() {
  const router = useRouter();
  const { user, loading } = useAuth();

  // Signed-in users go straight to their workspace; everyone else gets the landing page.
  useEffect(() => {
    if (loading || !user) return;
    router.replace(user.role === "ROLE_SUPERADMIN" ? "/admin" : "/dashboard");
  }, [user, loading, router]);

  if (!loading && !user) return <Landing />;

  return (
    <div className="flex h-screen items-center justify-center bg-[var(--canvas)] text-[var(--fg-muted)]">
      <div className="flex flex-col items-center gap-3">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-blue-700 border-t-transparent" />
        <p className="text-sm">Loading AirPairy…</p>
      </div>
    </div>
  );
}
