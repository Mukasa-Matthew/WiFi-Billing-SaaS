"use client";

import React, { useState } from "react";
import Link from "next/link";
import api from "@/lib/api";
import { EnvelopeSimple, Lock, Radio, WarningCircle, CheckCircle, ArrowLeft, ArrowRight, Key } from "@/components/icons";
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

  const input = "w-full pl-10 pr-4 py-2.5 rounded-xl bg-[var(--surface)] border border-[var(--border)] text-sm text-[var(--fg)] placeholder-[var(--fg-muted)] focus:outline-none focus:border-blue-700 focus:ring-2 focus:ring-blue-600/15 transition-all";
  const lbl = "block text-xs font-medium text-[var(--fg-body)] mb-1.5";

  return (
    <div className="relative min-h-screen flex items-center justify-center p-4 bg-[var(--canvas)] overflow-hidden">
      <div className="relative w-full max-w-md">
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-[#122B49] mb-4">
            <Radio className="w-7 h-7 text-white" />
          </div>
          <h1 className="text-2xl font-bold tracking-tight text-[var(--fg)]">AirPairy</h1>
          <p className="text-sm text-[var(--fg-muted)] mt-1">Connecting WiFi. Simplifying Business.</p>
        </div>

        <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl p-8 shadow-sm">
          <h2 className="text-lg font-semibold text-[var(--fg)]">
            {step === "email" && "Reset your password"}
            {step === "code" && "Enter reset code"}
            {step === "done" && "Password updated"}
          </h2>
          <p className="text-xs text-[var(--fg-muted)] mt-1">
            {step === "email" && "We'll email you a 6-digit reset code."}
            {step === "code" && `Code sent to ${email}`}
            {step === "done" && "You can sign in with your new password."}
          </p>

          {error && (
            <div className="mt-4 p-3.5 rounded-xl bg-red-50 border border-red-200 flex items-start gap-3 text-red-600 text-sm">
              <WarningCircle className="w-5 h-5 shrink-0 mt-0.5" /><span>{error}</span>
            </div>
          )}
          {notice && (
            <div className="mt-4 p-3.5 rounded-xl bg-emerald-50 border border-emerald-200 flex items-start gap-3 text-emerald-700 text-sm">
              <CheckCircle className="w-5 h-5 shrink-0 mt-0.5" /><span>{notice}</span>
            </div>
          )}

          {step === "email" && (
            <form onSubmit={sendCode} className="mt-6 space-y-5">
              <div>
                <label className={lbl}>Email Address</label>
                <div className="relative">
                  <EnvelopeSimple className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--fg-muted)]" />
                  <input type="email" required value={email} onChange={(e) => setEmail(e.target.value)}
                    placeholder="name@company.com" className={input} />
                </div>
              </div>
              <button type="submit" disabled={busy}
                className="w-full flex items-center justify-center gap-2 py-3 px-4 rounded-lg bg-blue-700 hover:bg-blue-600 disabled:opacity-50 text-sm font-semibold text-white shadow-sm transition-all cursor-pointer">
                {busy ? "Sending…" : <><span>Send reset code</span><ArrowRight className="w-4 h-4" /></>}
              </button>
            </form>
          )}

          {step === "code" && (
            <form onSubmit={reset} className="mt-6 space-y-5">
              <div>
                <label className={lbl}>6-digit code</label>
                <div className="relative">
                  <Key className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--fg-muted)]" />
                  <input required inputMode="numeric" maxLength={6} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
                    placeholder="000000" className={`${input} font-mono tracking-[0.4em]`} />
                </div>
              </div>
              <div>
                <label className={lbl}>New password</label>
                <div className="relative">
                  <Lock className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--fg-muted)]" />
                  <input type="password" required minLength={8} value={password} onChange={(e) => setPassword(e.target.value)}
                    placeholder="At least 8 characters" className={input} />
                </div>
              </div>
              <div>
                <label className={lbl}>Confirm new password</label>
                <div className="relative">
                  <Lock className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--fg-muted)]" />
                  <input type="password" required minLength={8} value={password2} onChange={(e) => setPassword2(e.target.value)}
                    placeholder="Repeat password" className={input} />
                </div>
              </div>
              <button type="submit" disabled={busy || code.length !== 6}
                className="w-full flex items-center justify-center gap-2 py-3 px-4 rounded-lg bg-blue-700 hover:bg-blue-600 disabled:opacity-50 text-sm font-semibold text-white shadow-sm transition-all cursor-pointer">
                {busy ? "Resetting…" : "Reset password"}
              </button>
              <button type="button" onClick={() => void sendCode()} disabled={busy}
                className="w-full text-xs font-semibold text-[var(--accent-text)] hover:underline">
                Resend code
              </button>
            </form>
          )}

          {step === "done" && (
            <Link href="/login"
              className="mt-6 w-full flex items-center justify-center gap-2 py-3 px-4 rounded-lg bg-blue-700 hover:bg-blue-600 text-sm font-semibold text-white shadow-sm transition-all">
              <span>Back to sign in</span><ArrowRight className="w-4 h-4" />
            </Link>
          )}

          {step !== "done" && (
            <div className="mt-6 pt-5 border-t border-[var(--border)] text-center">
              <Link href="/login" className="inline-flex items-center gap-1.5 text-xs font-semibold text-[var(--fg-muted)] hover:text-[var(--fg)]">
                <ArrowLeft className="w-3.5 h-3.5" /> Back to sign in
              </Link>
            </div>
          )}
        </div>

        <p className="text-center text-[11px] text-[var(--fg-faint)] mt-6">AirPairy is a product by Thewton.</p>
      </div>
    </div>
  );
}
