/**
 * Centralized API Client
 * Reads the backend base URL strictly from VITE_API_BASE_URL environment configuration.
 * Never hardcodes localhost or production URLs in application source code.
 */
const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL;

if (!configuredBaseUrl) {
  throw new Error(
    'VITE_API_BASE_URL environment variable is required. Define it in frontend/.env (see frontend/.env.example).'
  );
}

function resolveApiBaseUrl(rawUrl: string): string {
  const trimmed = rawUrl.replace(/\/+$/, '');
  if (typeof window !== 'undefined') {
    const pageHost = window.location.hostname;
    if (pageHost === '127.0.0.1' && trimmed.includes('://localhost:')) {
      return trimmed.replace('://localhost:', '://127.0.0.1:');
    }
    if (pageHost === 'localhost' && trimmed.includes('://127.0.0.1:')) {
      return trimmed.replace('://127.0.0.1:', '://localhost:');
    }
  }
  return trimmed;
}

export const API_BASE_URL = resolveApiBaseUrl(configuredBaseUrl);

export interface SessionUser {
  userId: number;
  tenantId: number | null;
  email: string;
  fullName: string;
  role: 'super_admin' | 'business_admin' | 'manager' | 'technician' | 'cashier';
  superAdmin: boolean;
  tenantName?: string;
  tenantSlug?: string;
  tenantStatus?: string;
}

export interface TenantSummary {
  id: number;
  businessName: string;
  slug: string;
  status: 'active' | 'suspended' | 'trial';
  contactEmail: string;
  contactPhone: string;
  whatsappSupportNumber: string;
  currencyCode: string;
  timezone: string;
  subscriptionPlan?: string;
  subscriptionStatus?: string;
  maxRouters?: number;
  maxVouchersMonthly?: number;
  userCount: number;
  createdAt: string;
}

export interface PackagePlanItem {
  id: number;
  tenantId: number;
  name: string;
  description: string | null;
  badgeText: string | null;
  price: number;
  currency: string;
  durationSeconds: number;
  rateLimitUpKbps: number;
  rateLimitDownKbps: number;
  simultaneousUse: number;
  dataQuotaBytes: number | null;
  active: boolean;
  portalVisible: boolean;
  sortOrder: number;
}

export interface CaptivePortalConfigResponse {
  branding: {
    tenantId: number;
    tenantSlug: string;
    businessName: string;
    companyName: string | null;
    status: string;
    logoUrl: string | null;
    primaryColor: string;
    secondaryColor: string;
    welcomeTitle: string;
    welcomeMessage: string;
    promotionalBannerText: string | null;
    whatsappSupportNumber: string | null;
    supportPhoneNumber: string | null;
    currencyCode: string;
  };
  paymentOptions: {
    enableMtnMomo: boolean;
    enableAirtelMoney: boolean;
    enableVoucherCode: boolean;
  };
  packages: Array<{
    id: number;
    name: string;
    description: string | null;
    badgeText: string | null;
    price: number;
    currencyCode: string;
    durationSeconds: number;
    downloadKbps: number;
    uploadKbps: number;
    dataQuotaBytes: number | null;
    simultaneousUse: number;
  }>;
  routerCode: string | null;
}

export class ApiError extends Error {
  status: number;
  fieldErrors?: Record<string, string>;

  constructor(message: string, status: number, fieldErrors?: Record<string, string>) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

export async function apiRequest<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      ...(options.headers || {}),
    },
  });

  if (!response.ok) {
    let errorBody: { message?: string; fieldErrors?: Record<string, string> } = {};
    try {
      errorBody = await response.json();
    } catch {
      // fallback if non-JSON error
    }
    throw new ApiError(
      errorBody.message || `Request failed with HTTP ${response.status}`,
      response.status,
      errorBody.fieldErrors
    );
  }

  return response.json() as Promise<T>;
}
