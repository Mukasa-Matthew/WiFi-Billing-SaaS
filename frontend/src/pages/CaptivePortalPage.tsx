import React, { useState } from 'react';
import { useSearchParams, Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Wifi,
  Smartphone,
  Ticket,
  PhoneCall,
  MessageCircle,
  Zap,
  Clock,
  CheckCircle2,
  Sparkles,
  ShieldCheck,
} from 'lucide-react';
import { apiRequest, CaptivePortalConfigResponse } from '../lib/api';

export const CaptivePortalPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const tenantSlug = searchParams.get('tenantSlug') || 'default-hotspot';
  const routerCode = searchParams.get('router') || searchParams.get('routerCode') || '';
  const clientMac = searchParams.get('mac') || '';
  const clientIp = searchParams.get('ip') || '';

  const [selectedPackageId, setSelectedPackageId] = useState<number | null>(null);
  const [voucherCode, setVoucherCode] = useState('');
  const [momoPhone, setMomoPhone] = useState('');
  const [activeMode, setActiveMode] = useState<'buy' | 'voucher'>('buy');

  const { data, isLoading, error } = useQuery<CaptivePortalConfigResponse>({
    queryKey: ['captive-portal', tenantSlug, routerCode],
    queryFn: () => {
      const params = new URLSearchParams();
      if (tenantSlug) params.set('tenantSlug', tenantSlug);
      if (routerCode) params.set('routerCode', routerCode);
      return apiRequest<CaptivePortalConfigResponse>(`/api/v1/public/portal/config?${params.toString()}`);
    },
  });

  if (isLoading) {
    return (
      <div className="min-h-screen bg-slate-950 text-white flex items-center justify-center p-6">
        <div className="text-center space-y-2">
          <Wifi className="h-8 w-8 text-blue-400 animate-pulse mx-auto" />
          <p className="text-sm text-slate-400">Loading WiFi Hotspot Portal...</p>
        </div>
      </div>
    );
  }

  if (error || !data) {
    return (
      <div className="min-h-screen bg-slate-950 text-white flex items-center justify-center p-6">
        <div className="max-w-md w-full bg-slate-900 border border-slate-800 rounded-2xl p-6 text-center">
          <p className="font-bold text-red-400">Hotspot Portal Not Found</p>
          <p className="text-xs text-slate-400 mt-2">
            Could not load portal configuration for tenant &ldquo;{tenantSlug}&rdquo;.
          </p>
        </div>
      </div>
    );
  }

  const { branding, paymentOptions, packages } = data;
  const primaryColor = branding.primaryColor || '#2563eb';

  const formatDuration = (seconds: number) => {
    const hours = Math.round(seconds / 3600);
    if (hours >= 24 && hours % 24 === 0) {
      const days = hours / 24;
      return `${days} ${days === 1 ? 'Day' : 'Days'}`;
    }
    if (hours >= 1) {
      return `${hours} ${hours === 1 ? 'Hour' : 'Hours'}`;
    }
    return `${Math.round(seconds / 60)} Mins`;
  };

  return (
    <div
      className="min-h-screen text-slate-100 flex flex-col justify-between"
      style={{ backgroundColor: branding.secondaryColor || '#0f172a' }}
    >
      {/* Top Promotional Banner (Dynamic from DB) */}
      {branding.promotionalBannerText && (
        <div
          className="py-2 px-4 text-center text-xs font-semibold text-white flex items-center justify-center gap-1.5 shadow"
          style={{ backgroundColor: primaryColor }}
        >
          <Sparkles className="h-3.5 w-3.5 shrink-0" />
          <span>{branding.promotionalBannerText}</span>
        </div>
      )}

      {/* Main Mobile-First Captive Portal Container */}
      <main className="w-full max-w-md mx-auto px-4 py-6 space-y-5">
        {/* Tenant Switcher Bar for Local Testing */}
        <div className="flex items-center justify-between text-[11px] text-slate-400 bg-slate-950/60 border border-slate-800/80 rounded-xl px-3 py-1.5">
          <span>
            Portal Slug: <strong className="text-slate-200">{branding.tenantSlug}</strong>
          </span>
          <div className="flex items-center gap-2">
            <Link to="/portal/hotspot?tenantSlug=default-hotspot" className="hover:text-white underline">
              Tenant #1
            </Link>
            <span>•</span>
            <Link to="/portal/hotspot?tenantSlug=tenant-2" className="hover:text-white underline">
              Tenant #2
            </Link>
            <span>•</span>
            <Link to="/login" className="text-blue-400 hover:text-blue-300 font-medium">
              Admin Login
            </Link>
          </div>
        </div>

        {/* Dynamic Brand Card */}
        <div className="bg-slate-900/95 border border-slate-800 rounded-3xl p-6 shadow-2xl text-center">
          <div
            className="h-14 w-14 rounded-2xl mx-auto flex items-center justify-center text-white shadow-lg"
            style={{ backgroundColor: primaryColor }}
          >
            <Wifi className="h-7 w-7" />
          </div>
          <p className="mt-3 text-xs font-bold uppercase tracking-widest text-slate-400">
            {branding.businessName}
          </p>
          <h1 className="mt-1 text-xl font-extrabold text-white">{branding.welcomeTitle}</h1>
          <p className="mt-1.5 text-xs text-slate-300 leading-relaxed">{branding.welcomeMessage}</p>

          {(clientMac || clientIp) && (
            <div className="mt-3 inline-flex items-center gap-2 px-3 py-1 rounded-full bg-slate-950 border border-slate-800 text-[11px] text-slate-400">
              {clientIp && <span>IP: {clientIp}</span>}
              {clientMac && <span>MAC: {clientMac}</span>}
            </div>
          )}
        </div>

        {/* Mode Toggle: Buy Package vs Voucher Code */}
        <div className="grid grid-cols-2 gap-2 bg-slate-900/90 p-1.5 rounded-2xl border border-slate-800">
          <button
            type="button"
            onClick={() => setActiveMode('buy')}
            className={`py-2.5 rounded-xl text-xs font-bold flex items-center justify-center gap-1.5 transition ${
              activeMode === 'buy' ? 'text-white shadow' : 'text-slate-400 hover:text-white'
            }`}
            style={activeMode === 'buy' ? { backgroundColor: primaryColor } : undefined}
          >
            <Smartphone className="h-4 w-4" />
            Buy WiFi Pass
          </button>
          {paymentOptions.enableVoucherCode && (
            <button
              type="button"
              onClick={() => setActiveMode('voucher')}
              className={`py-2.5 rounded-xl text-xs font-bold flex items-center justify-center gap-1.5 transition ${
                activeMode === 'voucher' ? 'text-white shadow' : 'text-slate-400 hover:text-white'
              }`}
              style={activeMode === 'voucher' ? { backgroundColor: primaryColor } : undefined}
            >
              <Ticket className="h-4 w-4" />
              Have a Voucher?
            </button>
          )}
        </div>

        {/* Mode 1: Dynamic Database-Driven Packages */}
        {activeMode === 'buy' && (
          <div className="space-y-3">
            {packages.map((pkg) => {
              const isSelected = selectedPackageId === pkg.id;
              return (
                <div
                  key={pkg.id}
                  onClick={() => setSelectedPackageId(pkg.id)}
                  className={`cursor-pointer rounded-2xl p-4 border transition bg-slate-900/95 ${
                    isSelected
                      ? 'ring-2 ring-offset-2 ring-offset-slate-950 border-transparent'
                      : 'border-slate-800 hover:border-slate-700'
                  }`}
                  style={isSelected ? { borderColor: primaryColor } : undefined}
                >
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="font-bold text-white text-sm">{pkg.name}</span>
                        {pkg.badgeText && (
                          <span
                            className="px-2 py-0.5 rounded-full text-[10px] font-bold text-white"
                            style={{ backgroundColor: primaryColor }}
                          >
                            {pkg.badgeText}
                          </span>
                        )}
                      </div>
                      {pkg.description && (
                        <p className="text-xs text-slate-400 mt-1">{pkg.description}</p>
                      )}
                      <div className="mt-2.5 flex items-center gap-3 text-[11px] text-slate-300">
                        <span className="inline-flex items-center gap-1">
                          <Clock className="h-3 w-3 text-slate-400" />
                          {formatDuration(pkg.durationSeconds)}
                        </span>
                        <span className="inline-flex items-center gap-1">
                          <Zap className="h-3 w-3 text-amber-400" />
                          {(pkg.downloadKbps / 1024).toFixed(0)} Mbps
                        </span>
                      </div>
                    </div>

                    <div className="text-right shrink-0">
                      <span className="text-[10px] uppercase font-bold text-slate-400 block">
                        {pkg.currencyCode}
                      </span>
                      <span className="text-lg font-extrabold text-white">
                        {Number(pkg.price).toLocaleString()}
                      </span>
                    </div>
                  </div>

                  {isSelected && (
                    <div className="mt-4 pt-3.5 border-t border-slate-800 space-y-3">
                      <label className="block text-xs font-semibold text-slate-300">
                        Enter Mobile Money Number (
                        {[
                          paymentOptions.enableMtnMomo ? 'MTN MoMo' : null,
                          paymentOptions.enableAirtelMoney ? 'Airtel Money' : null,
                        ]
                          .filter(Boolean)
                          .join(' / ')}
                        )
                      </label>
                      <input
                        type="tel"
                        value={momoPhone}
                        onChange={(e) => setMomoPhone(e.target.value)}
                        placeholder="077... or 070..."
                        className="w-full px-3.5 py-2.5 rounded-xl bg-slate-950 border border-slate-700 text-sm text-white"
                      />
                      <button
                        type="button"
                        className="w-full py-2.5 rounded-xl text-white font-bold text-xs flex items-center justify-center gap-1.5 shadow-lg"
                        style={{ backgroundColor: primaryColor }}
                      >
                        <CheckCircle2 className="h-4 w-4" />
                        Pay {pkg.currencyCode} {Number(pkg.price).toLocaleString()} &amp; Connect Instantly
                      </button>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {/* Mode 2: Voucher Redemption */}
        {activeMode === 'voucher' && (
          <div className="bg-slate-900/95 border border-slate-800 rounded-2xl p-5 space-y-4">
            <h2 className="font-bold text-white text-sm">Enter Your WiFi Voucher / PIN Code</h2>
            <input
              value={voucherCode}
              onChange={(e) => setVoucherCode(e.target.value.toUpperCase())}
              placeholder="XXXX-XXXX"
              className="w-full px-4 py-3 rounded-xl bg-slate-950 border border-slate-700 text-center font-mono text-lg tracking-widest text-white uppercase"
            />
            <button
              type="button"
              className="w-full py-3 rounded-xl text-white font-bold text-xs shadow-lg"
              style={{ backgroundColor: primaryColor }}
            >
              Connect to WiFi Now
            </button>
          </div>
        )}

        {/* Dynamic Customer Support Footer */}
        <div className="bg-slate-900/80 border border-slate-800 rounded-2xl p-4 flex items-center justify-between text-xs">
          <div className="text-slate-400">
            <span className="block font-semibold text-slate-200">Need Help Connecting?</span>
            <span>Support: {branding.supportPhoneNumber || branding.whatsappSupportNumber}</span>
          </div>
          <div className="flex items-center gap-2">
            {branding.whatsappSupportNumber && (
              <a
                href={`https://wa.me/${branding.whatsappSupportNumber.replace(/[^0-9]/g, '')}`}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1 px-3 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-semibold"
              >
                <MessageCircle className="h-3.5 w-3.5" />
                WhatsApp
              </a>
            )}
            {branding.supportPhoneNumber && (
              <a
                href={`tel:${branding.supportPhoneNumber}`}
                className="inline-flex items-center gap-1 px-3 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 font-semibold"
              >
                <PhoneCall className="h-3.5 w-3.5" />
                Call
              </a>
            )}
          </div>
        </div>
      </main>

      <footer className="py-4 text-center text-[11px] text-slate-500 flex items-center justify-center gap-1.5">
        <ShieldCheck className="h-3.5 w-3.5" />
        <span>Powered by EduBridge WiFi Billing SaaS</span>
      </footer>
    </div>
  );
};
