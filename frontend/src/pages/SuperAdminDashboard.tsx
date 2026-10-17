import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  Building2,
  Shield,
  Router,
  Package,
  Plus,
  LogOut,
  ExternalLink,
  CheckCircle2,
  PauseCircle,
  Activity,
} from 'lucide-react';
import { apiRequest, TenantSummary } from '../lib/api';
import { useAuth } from '../context/AuthContext';

interface PlatformOverviewResponse {
  totalTenants: number;
  activeTenants: number;
  suspendedTenants: number;
  totalRouters: number;
  totalPackages: number;
  tenants: TenantSummary[];
  recentAuditLogs: Array<{
    id: number;
    tenantId: number | null;
    userId: number | null;
    action: string;
    resourceType: string;
    resourceId: string;
    createdAt: string;
  }>;
}

export const SuperAdminDashboard: React.FC = () => {
  const { user, logout } = useAuth();
  const queryClient = useQueryClient();

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [businessName, setBusinessName] = useState('');
  const [slug, setSlug] = useState('');
  const [contactEmail, setContactEmail] = useState('');
  const [contactPhone, setContactPhone] = useState('+256700000000');
  const [subscriptionTier, setSubscriptionTier] = useState('pro');
  const [adminEmail, setAdminEmail] = useState('');
  const [adminFullName, setAdminFullName] = useState('');
  const [adminPassword, setAdminPassword] = useState('Admin@2026!');
  const [formError, setFormError] = useState<string | null>(null);

  const { data, isLoading } = useQuery<PlatformOverviewResponse>({
    queryKey: ['super-admin', 'overview'],
    queryFn: () => apiRequest<PlatformOverviewResponse>('/api/v1/super-admin/overview'),
  });

  const createTenantMutation = useMutation({
    mutationFn: (payload: Record<string, unknown>) =>
      apiRequest('/api/v1/super-admin/tenants', {
        method: 'POST',
        body: JSON.stringify(payload),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['super-admin', 'overview'] });
      setShowCreateModal(false);
      setBusinessName('');
      setSlug('');
      setContactEmail('');
      setAdminEmail('');
      setAdminFullName('');
      setFormError(null);
    },
    onError: (err: Error) => {
      setFormError(err.message);
    },
  });

  const statusMutation = useMutation({
    mutationFn: ({ tenantId, status }: { tenantId: number; status: string }) =>
      apiRequest(`/api/v1/super-admin/tenants/${tenantId}/status`, {
        method: 'PATCH',
        body: JSON.stringify({ status }),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['super-admin', 'overview'] });
    },
  });

  const handleCreateSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);
    createTenantMutation.mutate({
      businessName,
      slug,
      contactEmail,
      contactPhone,
      whatsappSupportNumber: contactPhone,
      subscriptionTier,
      adminEmail,
      adminFullName,
      adminPassword,
    });
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">
      {/* Top Navigation */}
      <header className="border-b border-slate-800 bg-slate-900/80 backdrop-blur sticky top-0 z-20">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="h-9 w-9 rounded-xl bg-purple-600 flex items-center justify-center">
              <Shield className="h-5 w-5 text-white" />
            </div>
            <div>
              <span className="font-bold text-white">SaaS Super Admin Console</span>
              <span className="hidden sm:inline-block ml-2.5 px-2 py-0.5 text-xs rounded-md bg-purple-500/20 text-purple-300 border border-purple-500/30">
                Platform Operator
              </span>
            </div>
          </div>

          <div className="flex items-center gap-4">
            <span className="text-xs text-slate-400 hidden md:inline">
              Signed in as <strong className="text-slate-200">{user?.email}</strong>
            </span>
            <button
              onClick={() => logout()}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-xs font-medium text-slate-200 transition"
            >
              <LogOut className="h-3.5 w-3.5" />
              Sign Out
            </button>
          </div>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-8">
        {/* Header & Action */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold text-white">WiFi Businesses &amp; Platform Governance</h1>
            <p className="text-sm text-slate-400 mt-1">
              Onboard WiFi operators, manage SaaS subscription tiers, and enforce tenant access status.
            </p>
          </div>
          <button
            onClick={() => setShowCreateModal(true)}
            className="inline-flex items-center gap-2 px-4 py-2.5 rounded-xl bg-blue-600 hover:bg-blue-500 text-white text-sm font-semibold shadow-lg shadow-blue-600/25 transition"
          >
            <Plus className="h-4 w-4" />
            Register WiFi Business
          </button>
        </div>

        {/* KPI Cards */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
            <div className="flex items-center justify-between text-slate-400 text-xs font-semibold uppercase">
              <span>Total WiFi Businesses</span>
              <Building2 className="h-4 w-4 text-blue-400" />
            </div>
            <p className="mt-3 text-3xl font-bold text-white">{isLoading ? '—' : data?.totalTenants ?? 0}</p>
            <p className="mt-1 text-xs text-emerald-400">
              {data?.activeTenants ?? 0} active / {data?.suspendedTenants ?? 0} suspended
            </p>
          </div>

          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
            <div className="flex items-center justify-between text-slate-400 text-xs font-semibold uppercase">
              <span>Provisioned Routers</span>
              <Router className="h-4 w-4 text-emerald-400" />
            </div>
            <p className="mt-3 text-3xl font-bold text-white">{isLoading ? '—' : data?.totalRouters ?? 0}</p>
            <p className="mt-1 text-xs text-slate-400">Across all tenant WireGuard peers</p>
          </div>

          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
            <div className="flex items-center justify-between text-slate-400 text-xs font-semibold uppercase">
              <span>Published Packages</span>
              <Package className="h-4 w-4 text-amber-400" />
            </div>
            <p className="mt-3 text-3xl font-bold text-white">{isLoading ? '—' : data?.totalPackages ?? 0}</p>
            <p className="mt-1 text-xs text-slate-400">Database-driven hotspot plans</p>
          </div>

          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
            <div className="flex items-center justify-between text-slate-400 text-xs font-semibold uppercase">
              <span>Audit Trail Events</span>
              <Activity className="h-4 w-4 text-purple-400" />
            </div>
            <p className="mt-3 text-3xl font-bold text-white">
              {isLoading ? '—' : data?.recentAuditLogs?.length ?? 0}
            </p>
            <p className="mt-1 text-xs text-slate-400">Recent security &amp; lifecycle actions</p>
          </div>
        </div>

        {/* Tenants Table */}
        <div className="bg-slate-900 border border-slate-800 rounded-2xl overflow-hidden">
          <div className="px-6 py-4 border-b border-slate-800 flex items-center justify-between">
            <h2 className="font-semibold text-white">Registered WiFi Businesses (Tenants)</h2>
            <span className="text-xs text-slate-400">Strict server-side tenant_id isolation</span>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse text-sm">
              <thead>
                <tr className="border-b border-slate-800 text-xs uppercase text-slate-400 bg-slate-950/50">
                  <th className="py-3.5 px-6">ID / Business</th>
                  <th className="py-3.5 px-4">Portal Slug</th>
                  <th className="py-3.5 px-4">Tier &amp; Quotas</th>
                  <th className="py-3.5 px-4">Contact</th>
                  <th className="py-3.5 px-4">Status</th>
                  <th className="py-3.5 px-6 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800">
                {data?.tenants.map((t) => (
                  <tr key={t.id} className="hover:bg-slate-800/40 transition">
                    <td className="py-4 px-6">
                      <div className="font-semibold text-white">{t.businessName}</div>
                      <div className="text-xs text-slate-400">Tenant ID #{t.id} • {t.userCount} staff</div>
                    </td>
                    <td className="py-4 px-4">
                      <code className="px-2 py-1 rounded bg-slate-950 border border-slate-800 text-xs text-blue-300">
                        {t.slug}
                      </code>
                    </td>
                    <td className="py-4 px-4">
                      <span className="uppercase text-xs font-bold text-slate-200">
                        {t.subscriptionPlan || 'starter'}
                      </span>
                      <div className="text-xs text-slate-400">
                        Up to {t.maxRouters ?? 5} routers
                      </div>
                    </td>
                    <td className="py-4 px-4 text-xs text-slate-300">
                      <div>{t.contactEmail}</div>
                      <div className="text-slate-400">{t.contactPhone}</div>
                    </td>
                    <td className="py-4 px-4">
                      {t.status === 'active' ? (
                        <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-medium bg-emerald-500/10 text-emerald-300 border border-emerald-500/20">
                          <CheckCircle2 className="h-3.5 w-3.5" />
                          Active
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-medium bg-red-500/10 text-red-300 border border-red-500/20">
                          <PauseCircle className="h-3.5 w-3.5" />
                          {t.status}
                        </span>
                      )}
                    </td>
                    <td className="py-4 px-6 text-right space-x-2">
                      <Link
                        to={`/portal/hotspot?tenantSlug=${encodeURIComponent(t.slug)}`}
                        className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-xs text-blue-300 font-medium"
                      >
                        Portal
                        <ExternalLink className="h-3 w-3" />
                      </Link>
                      <Link
                        to={`/business?tenantId=${t.id}`}
                        className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-blue-600/20 hover:bg-blue-600/30 text-xs text-blue-300 font-medium"
                      >
                        Inspect Console
                      </Link>
                      {t.status === 'active' ? (
                        <button
                          onClick={() => statusMutation.mutate({ tenantId: t.id, status: 'suspended' })}
                          className="px-2.5 py-1.5 rounded-lg bg-red-500/10 hover:bg-red-500/20 text-xs text-red-300 font-medium"
                        >
                          Suspend
                        </button>
                      ) : (
                        <button
                          onClick={() => statusMutation.mutate({ tenantId: t.id, status: 'active' })}
                          className="px-2.5 py-1.5 rounded-lg bg-emerald-500/10 hover:bg-emerald-500/20 text-xs text-emerald-300 font-medium"
                        >
                          Activate
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </main>

      {/* Create Tenant Modal */}
      {showCreateModal && (
        <div className="fixed inset-0 z-50 bg-black/75 flex items-center justify-center p-4">
          <div className="bg-slate-900 border border-slate-800 rounded-2xl max-w-lg w-full p-6 shadow-2xl">
            <h3 className="text-lg font-bold text-white">Register New WiFi Business Tenant</h3>
            <p className="text-xs text-slate-400 mt-1">
              Creates an isolated tenant record, subscription quota, and initial Business Admin account.
            </p>

            {formError && (
              <div className="mt-4 p-3 rounded-lg bg-red-500/10 border border-red-500/30 text-xs text-red-300">
                {formError}
              </div>
            )}

            <form onSubmit={handleCreateSubmit} className="mt-4 space-y-3.5 text-sm">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs text-slate-400 mb-1">Business Name</label>
                  <input
                    required
                    value={businessName}
                    onChange={(e) => {
                      setBusinessName(e.target.value);
                      if (!slug) {
                        setSlug(e.target.value.toLowerCase().replace(/[^a-z0-9]+/g, '-'));
                      }
                    }}
                    placeholder="Victoria Mall WiFi"
                    className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white"
                  />
                </div>
                <div>
                  <label className="block text-xs text-slate-400 mb-1">Portal Slug</label>
                  <input
                    required
                    value={slug}
                    onChange={(e) => setSlug(e.target.value)}
                    placeholder="victoria-mall"
                    className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white"
                  />
                </div>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs text-slate-400 mb-1">Business Contact Email</label>
                  <input
                    type="email"
                    required
                    value={contactEmail}
                    onChange={(e) => setContactEmail(e.target.value)}
                    placeholder="ops@victoriamall.ug"
                    className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white"
                  />
                </div>
                <div>
                  <label className="block text-xs text-slate-400 mb-1">WhatsApp / Support Phone</label>
                  <input
                    value={contactPhone}
                    onChange={(e) => setContactPhone(e.target.value)}
                    className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white"
                  />
                </div>
              </div>

              <div>
                <label className="block text-xs text-slate-400 mb-1">Subscription Tier</label>
                <select
                  value={subscriptionTier}
                  onChange={(e) => setSubscriptionTier(e.target.value)}
                  className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white"
                >
                  <option value="starter">Starter (Up to 5 Routers)</option>
                  <option value="pro">Pro Growth (Up to 15 Routers)</option>
                  <option value="enterprise">Enterprise (Up to 100 Routers)</option>
                </select>
              </div>

              <div className="pt-2 border-t border-slate-800">
                <p className="text-xs font-semibold text-slate-300 mb-2">Initial Business Admin Credentials</p>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <input
                    required
                    value={adminFullName}
                    onChange={(e) => setAdminFullName(e.target.value)}
                    placeholder="Admin Full Name"
                    className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white"
                  />
                  <input
                    type="email"
                    required
                    value={adminEmail}
                    onChange={(e) => setAdminEmail(e.target.value)}
                    placeholder="admin@victoriamall.ug"
                    className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white"
                  />
                </div>
                <div className="mt-2">
                  <input
                    type="password"
                    required
                    value={adminPassword}
                    onChange={(e) => setAdminPassword(e.target.value)}
                    placeholder="Initial Password"
                    className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white"
                  />
                </div>
              </div>

              <div className="flex justify-end gap-2 pt-4">
                <button
                  type="button"
                  onClick={() => setShowCreateModal(false)}
                  className="px-4 py-2 rounded-lg bg-slate-800 text-slate-300 text-xs font-semibold"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={createTenantMutation.isPending}
                  className="px-4 py-2 rounded-lg bg-blue-600 hover:bg-blue-500 text-white text-xs font-semibold"
                >
                  {createTenantMutation.isPending ? 'Creating...' : 'Create Tenant & Admin'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
