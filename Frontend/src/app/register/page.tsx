"use client";

import React, { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import api from "@/lib/api";
import { ArrowRight, Building2 as Building, EnvelopeSimple, Eye, EyeSlash, Lock, Phone } from "@/components/icons";
import { AuthAlert, AuthLayout, Spinner, authButton, authInput, authLabel, readError } from "@/components/AuthLayout";

type Problem =
  | { kind: "text"; text: string }
  | { kind: "exists" }
  | { kind: "recentCode" };

const MIN_PASSWORD = 8;

export default function RegisterPage() {
  const router = useRouter();
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [problem, setProblem] = useState<Problem | null>(null);

  const trimmedEmail = email.trim();
  const verifyHref = (sent: boolean) =>
    `/register/verify?email=${encodeURIComponent(trimmedEmail)}${sent ? "&sent=1" : ""}`;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setProblem(null);
    if (!name.trim()) return setProblem({ kind: "text", text: "Enter your business name." });
    if (!phone.trim()) return setProblem({ kind: "text", text: "Enter a phone number we can reach you on." });
    if (password.length < MIN_PASSWORD) return setProblem({ kind: "text", text: `Your password needs at least ${MIN_PASSWORD} characters.` });
    if (password !== confirm) return setProblem({ kind: "text", text: "The two passwords don't match." });

    setLoading(true);
    try {
      await api.post("/api/auth/register", {
        name: name.trim(),
        email: trimmedEmail,
        phone: phone.trim(),
        password,
      });
      router.push(verifyHref(true));
    } catch (err) {
      const { status, message } = readError(err);
      if (status === 409) setProblem({ kind: "exists" });
      else if (status === 429) setProblem({ kind: "recentCode" });
      else if (status === 503) setProblem({ kind: "text", text: "We can't send emails right now, so sign-up is paused. Please try again later." });
      else if (status === 400) setProblem({ kind: "text", text: friendlyValidation(message) });
      else if (status) setProblem({ kind: "text", text: message || "Something went wrong. Please try again." });
      else setProblem({ kind: "text", text: "We couldn't reach AirPairy. Check your internet connection and try again." });
      setLoading(false);
    }
  };

  return (
    <AuthLayout>
      <div className="mb-6">
        <h2 className="text-lg font-semibold text-[var(--fg)]">Create your account</h2>
        <p className="text-xs text-[var(--fg-muted)] mt-1">Start selling WiFi from your MikroTik hotspot.</p>
      </div>

      {problem && (
        <AuthAlert tone="error">
          {problem.kind === "text" && problem.text}
          {problem.kind === "exists" && (
            <>
              An account with this email already exists.{" "}
              <Link href="/login" className="font-semibold underline">Sign in</Link>
            </>
          )}
          {problem.kind === "recentCode" && (
            <>
              We sent a code to this email a moment ago.{" "}
              <Link href={verifyHref(true)} className="font-semibold underline">Enter the code</Link>
            </>
          )}
        </AuthAlert>
      )}

      <form onSubmit={handleSubmit} className="space-y-5">
        <div>
          <label htmlFor="reg-name" className={authLabel}>Business name</label>
          <div className="relative">
            <Building className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--fg-muted)]" />
            <input id="reg-name" required autoComplete="organization" maxLength={120} value={name}
              onChange={(e) => setName(e.target.value)} placeholder="e.g. Kampala Fast WiFi" className={authInput} />
          </div>
        </div>

        <div>
          <label htmlFor="reg-email" className={authLabel}>Email address</label>
          <div className="relative">
            <EnvelopeSimple className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--fg-muted)]" />
            <input id="reg-email" type="email" required autoComplete="email" value={email}
              onChange={(e) => setEmail(e.target.value)} placeholder="name@company.com" className={authInput} />
          </div>
          <p className="mt-1.5 text-[11px] text-[var(--fg-muted)]">We&apos;ll send a 6-digit code here to confirm it&apos;s yours.</p>
        </div>

        <div>
          <label htmlFor="reg-phone" className={authLabel}>Phone number</label>
          <div className="relative">
            <Phone className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--fg-muted)]" />
            <input id="reg-phone" type="tel" required autoComplete="tel" inputMode="tel" maxLength={20} value={phone}
              onChange={(e) => setPhone(e.target.value)} placeholder="07XX XXX XXX" className={authInput} />
          </div>
        </div>

        <div>
          <label htmlFor="reg-password" className={authLabel}>Password</label>
          <div className="relative">
            <Lock className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--fg-muted)]" />
            <input id="reg-password" type={showPassword ? "text" : "password"} required minLength={MIN_PASSWORD}
              autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)}
              placeholder="At least 8 characters" className={`${authInput} !pr-11`} />
            <button type="button" onClick={() => setShowPassword((v) => !v)}
              aria-label={showPassword ? "Hide password" : "Show password"}
              className="absolute right-3 top-1/2 -translate-y-1/2 p-1 rounded-md text-[var(--fg-muted)] hover:text-[var(--fg-body)] hover:bg-[var(--fill)] focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-600/40 cursor-pointer">
              {showPassword ? <EyeSlash className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
            </button>
          </div>
          {password.length > 0 && password.length < MIN_PASSWORD && (
            <p className="mt-1.5 text-[11px] text-amber-600">{MIN_PASSWORD - password.length} more character{MIN_PASSWORD - password.length === 1 ? "" : "s"} needed.</p>
          )}
        </div>

        <div>
          <label htmlFor="reg-confirm" className={authLabel}>Confirm password</label>
          <div className="relative">
            <Lock className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--fg-muted)]" />
            <input id="reg-confirm" type={showPassword ? "text" : "password"} required autoComplete="new-password"
              value={confirm} onChange={(e) => setConfirm(e.target.value)} placeholder="Type it again" className={authInput} />
          </div>
          {confirm.length > 0 && confirm !== password && (
            <p className="mt-1.5 text-[11px] text-amber-600">Passwords don&apos;t match yet.</p>
          )}
        </div>

        <button type="submit" disabled={loading} className={authButton}>
          {loading ? (<><Spinner /><span>Creating your account...</span></>) : (<><span>Create account</span><ArrowRight className="w-4 h-4" /></>)}
        </button>
      </form>

      <p className="mt-6 text-center text-xs text-[var(--fg-muted)]">
        Already have an account?{" "}
        <Link href="/login" className="font-semibold text-[var(--accent-text)] hover:underline">Sign in</Link>
      </p>
    </AuthLayout>
  );
}

/** Spring validation errors arrive as "field: message"; turn them into plain sentences. */
function friendlyValidation(message?: string): string {
  if (!message) return "Some details look wrong. Check the form and try again.";
  if (message.startsWith("email:")) return "That email address doesn't look right.";
  if (message.startsWith("password:")) return `Your password needs at least ${MIN_PASSWORD} characters.`;
  if (message.startsWith("phone:")) return "Enter a phone number we can reach you on.";
  if (message.startsWith("name:")) return "Enter your business name.";
  return message;
}
