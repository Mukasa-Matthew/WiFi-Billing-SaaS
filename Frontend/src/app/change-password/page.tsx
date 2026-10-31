"use client";
import { BusyLabel } from "@/components/LoadingUI";

import React, { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import axios from "axios";
import api from "@/lib/api";
import { useAuth } from "@/context/AuthContext";
import { AuthAlert, AuthField, AuthShell, authSubmit } from "@/components/AuthShell";
import { Lock } from "@/components/icons";

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

  return (
    <AuthShell title="Secure your account" description="Create your own password before continuing.">
      <AuthAlert tone="warning">You signed in with a temporary password. It can only be used to reach this screen.</AuthAlert>
      {error && <AuthAlert>{error}</AuthAlert>}
      <form onSubmit={submit} className="space-y-5">
        <AuthField id="current-password" label="Temporary password" icon={Lock} type="password" required minLength={8} autoComplete="current-password"
          value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)} />
        <AuthField id="new-password" label="New password" icon={Lock} type="password" required minLength={8} autoComplete="new-password"
          value={newPassword} onChange={(e) => setNewPassword(e.target.value)} />
        <AuthField id="confirm-password" label="Confirm new password" icon={Lock} type="password" required minLength={8} autoComplete="new-password"
          value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)} />
        <button type="submit" disabled={busy} className={authSubmit}>
          <BusyLabel busy={busy} label="Updating password…">Set password and continue</BusyLabel>
        </button>
      </form>
    </AuthShell>
  );
}
