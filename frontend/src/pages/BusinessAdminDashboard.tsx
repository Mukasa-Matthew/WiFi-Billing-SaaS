import React, { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useSearchParams, Link } from 'react-router-dom';
import {
  Wifi,
  Palette,
  Package,
  Router,
  Users,
  LogOut,
  ExternalLink,
  Save,
  Plus,
  CheckCircle2,
  Eye,
  EyeOff,
  ArrowLeft,
} from 'lucide-react';
import { apiRequest, PackagePlanItem } from '../lib/api';
import { useAuth } from '../context/AuthContext';

interface TenantDetail {
  id: number;
  name: string;
  slug: string;
  status: string;
  companyName: string | null;
  contactEmail: string;
  contactPhone: string | null;
  currency: string;
  brandingLogoUrl: string | null;
  brandingPrimaryColor: string;
  brandingSecondaryColor: string;
  whatsappPhone: string | null;
  supportPhone: string | null;
  portalWelcomeTitle: string;
  portalWelcomeMessage: string;
  portalPromotionalBanner: string | null;
  portalEnableMtnMomo: boolean;
  portalEnableAirtelMoney: boolean;
  portalEnableVoucherCode: boolean;
}

interface BusinessDashboardResponse {
  tenant: TenantDetail;
  subscription: {
    planTier: string;
    routerLimit: number;
    voucherLimit: number;
    status: string;
  } | null;
  metrics: {
    totalRouters: number;
    totalPackages: number;
    portalVisiblePackages: number;
    totalVouchers: number;
    totalCustomers: number;
  };
  packages: PackagePlanItem[];
  staff: Array<{
    id: number;
    email: string;
    fullName: string;
    role: string;
    status: string;
  }>;
}

