"use client";
import { BusyLabel } from "@/components/LoadingUI";

import React, { useState } from "react";
import Link from "next/link";
import api from "@/lib/api";
import { AuthAlert, AuthField, AuthShell, authSubmit } from "@/components/AuthShell";
import { EnvelopeSimple, Lock, ArrowLeft, ArrowRight, Key } from "@/components/icons";
import axios from "axios";

const errText = (e: unknown) =>
  axios.isAxiosError(e) ? (e.response?.data?.message as string) || "Request failed — try again." : "Network error — try again.";

export default function ForgotPasswordPage() {
  const [step, setStep] = useState<"email" | "code" | "done">("email");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [password2, setPassword2] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const sendCode = async (e?: React.FormEvent) => {
    e?.preventDefault();
    setBusy(true); setError(""); setNotice("");
    try {
      await api.post("/api/auth/forgot-password", { email: email.trim() });
      setNotice("If that account exists, a reset code is on its way — check your inbox and spam folder.");
      setStep("code");
    } catch (err) { setError(errText(err)); }
    finally { setBusy(false); }
  };

  const reset = async (e: React.FormEvent) => {
    e.preventDefault();
    if (password !== password2) { setError("Passwords don't match."); return; }
    setBusy(true); setError("");
    try {
      await api.post("/api/auth/reset-password", { email: email.trim(), code: code.trim(), newPassword: password });
      setStep("done");
    } catch (err) { setError(errText(err)); }
    finally { setBusy(false); }
  };

  const title = step === "email" ? "Reset your password" : step === "code" ? "Enter reset code" : "Password updated";
  const description = step === "email" ? "We'll email you a 6-digit reset code." : step === "code" ? `Code sent to ${email}` : "You can sign in with your new password.";

  return (
    <AuthShell title={title} description={description}>
      {error && <AuthAlert>{error}</AuthAlert>}
      {notice && <AuthAlert tone="success">{notice}</AuthAlert>}

      {step === "email" && (
        <form onSubmit={sendCode} className="space-y-5">
          <AuthField id="email" label="Email address" icon={EnvelopeSimple} type="email" required autoComplete="username" inputMode="email"
            value={email} onChange={(e) => setEmail(e.target.value)} placeholder="name@company.com" />
          <button type="submit" disabled={busy} className={authSubmit}>
            {busy ? "Sending…" : <><span>Send reset code</span><ArrowRight className="h-4 w-4" aria-hidden /></>}
          </button>
        </form>
      )}

      {step === "code" && (
        <form onSubmit={reset} className="space-y-5">
          <AuthField id="code" label="6-digit code" icon={Key} required autoComplete="one-time-code" inputMode="numeric" maxLength={6}
            value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} placeholder="000000" className="font-mono tracking-[0.3em]" />
          <AuthField id="new-password" label="New password" icon={Lock} type="password" required minLength={8} autoComplete="new-password"
            value={password} onChange={(e) => setPassword(e.target.value)} placeholder="At least 8 characters" />
          <AuthField id="confirm-password" label="Confirm new password" icon={Lock} type="password" required minLength={8} autoComplete="new-password"
            value={password2} onChange={(e) => setPassword2(e.target.value)} placeholder="Repeat password" />
          <button type="submit" disabled={busy || code.length !== 6} className={authSubmit}>
            <BusyLabel busy={busy} label="Resetting…">Reset password</BusyLabel>
          </button>
          <button type="button" onClick={() => void sendCode()} disabled={busy} className="min-h-11 w-full text-[13px] font-semibold text-[var(--accent-text)] hover:underline">
            Resend code
          </button>
        </form>
      )}

      {step === "done" && (
        <Link href="/login" className={authSubmit}>
          <span>Back to sign in</span><ArrowRight className="h-4 w-4" aria-hidden />
        </Link>
      )}

      {step !== "done" && (
        <div className="mt-6 border-t border-[var(--border)] pt-3 text-center">
          <Link href="/login" className="inline-flex min-h-11 items-center gap-1.5 text-[13px] font-semibold text-[var(--fg-muted)] hover:text-[var(--fg)]">
            <ArrowLeft className="h-3.5 w-3.5" aria-hidden /> Back to sign in
          </Link>
        </div>
      )}
    </AuthShell>
  );
}
