"use client";

import React, { useState } from "react";
import { useAuth } from "@/context/AuthContext";
import api from "@/lib/api";
import { Spinner } from "@/components/LoadingUI";
import { AuthAlert, AuthField, AuthShell, authSubmit } from "@/components/AuthShell";
import { Lock, EnvelopeSimple, ArrowRight, Eye, EyeSlash, Key } from "@/components/icons";

export default function LoginPage() {
  const { login } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Two-Factor Challenge State
  const [requires2fa, setRequires2fa] = useState(false);
  const [tempToken, setTempToken] = useState("");
  const [totpCode, setTotpCode] = useState("");
  const [isRecoveryCode, setIsRecoveryCode] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      const response = await api.post("/api/auth/login", {
        email: email.trim(),
        password: password,
      });

      if (response.data.requires2fa) {
        setRequires2fa(true);
        setTempToken(response.data.tempToken);
        setLoading(false);
        return;
      }

      // Normal login
      login(response.data);
    } catch (err: unknown) {
      if (err && typeof err === "object" && "response" in err) {
        const axErr = err as { response?: { status?: number; data?: { message?: string } } };
        if (axErr.response?.status === 401) {
          setError("Invalid email address or password.");
        } else if (axErr.response?.status === 403) {
          setError("Your account is suspended or disabled. Please contact Superadmin.");
        } else {
          setError(axErr.response?.data?.message || "Failed to connect to authentication server.");
        }
      } else {
        setError("Network error: Could not reach the server. Please try again.");
      }
      setLoading(false);
    }
  };

  const handleVerify2fa = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      const response = await api.post("/api/auth/2fa/verify", {
        tempToken: tempToken,
        code: totpCode.trim(),
      });

      login(response.data);
    } catch (err: unknown) {
      if (err && typeof err === "object" && "response" in err) {
        const axErr = err as { response?: { status?: number; data?: { message?: string } } };
        setError(axErr.response?.data?.message || "Invalid verification code or recovery code.");
      } else {
        setError("Network error: Could not verify 2FA code.");
      }
    } finally {
      setLoading(false);
    }
  };

  const busy = (text: string) => (
    <>
      <Spinner />
      <span>{text}</span>
    </>
  );

  if (requires2fa) {
    return (
      <AuthShell
        title="Two-factor authentication"
        description={isRecoveryCode ? "Enter an emergency 8-character recovery code." : "Enter the 6-digit code from your authenticator app."}
      >
        {error && <AuthAlert>{error}</AuthAlert>}
        <form onSubmit={handleVerify2fa} className="space-y-5">
          <AuthField
            id="totp" label={isRecoveryCode ? "Recovery code" : "Security code"} icon={Key}
            type="text" autoFocus required autoComplete="one-time-code" inputMode={isRecoveryCode ? "text" : "numeric"}
            maxLength={isRecoveryCode ? 12 : 6} value={totpCode} onChange={(e) => setTotpCode(e.target.value.toUpperCase())}
            placeholder={isRecoveryCode ? "XXXX-XXXX" : "123456"} className="font-mono tracking-[0.3em]"
          />
          <button type="submit" disabled={loading || totpCode.trim().length < 6} className={authSubmit}>
            {loading ? busy("Verifying code…") : <><span>Verify and continue</span><ArrowRight className="h-4 w-4" aria-hidden /></>}
          </button>
          <div className="flex flex-col items-center gap-1 pt-1">
            <button
              type="button"
              onClick={() => { setIsRecoveryCode(!isRecoveryCode); setTotpCode(""); setError(null); }}
              className="min-h-11 text-[13px] font-semibold text-[var(--accent-text)] hover:underline"
            >
              {isRecoveryCode ? "Use authenticator app instead" : "Lost access? Use a recovery code"}
            </button>
            <button
              type="button"
              onClick={() => { setRequires2fa(false); setTempToken(""); setTotpCode(""); setError(null); }}
              className="min-h-11 text-[13px] text-[var(--fg-muted)] hover:text-[var(--fg)]"
            >
              Back to email sign in
            </button>
          </div>
        </form>
      </AuthShell>
    );
  }

  return (
    <AuthShell title="Sign in" description="Use your AirPairy account to continue.">
      {error && <AuthAlert>{error}</AuthAlert>}
      <form onSubmit={handleSubmit} className="space-y-5">
        <AuthField
          id="email" label="Email address" icon={EnvelopeSimple} type="email" required autoComplete="username" inputMode="email"
          value={email} onChange={(e) => setEmail(e.target.value)} placeholder={process.env.NEXT_PUBLIC_SUPPORT_EMAIL || "name@company.com"}
        />
        <AuthField
          id="password" label="Password" icon={Lock} type={showPassword ? "text" : "password"} required autoComplete="current-password"
          value={password} onChange={(e) => setPassword(e.target.value)}
          aside={<a href="/forgot-password" className="-my-3 inline-flex min-h-11 items-center text-[13px] font-semibold text-[var(--accent-text)] hover:underline">Forgot password?</a>}
          trailing={
            <button
              type="button"
              onClick={() => setShowPassword((v) => !v)}
              aria-label={showPassword ? "Hide password" : "Show password"}
              aria-pressed={showPassword}
              className="absolute right-0.5 top-1/2 flex h-11 w-11 -translate-y-1/2 items-center justify-center rounded-lg text-[var(--fg-muted)] hover:text-[var(--fg)]"
            >
              {showPassword ? <EyeSlash className="h-[18px] w-[18px]" aria-hidden /> : <Eye className="h-[18px] w-[18px]" aria-hidden />}
            </button>
          }
        />
        <button type="submit" disabled={loading} className={authSubmit}>
          {loading ? busy("Signing in…") : <><span>Sign in</span><ArrowRight className="h-4 w-4" aria-hidden /></>}
        </button>
      </form>
    </AuthShell>
  );
}
