"use client";

import React, { Suspense, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import api from "@/lib/api";
import { useAuth } from "@/context/AuthContext";
import { ArrowRight, EnvelopeSimple, ShieldCheck } from "@/components/icons";
import { AuthAlert, AuthLayout, Spinner, authButton, authInput, authLabel, readError } from "@/components/AuthLayout";

const RESEND_COOLDOWN_S = 60;

export default function VerifyRegistrationPage() {
  return (
    <Suspense>
      <VerifyRegistration />
    </Suspense>
  );
}

function VerifyRegistration() {
  const { login } = useAuth();
  const params = useSearchParams();
  const emailFromLink = params.get("email")?.trim() || "";
  // ?sent=1 means /register just emailed a code, so the resend cooldown is already running.
  const justSent = params.get("sent") === "1";
  const [email, setEmail] = useState(emailFromLink);
  // When the email arrives in the link we show it as text; owners can still switch to editing it.
  const [emailLocked, setEmailLocked] = useState(Boolean(emailFromLink));
  const [code, setCode] = useState("");
  const [loading, setLoading] = useState(false);
  const [resending, setResending] = useState(false);
  const [cooldown, setCooldown] = useState(justSent ? RESEND_COOLDOWN_S : 0);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(() =>
    justSent ? `We sent a 6-digit code to ${emailFromLink || "your email"}. If you don't see it, check your spam folder.` : null);
  const codeRef = useRef<HTMLInputElement>(null);
  const submittedFor = useRef<string | null>(null);

  useEffect(() => {
    if (cooldown <= 0) return;
    const t = window.setTimeout(() => setCooldown((s) => s - 1), 1000);
    return () => window.clearTimeout(t);
  }, [cooldown]);

  const verify = async (value: string) => {
    if (loading) return;
    if (!email.trim()) { setError("Enter the email address you signed up with."); return; }
    if (value.length !== 6) { setError("The code has 6 digits."); return; }
    submittedFor.current = value;
    setError(null);
    setLoading(true);
    try {
      const response = await api.post("/api/auth/register/verify", { email: email.trim(), code: value });
      login(response.data);
    } catch (err) {
      const { status, message } = readError(err);
      setError(friendlyVerifyError(status, message));
      setLoading(false);
      setCode("");
      codeRef.current?.focus();
    }
  };

  const onCodeChange = (raw: string) => {
    const digits = raw.replace(/\D/g, "").slice(0, 6);
    setCode(digits);
    if (error) setError(null);
    // Submit automatically once all six digits are in (typed or pasted), once per value.
    if (digits.length === 6 && submittedFor.current !== digits && email.trim()) void verify(digits);
  };

  const resend = async () => {
    if (cooldown > 0 || resending) return;
    if (!email.trim()) { setError("Enter your email address first."); return; }
    setResending(true);
    setError(null);
    setNotice(null);
    try {
      await api.post("/api/auth/register/resend", { email: email.trim() });
      setNotice(`If ${email.trim()} is waiting to be verified, a new code is on its way. Codes from earlier emails no longer work.`);
      submittedFor.current = null;
      setCooldown(RESEND_COOLDOWN_S);
    } catch (err) {
      const { status } = readError(err);
      if (status === 429) {
        setNotice("A code was sent a moment ago. Check your inbox and spam folder, or try again in a minute.");
        setCooldown(RESEND_COOLDOWN_S);
      } else if (status === 503) {
        setError("We can't send emails right now. Please try again later.");
      } else {
        setError("We couldn't send a new code. Check your connection and try again.");
      }
    } finally {
      setResending(false);
    }
  };

  return (
    <AuthLayout>
      <div className="text-center mb-6">
        <div className="inline-flex items-center justify-center w-12 h-12 rounded-full bg-blue-50 border border-blue-200 text-blue-700 mb-3">
          <ShieldCheck className="w-6 h-6" />
        </div>
        <h2 className="text-lg font-semibold text-[var(--fg)]">Confirm your email</h2>
        <p className="text-xs text-[var(--fg-muted)] mt-1">Enter the 6-digit code we emailed you.</p>
      </div>

      {notice && !error && <AuthAlert tone="success">{notice}</AuthAlert>}
      {error && <AuthAlert tone="error">{error}</AuthAlert>}

      <form onSubmit={(e) => { e.preventDefault(); void verify(code); }} className="space-y-5">
        {emailLocked ? (
          <div className="flex items-center justify-between gap-3 rounded-xl border border-[var(--border)] bg-[var(--subtle)] px-3.5 py-2.5 text-sm">
            <span className="flex min-w-0 items-center gap-2 text-[var(--fg-body)]">
              <EnvelopeSimple className="w-4 h-4 shrink-0 text-[var(--fg-muted)]" />
              <span className="truncate">{email}</span>
            </span>
            <button type="button" onClick={() => setEmailLocked(false)} className="shrink-0 text-xs font-semibold text-[var(--accent-text)] hover:underline">
              Change
            </button>
          </div>
        ) : (
          <div>
            <label htmlFor="verify-email" className={authLabel}>Email address</label>
            <div className="relative">
              <EnvelopeSimple className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--fg-muted)]" />
              <input id="verify-email" type="email" required autoComplete="email" value={email}
                onChange={(e) => { setEmail(e.target.value); submittedFor.current = null; }}
                placeholder="name@company.com" className={authInput} />
            </div>
          </div>
        )}

        <div>
          <label htmlFor="verify-code" className={`${authLabel} text-center`}>Verification code</label>
          <input
            id="verify-code"
            ref={codeRef}
            autoFocus
            inputMode="numeric"
            autoComplete="one-time-code"
            pattern="[0-9]{6}"
            maxLength={6}
            required
            value={code}
            onChange={(e) => onCodeChange(e.target.value)}
            placeholder="000000"
            aria-describedby="verify-code-hint"
            className="w-full py-4 rounded-xl bg-[var(--surface)] border border-[var(--border)] text-center font-mono text-3xl font-semibold tracking-[0.5em] pl-[0.5em] text-[var(--fg)] placeholder-[var(--fg-faint)] focus:outline-none focus:border-blue-700 focus:ring-2 focus:ring-blue-600/15 transition-all"
          />
          <p id="verify-code-hint" className="mt-1.5 text-center text-[11px] text-[var(--fg-muted)]">Codes expire after a few minutes.</p>
        </div>

        <button type="submit" disabled={loading || code.length !== 6} className={authButton}>
          {loading ? (<><Spinner /><span>Checking code...</span></>) : (<><span>Verify and continue</span><ArrowRight className="w-4 h-4" /></>)}
        </button>
      </form>

      <div className="mt-6 text-center text-xs text-[var(--fg-muted)]">
        Didn&apos;t get it?{" "}
        <button type="button" onClick={resend} disabled={cooldown > 0 || resending}
          className="font-semibold text-[var(--accent-text)] hover:underline disabled:cursor-not-allowed disabled:text-[var(--fg-muted)] disabled:no-underline">
          {resending ? "Sending..." : cooldown > 0 ? `Resend code in ${cooldown}s` : "Resend code"}
        </button>
      </div>

      <p className="mt-4 text-center text-xs text-[var(--fg-muted)]">
        Already verified?{" "}
        <Link href="/login" className="font-semibold text-[var(--accent-text)] hover:underline">Sign in</Link>
      </p>
    </AuthLayout>
  );
}

function friendlyVerifyError(status?: number, message?: string): string {
  const m = (message || "").toLowerCase();
  if (status === 429 || m.includes("too many")) return "Too many wrong tries. Tap \"Resend code\" to get a new one.";
  if (m.includes("expired") && !m.includes("invalid")) return "That code has expired. Tap \"Resend code\" to get a new one.";
  if (m.includes("incorrect")) return "That code isn't right. Check the latest email from AirPairy and try again.";
  if (status === 400) return "That code didn't work. It may be wrong or expired, or the email above may be different from the one you signed up with.";
  if (!status) return "We couldn't reach AirPairy. Check your internet connection and try again.";
  return message || "Something went wrong. Please try again.";
}
