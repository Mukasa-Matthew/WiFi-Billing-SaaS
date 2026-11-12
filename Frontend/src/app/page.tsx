"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/context/AuthContext";

export default function HomePage() {
  const router = useRouter();
  const { user, loading } = useAuth();

  // The public marketing site lives on airpairy.com; the app subdomain goes
  // straight to sign-in, or the workspace when already signed in.
  useEffect(() => {
    if (loading) return;
    if (!user) {
      router.replace("/login");
      return;
    }
    router.replace(user.role === "ROLE_SUPERADMIN" ? "/admin" : "/dashboard");
  }, [user, loading, router]);

  return (
    <div className="flex h-screen items-center justify-center bg-[var(--canvas)] text-[var(--fg-muted)]">
      <div className="flex flex-col items-center gap-3">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-blue-700 border-t-transparent" />
        <p className="text-sm">Loading AirPairy…</p>
      </div>
    </div>
  );
}