export const BusinessAdminDashboard: React.FC = () => {
  const { user, logout } = useAuth();
  const [searchParams] = useSearchParams();
  const queryClient = useQueryClient();

  const requestedTenantId = searchParams.get('tenantId');
  const querySuffix = requestedTenantId ? `?tenantId=${encodeURIComponent(requestedTenantId)}` : '';

  const [activeTab, setActiveTab] = useState<'portal' | 'packages' | 'routers'>('portal');
  const [saveBanner, setSaveBanner] = useState<string | null>(null);

  const { data, isLoading } = useQuery<BusinessDashboardResponse>({
    queryKey: ['business', 'dashboard', requestedTenantId ?? user?.tenantId ?? 'self'],
    queryFn: () => apiRequest<BusinessDashboardResponse>(`/api/v1/business/dashboard${querySuffix}`),
  });

  // Portal Settings Form State
  const [businessName, setBusinessName] = useState('');
  const [logoUrl, setLogoUrl] = useState('');
  const [primaryColor, setPrimaryColor] = useState('#2563eb');
  const [secondaryColor, setSecondaryColor] = useState('#0f172a');
  const [whatsappSupportNumber, setWhatsappSupportNumber] = useState('');
  const [supportPhoneNumber, setSupportPhoneNumber] = useState('');
  const [portalWelcomeTitle, setPortalWelcomeTitle] = useState('');
  const [portalWelcomeMessage, setPortalWelcomeMessage] = useState('');
  const [promotionalBannerText, setPromotionalBannerText] = useState('');
  const [enableMtnMomo, setEnableMtnMomo] = useState(true);
  const [enableAirtelMoney, setEnableAirtelMoney] = useState(true);
  const [enableVoucherCode, setEnableVoucherCode] = useState(true);

  // New Package Form State
  const [pkgName, setPkgName] = useState('');
  const [pkgDesc, setPkgDesc] = useState('');
  const [pkgBadge, setPkgBadge] = useState('Fast');
  const [pkgPrice, setPkgPrice] = useState('2000');
  const [pkgDurationHours, setPkgDurationHours] = useState('12');
  const [pkgDownKbps, setPkgDownKbps] = useState('10240');
  const [pkgUpKbps, setPkgUpKbps] = useState('4096');

  useEffect(() => {
    if (data?.tenant) {
      const t = data.tenant;
      setBusinessName(t.name || '');
      setLogoUrl(t.brandingLogoUrl || '');
      setPrimaryColor(t.brandingPrimaryColor || '#2563eb');
      setSecondaryColor(t.brandingSecondaryColor || '#0f172a');
      setWhatsappSupportNumber(t.whatsappPhone || '');
      setSupportPhoneNumber(t.supportPhone || '');
      setPortalWelcomeTitle(t.portalWelcomeTitle || '');
      setPortalWelcomeMessage(t.portalWelcomeMessage || '');
      setPromotionalBannerText(t.portalPromotionalBanner || '');
      setEnableMtnMomo(t.portalEnableMtnMomo);
      setEnableAirtelMoney(t.portalEnableAirtelMoney);
      setEnableVoucherCode(t.portalEnableVoucherCode);
    }
  }, [data?.tenant]);

  const updatePortalMutation = useMutation({
    mutationFn: (payload: Record<string, unknown>) =>
      apiRequest(`/api/v1/business/portal-settings${querySuffix}`, {
        method: 'PUT',
        body: JSON.stringify(payload),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['business', 'dashboard'] });
      queryClient.invalidateQueries({ queryKey: ['captive-portal'] });
      setSaveBanner('Captive portal preferences saved! Customer portal updated immediately.');
      setTimeout(() => setSaveBanner(null), 4000);
    },
  });

  const createPackageMutation = useMutation({
    mutationFn: (payload: Record<string, unknown>) =>
      apiRequest(`/api/v1/business/packages${querySuffix}`, {
        method: 'POST',
        body: JSON.stringify(payload),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['business', 'dashboard'] });
      setPkgName('');
      setPkgDesc('');
    },
  });

  const togglePackageMutation = useMutation({
    mutationFn: ({ id, active, portalVisible }: { id: number; active: boolean; portalVisible: boolean }) =>
      apiRequest(`/api/v1/business/packages/${id}/toggle${querySuffix}`, {
        method: 'PATCH',
        body: JSON.stringify({ active, portalVisible }),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['business', 'dashboard'] });
    },
  });

  const handlePortalSave = (e: React.FormEvent) => {
    e.preventDefault();
    updatePortalMutation.mutate({
      businessName,
      logoUrl,
      primaryColor,
      secondaryColor,
      whatsappSupportNumber,
      supportPhoneNumber,
      portalWelcomeTitle,
      portalWelcomeMessage,
      promotionalBannerText,
      enableMtnMomo,
      enableAirtelMoney,
      enableVoucherCode,
    });
  };

  const handleCreatePackage = (e: React.FormEvent) => {
    e.preventDefault();
    createPackageMutation.mutate({
      name: pkgName,
      description: pkgDesc,
      badgeText: pkgBadge,
      price: Number(pkgPrice),
      durationSeconds: Number(pkgDurationHours) * 3600,
      downloadKbps: Number(pkgDownKbps),
      uploadKbps: Number(pkgUpKbps),
      dataQuotaBytes: null,
      simultaneousUse: 1,
      active: true,
      portalVisible: true,
      sortOrder: (data?.packages?.length ?? 0) + 1,
    });
  };

  const tenant = data?.tenant;

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">
      {/* Header */}
      <header className="border-b border-slate-800 bg-slate-900/80 backdrop-blur sticky top-0 z-20">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center gap-3">
            {user?.superAdmin && (
              <Link
                to="/super-admin"
                className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-xs text-slate-300"
              >
                <ArrowLeft className="h-3.5 w-3.5" />
                Super Admin
              </Link>
            )}
            <div
              className="h-9 w-9 rounded-xl flex items-center justify-center text-white"
              style={{ backgroundColor: primaryColor || '#2563eb' }}
            >
              <Wifi className="h-5 w-5" />
            </div>
            <div>
              <span className="font-bold text-white">{tenant?.name || 'Business Admin Console'}</span>
              <span className="hidden sm:inline-block ml-2.5 px-2 py-0.5 text-xs rounded-md bg-blue-500/20 text-blue-300 border border-blue-500/30">
                Tenant #{tenant?.id ?? user?.tenantId} • {data?.subscription?.planTier?.toUpperCase() || 'PRO'}
              </span>
            </div>
          </div>

          <div className="flex items-center gap-3">
            {tenant?.slug && (
              <Link
                to={`/portal/hotspot?tenantSlug=${encodeURIComponent(tenant.slug)}`}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-blue-600/20 hover:bg-blue-600/30 border border-blue-500/30 text-xs font-semibold text-blue-300 transition"
              >
                Live Captive Portal
                <ExternalLink className="h-3.5 w-3.5" />
              </Link>
            )}
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

      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-6">
        {/* Metrics Bar */}
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-4">
            <div className="flex items-center justify-between text-xs text-slate-400 font-semibold uppercase">
              <span>Portal Packages</span>
              <Package className="h-4 w-4 text-blue-400" />
            </div>
            <p className="mt-2 text-2xl font-bold text-white">
              {isLoading ? '—' : `${data?.metrics.portalVisiblePackages ?? 0} / ${data?.metrics.totalPackages ?? 0}`}
            </p>
            <p className="text-xs text-slate-400 mt-0.5">Visible on customer portal</p>
          </div>

          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-4">
            <div className="flex items-center justify-between text-xs text-slate-400 font-semibold uppercase">
              <span>Linked MikroTiks</span>
              <Router className="h-4 w-4 text-emerald-400" />
            </div>
            <p className="mt-2 text-2xl font-bold text-white">
              {isLoading ? '—' : `${data?.metrics.totalRouters ?? 0} / ${data?.subscription?.routerLimit ?? 10}`}
            </p>
            <p className="text-xs text-slate-400 mt-0.5">WireGuard + RADIUS peers</p>
          </div>

          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-4">
            <div className="flex items-center justify-between text-xs text-slate-400 font-semibold uppercase">
              <span>Active Vouchers</span>
              <CheckCircle2 className="h-4 w-4 text-amber-400" />
            </div>
            <p className="mt-2 text-2xl font-bold text-white">{isLoading ? '—' : data?.metrics.totalVouchers ?? 0}</p>
            <p className="text-xs text-slate-400 mt-0.5">Synced to FreeRADIUS</p>
          </div>

          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-4">
            <div className="flex items-center justify-between text-xs text-slate-400 font-semibold uppercase">
              <span>Staff Accounts</span>
              <Users className="h-4 w-4 text-purple-400" />
            </div>
            <p className="mt-2 text-2xl font-bold text-white">{isLoading ? '—' : data?.staff.length ?? 0}</p>
            <p className="text-xs text-slate-400 mt-0.5">Role-scoped team members</p>
          </div>
        </div>

        {/* Navigation Tabs */}
        <div className="flex items-center gap-2 border-b border-slate-800 pb-3">
          <button
            onClick={() => setActiveTab('portal')}
            className={`inline-flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-semibold transition ${
              activeTab === 'portal'
                ? 'bg-blue-600 text-white shadow-lg shadow-blue-600/20'
                : 'bg-slate-900 text-slate-400 hover:text-white'
            }`}
          >
            <Palette className="h-4 w-4" />
            Dynamic Captive Portal Preferences
          </button>
          <button
            onClick={() => setActiveTab('packages')}
            className={`inline-flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-semibold transition ${
              activeTab === 'packages'
                ? 'bg-blue-600 text-white shadow-lg shadow-blue-600/20'
                : 'bg-slate-900 text-slate-400 hover:text-white'
            }`}
          >
            <Package className="h-4 w-4" />
            Internet Packages ({data?.packages.length ?? 0})
          </button>
          <button
            onClick={() => setActiveTab('routers')}
            className={`inline-flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-semibold transition ${
              activeTab === 'routers'
                ? 'bg-blue-600 text-white shadow-lg shadow-blue-600/20'
                : 'bg-slate-900 text-slate-400 hover:text-white'
            }`}
          >
            <Router className="h-4 w-4" />
            MikroTik Routers
          </button>
        </div>

        {saveBanner && (
          <div className="p-4 rounded-xl bg-emerald-500/10 border border-emerald-500/30 text-sm text-emerald-300 flex items-center gap-2">
            <CheckCircle2 className="h-4 w-4 shrink-0" />
            <span>{saveBanner}</span>
          </div>
        )}

        {/* TAB 1: Dynamic Captive Portal Preferences */}
        {activeTab === 'portal' && (
          <form onSubmit={handlePortalSave} className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-6">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-slate-800 pb-4">
              <div>
                <h2 className="text-lg font-bold text-white">Customer Captive Portal Customization</h2>
                <p className="text-xs text-slate-400 mt-1">
                  All changes update the centrally hosted portal immediately without rerunning the MikroTik onboarding script.
                </p>
              </div>
              <button
                type="submit"
                disabled={updatePortalMutation.isPending}
                className="inline-flex items-center gap-2 px-4 py-2.5 rounded-xl bg-blue-600 hover:bg-blue-500 text-white text-sm font-semibold shadow-lg shadow-blue-600/25 transition"
              >
                <Save className="h-4 w-4" />
                {updatePortalMutation.isPending ? 'Saving...' : 'Save Portal Preferences'}
              </button>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-5 text-sm">
              <div>
                <label className="block text-xs font-semibold uppercase text-slate-400 mb-1.5">
                  Displayed Business Name
                </label>
                <input
                  required
                  value={businessName}
                  onChange={(e) => setBusinessName(e.target.value)}
                  className="w-full px-3.5 py-2.5 rounded-xl bg-slate-950 border border-slate-700 text-white"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold uppercase text-slate-400 mb-1.5">
                  Logo URL (Optional)
                </label>
                <input
                  value={logoUrl}
                  onChange={(e) => setLogoUrl(e.target.value)}
                  placeholder="https://example.com/logo.png"
                  className="w-full px-3.5 py-2.5 rounded-xl bg-slate-950 border border-slate-700 text-white"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold uppercase text-slate-400 mb-1.5">
                  Brand Primary Color (Hex)
                </label>
                <div className="flex items-center gap-3">
                  <input
                    type="color"
                    value={primaryColor}
                    onChange={(e) => setPrimaryColor(e.target.value)}
                    className="h-10 w-14 rounded bg-slate-950 border border-slate-700 cursor-pointer"
                  />
                  <input
                    value={primaryColor}
                    onChange={(e) => setPrimaryColor(e.target.value)}
                    className="flex-1 px-3.5 py-2.5 rounded-xl bg-slate-950 border border-slate-700 text-white font-mono"
                  />
                </div>
              </div>

              <div>
                <label className="block text-xs font-semibold uppercase text-slate-400 mb-1.5">
                  Secondary Surface Color (Hex)
                </label>
                <div className="flex items-center gap-3">
                  <input
                    type="color"
                    value={secondaryColor}
                    onChange={(e) => setSecondaryColor(e.target.value)}
                    className="h-10 w-14 rounded bg-slate-950 border border-slate-700 cursor-pointer"
                  />
                  <input
                    value={secondaryColor}
                    onChange={(e) => setSecondaryColor(e.target.value)}
                    className="flex-1 px-3.5 py-2.5 rounded-xl bg-slate-950 border border-slate-700 text-white font-mono"
                  />
                </div>
              </div>

              <div>
                <label className="block text-xs font-semibold uppercase text-slate-400 mb-1.5">
                  WhatsApp Customer Support Number
                </label>
                <input
                  value={whatsappSupportNumber}
                  onChange={(e) => setWhatsappSupportNumber(e.target.value)}
                  placeholder="+256700100100"
                  className="w-full px-3.5 py-2.5 rounded-xl bg-slate-950 border border-slate-700 text-white"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold uppercase text-slate-400 mb-1.5">
                  Voice Support Phone Number
                </label>
                <input
                  value={supportPhoneNumber}
                  onChange={(e) => setSupportPhoneNumber(e.target.value)}
                  placeholder="+256700100100"
                  className="w-full px-3.5 py-2.5 rounded-xl bg-slate-950 border border-slate-700 text-white"
                />
              </div>

              <div className="md:col-span-2">
                <label className="block text-xs font-semibold uppercase text-slate-400 mb-1.5">
                  Portal Welcome Headline
                </label>
                <input
                  required
                  value={portalWelcomeTitle}
                  onChange={(e) => setPortalWelcomeTitle(e.target.value)}
                  className="w-full px-3.5 py-2.5 rounded-xl bg-slate-950 border border-slate-700 text-white"
                />
              </div>

              <div className="md:col-span-2">
                <label className="block text-xs font-semibold uppercase text-slate-400 mb-1.5">
                  Portal Welcome Subtitle / Instructions
                </label>
                <textarea
                  rows={2}
                  value={portalWelcomeMessage}
                  onChange={(e) => setPortalWelcomeMessage(e.target.value)}
                  className="w-full px-3.5 py-2.5 rounded-xl bg-slate-950 border border-slate-700 text-white"
                />
              </div>

              <div className="md:col-span-2">
                <label className="block text-xs font-semibold uppercase text-slate-400 mb-1.5">
                  Promotional Banner Text
                </label>
                <input
                  value={promotionalBannerText}
                  onChange={(e) => setPromotionalBannerText(e.target.value)}
                  placeholder="Special Weekend Offer: 24 Hours Unlimited for UGX 2,500!"
                  className="w-full px-3.5 py-2.5 rounded-xl bg-slate-950 border border-slate-700 text-white"
                />
              </div>
            </div>

            <div className="pt-4 border-t border-slate-800">
              <p className="text-xs font-semibold uppercase text-slate-400 mb-3">
                Enabled Customer Payment &amp; Redemption Methods
              </p>
              <div className="flex flex-wrap gap-6 text-sm">
                <label className="inline-flex items-center gap-2.5 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={enableMtnMomo}
                    onChange={(e) => setEnableMtnMomo(e.target.checked)}
                    className="h-4 w-4 rounded border-slate-700 text-blue-600"
                  />
                  <span>MTN Mobile Money</span>
                </label>
                <label className="inline-flex items-center gap-2.5 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={enableAirtelMoney}
                    onChange={(e) => setEnableAirtelMoney(e.target.checked)}
                    className="h-4 w-4 rounded border-slate-700 text-blue-600"
                  />
                  <span>Airtel Money</span>
                </label>
                <label className="inline-flex items-center gap-2.5 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={enableVoucherCode}
                    onChange={(e) => setEnableVoucherCode(e.target.checked)}
                    className="h-4 w-4 rounded border-slate-700 text-blue-600"
                  />
                  <span>8-Digit Voucher / PIN Redemption</span>
                </label>
              </div>
            </div>
          </form>
        )}

        {/* TAB 2: Internet Packages */}
        {activeTab === 'packages' && (
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
            <div className="lg:col-span-2 bg-slate-900 border border-slate-800 rounded-2xl overflow-hidden">
              <div className="px-6 py-4 border-b border-slate-800 flex items-center justify-between">
                <h2 className="font-bold text-white">Configured Hotspot Packages</h2>
                <span className="text-xs text-slate-400">Only Active + Visible packages appear on the portal</span>
              </div>
              <div className="divide-y divide-slate-800">
                {data?.packages.map((pkg) => (
                  <div key={pkg.id} className="p-5 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="font-bold text-white">{pkg.name}</span>
                        {pkg.badgeText && (
                          <span className="px-2 py-0.5 text-xs rounded-full bg-blue-500/20 text-blue-300 font-medium">
                            {pkg.badgeText}
                          </span>
                        )}
                      </div>
                      <p className="text-xs text-slate-400 mt-1">{pkg.description}</p>
                      <div className="mt-2 flex flex-wrap gap-3 text-xs text-slate-300">
                        <span>
                          Price: <strong>{pkg.currency} {pkg.price.toLocaleString()}</strong>
                        </span>
                        <span>• Duration: {Math.round(pkg.durationSeconds / 60)} mins</span>
                        <span>
                          • Speed: {(pkg.rateLimitDownKbps / 1024).toFixed(1)}M / {(pkg.rateLimitUpKbps / 1024).toFixed(1)}M
                        </span>
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      <button
                        onClick={() =>
                          togglePackageMutation.mutate({
                            id: pkg.id,
                            active: pkg.active,
                            portalVisible: !pkg.portalVisible,
                          })
                        }
                        className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold border transition ${
                          pkg.portalVisible
                            ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-300'
                            : 'bg-slate-800 border-slate-700 text-slate-400'
                        }`}
                      >
                        {pkg.portalVisible ? <Eye className="h-3.5 w-3.5" /> : <EyeOff className="h-3.5 w-3.5" />}
                        {pkg.portalVisible ? 'Portal Visible' : 'Hidden'}
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            </div>

            {/* Add Package Form */}
            <form
              onSubmit={handleCreatePackage}
              className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-4 h-fit"
            >
              <h3 className="font-bold text-white flex items-center gap-2">
                <Plus className="h-4 w-4 text-blue-400" />
                Create Internet Package
              </h3>
              <div className="space-y-3 text-sm">
                <div>
                  <label className="block text-xs text-slate-400 mb-1">Package Name</label>
                  <input
                    required
                    value={pkgName}
                    onChange={(e) => setPkgName(e.target.value)}
                    placeholder="Weekly Unlimited Pass"
                    className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white"
                  />
                </div>
                <div>
                  <label className="block text-xs text-slate-400 mb-1">Description</label>
                  <input
                    value={pkgDesc}
                    onChange={(e) => setPkgDesc(e.target.value)}
                    placeholder="7 days high-speed access"
                    className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white"
                  />
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-xs text-slate-400 mb-1">Price ({tenant?.currency || 'UGX'})</label>
                    <input
                      type="number"
                      required
                      value={pkgPrice}
                      onChange={(e) => setPkgPrice(e.target.value)}
                      className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white"
                    />
                  </div>
                  <div>
                    <label className="block text-xs text-slate-400 mb-1">Duration (Hours)</label>
                    <input
                      type="number"
                      required
                      value={pkgDurationHours}
                      onChange={(e) => setPkgDurationHours(e.target.value)}
                      className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white"
                    />
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-xs text-slate-400 mb-1">Download (Kbps)</label>
                    <input
                      type="number"
                      required
                      value={pkgDownKbps}
                      onChange={(e) => setPkgDownKbps(e.target.value)}
                      className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white"
                    />
                  </div>
                  <div>
                    <label className="block text-xs text-slate-400 mb-1">Upload (Kbps)</label>
                    <input
                      type="number"
                      required
                      value={pkgUpKbps}
                      onChange={(e) => setPkgUpKbps(e.target.value)}
                      className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white"
                    />
                  </div>
                </div>
                <div>
                  <label className="block text-xs text-slate-400 mb-1">Badge Label</label>
                  <input
                    value={pkgBadge}
                    onChange={(e) => setPkgBadge(e.target.value)}
                    placeholder="Best Value"
                    className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-700 text-white"
                  />
                </div>
                <button
                  type="submit"
                  disabled={createPackageMutation.isPending}
                  className="w-full py-2.5 rounded-xl bg-blue-600 hover:bg-blue-500 text-white font-semibold text-xs transition"
                >
                  {createPackageMutation.isPending ? 'Saving...' : 'Publish Package to Portal'}
                </button>
              </div>
            </form>
          </div>
        )}

        {/* TAB 3: MikroTik Routers */}
        {activeTab === 'routers' && (
          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6">
            <h2 className="text-lg font-bold text-white">MikroTik Router Onboarding &amp; Telemetry</h2>
            <p className="text-sm text-slate-400 mt-1">
              Connected to the existing <code className="text-blue-300">cloudserv</code> WireGuard &amp; FreeRADIUS provisioning contract (<code className="text-blue-300">/api/v1/admin/routers/create-token</code>). Full interactive one-line terminal command generation (<strong className="text-slate-200">Link a MikroTik → Generate Command → Copy Command</strong>) is scheduled for Milestone 3.
            </p>
          </div>
        )}
      </main>
    </div>
  );
};
