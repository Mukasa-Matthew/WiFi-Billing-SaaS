import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { Wifi, ShieldCheck, Lock, Mail, ArrowRight, AlertCircle, Globe } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { ApiError } from '../lib/api';

export const LoginPage: React.FC = () => {
  const { login } = useAuth();
  const navigate = useNavigate();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMessage(null);
    setSubmitting(true);
    try {
      const sessionUser = await login(email, password);
      if (sessionUser.superAdmin) {
        navigate('/super-admin', { replace: true });
      } else {
        navigate('/business', { replace: true });
      }
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMessage(err.message);
      } else {
        setErrorMessage('Unable to reach backend authentication service.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const fillQuickDemo = (demoEmail: string) => {
    setEmail(demoEmail);
    setPassword('Admin@2026!');
    setErrorMessage(null);
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col lg:flex-row">
      {/* Left Enterprise Branding Column */}
      <div className="lg:w-1/2 bg-gradient-to-br from-slate-900 via-slate-900 to-blue-950 p-8 lg:p-14 flex flex-col justify-between border-b lg:border-b-0 lg:border-r border-slate-800">
        <div>
          <div className="flex items-center gap-3">
            <div className="h-10 w-10 rounded-xl bg-blue-600 flex items-center justify-center shadow-lg shadow-blue-600/30">
              <Wifi className="h-5 w-5 text-white" />
            </div>
            <div>
              <span className="font-bold text-lg tracking-tight text-white">EduBridge WiFi Billing</span>
              <span className="block text-xs text-blue-400 font-medium">Multi-Tenant MikroTik &amp; RADIUS SaaS</span>
            </div>
          </div>

          <div className="mt-12 lg:mt-20 max-w-xl">
            <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-blue-500/10 border border-blue-500/20 text-blue-300 text-xs font-medium mb-5">
              <ShieldCheck className="h-3.5 w-3.5" />
              Server-Enforced Multi-Tenant Isolation
            </div>
            <h1 className="text-3xl lg:text-4xl font-bold tracking-tight text-white leading-tight">
              Operate Hotspot Networks, Dynamic Portals &amp; Automated MikroTik Billing.
            </h1>
            <p className="mt-4 text-slate-400 text-base leading-relaxed">
              Manage WiFi businesses, customize your customer captive portal in real time, and control FreeRADIUS &amp; WireGuard-linked MikroTik routers from one unified control plane.
            </p>
          </div>
        </div>

        <div className="mt-10 pt-6 border-t border-slate-800/80 flex flex-wrap items-center justify-between gap-4 text-xs text-slate-400">
          <span>Infrastructure Contract: FreeRADIUS + WireGuard + PostgreSQL</span>
          <Link
            to="/portal/hotspot?tenantSlug=default-hotspot"
            className="inline-flex items-center gap-1.5 text-blue-400 hover:text-blue-300 font-medium"
          >
            <Globe className="h-3.5 w-3.5" />
            Preview Customer Captive Portal
          </Link>
        </div>
      </div>

      {/* Right Authentication Column */}
      <div className="lg:w-1/2 flex items-center justify-center p-6 sm:p-12">
        <div className="w-full max-w-md">
          <div className="bg-slate-900/90 border border-slate-800 rounded-2xl p-8 shadow-2xl">
            <h2 className="text-2xl font-bold text-white">Sign in to your account</h2>
            <p className="mt-1.5 text-sm text-slate-400">
              Enter your Super Admin or Business Admin credentials.
            </p>

            {errorMessage && (
              <div className="mt-5 p-3.5 rounded-xl bg-red-500/10 border border-red-500/30 flex items-start gap-2.5 text-sm text-red-300">
                <AlertCircle className="h-4 w-4 text-red-400 shrink-0 mt-0.5" />
                <span>{errorMessage}</span>
              </div>
            )}

            <form onSubmit={handleSubmit} className="mt-6 space-y-5">
              <div>
                <label htmlFor="email" className="block text-xs font-semibold uppercase tracking-wider text-slate-300 mb-2">
                  Email Address
                </label>
                <div className="relative">
                  <Mail className="h-4 w-4 text-slate-500 absolute left-3.5 top-1/2 -translate-y-1/2" />
                  <input
                    id="email"
                    type="email"
                    required
                    autoComplete="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="admin@default-hotspot.local"
                    className="w-full pl-10 pr-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                  />
                </div>
              </div>

              <div>
                <label htmlFor="password" className="block text-xs font-semibold uppercase tracking-wider text-slate-300 mb-2">
                  Password
                </label>
                <div className="relative">
                  <Lock className="h-4 w-4 text-slate-500 absolute left-3.5 top-1/2 -translate-y-1/2" />
                  <input
                    id="password"
                    type="password"
                    required
                    autoComplete="current-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="••••••••••••"
                    className="w-full pl-10 pr-4 py-2.5 bg-slate-950 border border-slate-700 rounded-xl text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                  />
                </div>
              </div>

              <button
                type="submit"
                disabled={submitting}
                className="w-full py-2.5 px-4 rounded-xl bg-blue-600 hover:bg-blue-500 disabled:opacity-50 text-white font-semibold text-sm flex items-center justify-center gap-2 transition shadow-lg shadow-blue-600/25"
              >
                {submitting ? 'Authenticating...' : 'Sign In'}
                <ArrowRight className="h-4 w-4" />
              </button>
            </form>

            {/* Local Development Seeded Account Helpers */}
            <div className="mt-7 pt-6 border-t border-slate-800">
              <p className="text-xs font-semibold uppercase tracking-wider text-slate-400 mb-3">
                Local Dev Seeded Accounts (Click to Autofill)
              </p>
              <div className="space-y-2 text-xs">
                <button
                  type="button"
                  onClick={() => fillQuickDemo('superadmin@edubridge.world')}
                  className="w-full text-left px-3 py-2 rounded-lg bg-slate-950 hover:bg-slate-800 border border-slate-800 flex items-center justify-between transition"
                >
                  <span className="font-medium text-slate-200">superadmin@edubridge.world</span>
                  <span className="px-2 py-0.5 rounded bg-purple-500/20 text-purple-300 font-semibold">Super Admin</span>
                </button>
                <button
                  type="button"
                  onClick={() => fillQuickDemo('admin@default-hotspot.local')}
                  className="w-full text-left px-3 py-2 rounded-lg bg-slate-950 hover:bg-slate-800 border border-slate-800 flex items-center justify-between transition"
                >
                  <span className="font-medium text-slate-200">admin@default-hotspot.local</span>
                  <span className="px-2 py-0.5 rounded bg-blue-500/20 text-blue-300 font-semibold">Tenant #1 Admin</span>
                </button>
                <button
                  type="button"
                  onClick={() => fillQuickDemo('admin@tenant2.local')}
                  className="w-full text-left px-3 py-2 rounded-lg bg-slate-950 hover:bg-slate-800 border border-slate-800 flex items-center justify-between transition"
                >
                  <span className="font-medium text-slate-200">admin@tenant2.local</span>
                  <span className="px-2 py-0.5 rounded bg-emerald-500/20 text-emerald-300 font-semibold">Tenant #2 Admin</span>
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
