"use client";
import { BusyLabel } from "@/components/LoadingUI";

import React, { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import axios from "axios";
import api from "@/lib/api";
import { useAuth } from "@/context/AuthContext";
import { Lock, Radio, WarningCircle, ShieldCheck } from "@/components/icons";

export default function ChangePasswordPage() {
  const { user, loading, markPasswordChanged } = useAuth();
  const router = useRouter();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (loading) return;
    if (!user) router.replace("/login");
    else if (!user.mustChangePassword) router.replace(user.role === "ROLE_SUPERADMIN" ? "/admin" : "/dashboard");
  }, [loading, router, user]);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError("");
    if (newPassword !== confirmPassword) {
      setError("The new passwords do not match.");
      return;
    }
    setBusy(true);
    try {
      await api.post("/api/auth/change-password", { currentPassword, newPassword });
      markPasswordChanged();
    } catch (err) {
      setError(axios.isAxiosError(err)
        ? err.response?.data?.message || "Could not change your password."
        : "Network error. Please try again.");
    } finally {
      setBusy(false);
    }
  };

  if (loading || !user || !user.mustChangePassword) return null;

  const input = "w-full pl-10 pr-4 py-2.5 rounded-xl bg-[var(--surface)] border border-[var(--border)] text-sm text-[var(--fg)] focus:outline-none focus:border-blue-700 focus:ring-2 focus:ring-blue-600/15";
  return (
    <div className="min-h-screen flex items-center justify-center p-4 bg-[var(--canvas)]">
      <div className="w-full max-w-md">
        <div className="text-center mb-7">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-[#122B49] mb-4"><Radio className="w-7 h-7 text-white" /></div>
          <h1 className="text-2xl font-bold text-[var(--fg)]">Secure your account</h1>
          <p className="text-sm text-[var(--fg-muted)] mt-1">Create your own password before continuing.</p>
        </div>
        <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl p-8 shadow-sm">
          <div className="flex items-start gap-3 mb-6 p-3.5 rounded-xl bg-amber-50 border border-amber-200 text-amber-800 text-sm">
            <ShieldCheck className="w-5 h-5 shrink-0" />
            <span>You signed in with a temporary password. It can only be used to reach this screen.</span>
          </div>
          {error && <div className="mb-5 p-3.5 rounded-xl bg-red-50 border border-red-200 flex gap-3 text-red-600 text-sm"><WarningCircle className="w-5 h-5 shrink-0" /><span>{error}</span></div>}
          <form onSubmit={submit} className="space-y-4">
            {[["Temporary password", currentPassword, setCurrentPassword], ["New password", newPassword, setNewPassword], ["Confirm new password", confirmPassword, setConfirmPassword]].map(([label, value, setter]) => (
              <label key={label as string} className="block text-xs font-medium text-[var(--fg-body)]">
                {label as string}
                <div className="relative mt-1.5"><Lock className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--fg-muted)]" />
                  <input type="password" required minLength={8} value={value as string} onChange={(e) => (setter as React.Dispatch<React.SetStateAction<string>>)(e.target.value)} className={input} />
                </div>
              </label>
            ))}
            <button type="submit" disabled={busy} className="w-full mt-2 py-3 rounded-lg bg-blue-700 hover:bg-blue-600 disabled:opacity-50 text-sm font-semibold text-white">
              <BusyLabel busy={busy} label="Updating password…">Set password and continue</BusyLabel>
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}
