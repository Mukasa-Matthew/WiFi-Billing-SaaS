"use client";

import React, { useState } from "react";
import { useAuth } from "@/context/AuthContext";
import api from "@/lib/api";
import { Lock, EnvelopeSimple, Radio, WarningCircle, ArrowRight, Eye, EyeSlash, ShieldCheck, Key } from "@/components/icons";

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

  return (
    <div className="relative min-h-screen flex items-center justify-center p-4 bg-[var(--canvas)] overflow-hidden">
      <div className="relative w-full max-w-md">
        {/* Header branding */}
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-[#122B49] mb-4">
            <Radio className="w-7 h-7 text-white" />
          </div>
          <h1 className="text-2xl font-bold tracking-tight text-[var(--fg)]">
            AirPairy
          </h1>
          <p className="text-sm text-[var(--fg-muted)] mt-1">
            Connecting WiFi. Simplifying Business.
          </p>
        </div>

        {/* Card */}
        <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl p-8 shadow-sm">
          {!requires2fa ? (
            <>
              <div className="mb-6">
                <h2 className="text-lg font-semibold text-[var(--fg)]">Account Sign In</h2>
              </div>

              {error && (
                <div className="mb-6 p-3.5 rounded-xl bg-red-50 border border-red-200 flex items-start gap-3 text-red-600 text-sm">
                  <WarningCircle className="w-5 h-5 shrink-0 mt-0.5" />
                  <span>{error}</span>
                </div>
              )}

              <form onSubmit={handleSubmit} className="space-y-5">
                <div>
                  <label className="block text-xs font-medium text-[var(--fg-body)] mb-1.5">
                    Email Address
                  </label>
                  <div className="relative">
                    <EnvelopeSimple className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--fg-muted)]" />
                    <input
                      type="email"
                      required
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      placeholder={process.env.NEXT_PUBLIC_SUPPORT_EMAIL || "name@company.com"}
                      className="w-full pl-10 pr-4 py-2.5 rounded-xl bg-[var(--surface)] border border-[var(--border)] text-sm text-[var(--fg)] placeholder-[var(--fg-muted)] focus:outline-none focus:border-blue-700 focus:ring-2 focus:ring-blue-600/15 transition-all"
                    />
                  </div>
                </div>

                <div>
                  <div className="flex items-center justify-between mb-1.5">
                    <label className="block text-xs font-medium text-[var(--fg-body)]">
                      Password
                    </label>
                    <a href="/forgot-password" className="text-xs font-semibold text-[var(--accent-text)] hover:underline">
                      Forgot password?
                    </a>
                  </div>
                  <div className="relative">
                    <Lock className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--fg-muted)]" />
                    <input
                      type={showPassword ? "text" : "password"}
                      required
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      placeholder="••••••••••••"
                      className="w-full pl-10 pr-11 py-2.5 rounded-xl bg-[var(--surface)] border border-[var(--border)] text-sm text-[var(--fg)] placeholder-[var(--fg-muted)] focus:outline-none focus:border-blue-700 focus:ring-2 focus:ring-blue-600/15 transition-all"
                    />
                    <button
                      type="button"
                      onClick={() => setShowPassword((v) => !v)}
                      aria-label={showPassword ? "Hide password" : "Show password"}
                      className="absolute right-3 top-1/2 -translate-y-1/2 p-1 rounded-md text-[var(--fg-muted)] hover:text-[var(--fg-body)] hover:bg-[var(--fill)] focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-600/40 cursor-pointer"
                    >
                      {showPassword ? <EyeSlash className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                    </button>
                  </div>
                </div>

                <button
                  type="submit"
                  disabled={loading}
                  className="w-full flex items-center justify-center gap-2 py-3 px-4 rounded-lg bg-blue-700 hover:bg-blue-600 disabled:opacity-50 text-sm font-semibold text-white shadow-sm transition-all cursor-pointer"
                >
                  {loading ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
                      <span>Verifying credentials...</span>
                    </>
                  ) : (
                    <>
                      <span>Sign In</span>
                      <ArrowRight className="w-4 h-4" />
                    </>
                  )}
                </button>
              </form>
            </>
          ) : (
            <>
              {/* 2FA Challenge Screen */}
              <div className="text-center mb-6">
                <div className="inline-flex items-center justify-center w-12 h-12 rounded-full bg-blue-50 border border-blue-200 text-blue-700 mb-3">
                  <ShieldCheck className="w-6 h-6" />
                </div>
                <h2 className="text-lg font-semibold text-[var(--fg)]">Two-Factor Authentication</h2>
                <p className="text-xs text-[var(--fg-muted)] mt-1">
                  {isRecoveryCode
                    ? "Enter an emergency 8-character recovery code"
                    : "Enter the 6-digit code from your authenticator app"}
                </p>
              </div>

              {error && (
                <div className="mb-6 p-3.5 rounded-xl bg-red-50 border border-red-200 flex items-start gap-3 text-red-600 text-sm">
                  <WarningCircle className="w-5 h-5 shrink-0 mt-0.5" />
                  <span>{error}</span>
                </div>
              )}

              <form onSubmit={handleVerify2fa} className="space-y-5">
                <div>
                  <label className="block text-xs font-medium text-[var(--fg-body)] mb-1.5">
                    {isRecoveryCode ? "Recovery Code (e.g. 8A3F-9K2M)" : "6-Digit Security Code"}
                  </label>
                  <div className="relative">
                    <Key className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--fg-muted)]" />
                    <input
                      type="text"
                      autoFocus
                      required
                      maxLength={isRecoveryCode ? 12 : 6}
                      value={totpCode}
                      onChange={(e) => setTotpCode(e.target.value.toUpperCase())}
                      placeholder={isRecoveryCode ? "XXXX-XXXX" : "123456"}
                      className="w-full pl-10 pr-4 py-2.5 font-mono text-center tracking-widest text-base rounded-xl bg-[var(--surface)] border border-[var(--border)] text-[var(--fg)] placeholder-[var(--fg-muted)] focus:outline-none focus:border-blue-700 focus:ring-2 focus:ring-blue-600/15 transition-all"
                    />
                  </div>
                </div>

                <button
                  type="submit"
                  disabled={loading || totpCode.trim().length < 6}
                  className="w-full flex items-center justify-center gap-2 py-3 px-4 rounded-lg bg-blue-700 hover:bg-blue-600 disabled:opacity-50 text-sm font-semibold text-white shadow-sm transition-all cursor-pointer"
                >
                  {loading ? (
                    <>
                      <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
                      <span>Verifying factor...</span>
                    </>
                  ) : (
                    <>
                      <span>Verify & Continue</span>
                      <ArrowRight className="w-4 h-4" />
                    </>
                  )}
                </button>

                <div className="pt-2 text-center flex flex-col gap-2">
                  <button
                    type="button"
                    onClick={() => {
                      setIsRecoveryCode(!isRecoveryCode);
                      setTotpCode("");
                      setError(null);
                    }}
                    className="text-xs text-[var(--accent-text)] font-medium hover:underline"
                  >
                    {isRecoveryCode ? "Use Authenticator App Instead" : "Lost access? Use an emergency recovery code"}
                  </button>

                  <button
                    type="button"
                    onClick={() => {
                      setRequires2fa(false);
                      setTempToken("");
                      setTotpCode("");
                      setError(null);
                    }}
                    className="text-xs text-[var(--fg-muted)] hover:text-[var(--fg)]"
                  >
                    ← Back to email sign in
                  </button>
                </div>
              </form>
            </>
          )}
        </div>
        <p className="text-center text-[11px] text-[var(--fg-faint)] mt-3">
          AirPairy is a product by Thewton.
        </p>
      </div>
    </div>
  );
}
