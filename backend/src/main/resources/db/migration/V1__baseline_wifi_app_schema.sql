-- ==============================================================================
-- Flyway V1: Authoritative Baseline Schema for wifi_app (18 Tables, 5 Views)
-- Fully idempotent (CREATE TABLE IF NOT EXISTS / ADD COLUMN IF NOT EXISTS) so it
-- runs safely on both a fresh local development DB and existing cloudserv DBs.
-- ==============================================================================

-- 1. Businesses / Tenants
CREATE TABLE IF NOT EXISTS public.tenants (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'trial', 'canceled')),
    company_name VARCHAR(255),
    contact_name VARCHAR(255),
    contact_email VARCHAR(255) NOT NULL,
    contact_phone VARCHAR(50),
    address TEXT,
    currency VARCHAR(10) DEFAULT 'UGX',
    timezone VARCHAR(50) DEFAULT 'Africa/Kampala',
    branding_logo_url TEXT,
    branding_primary_color VARCHAR(10) DEFAULT '#0d6efd',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tenants_slug ON public.tenants(slug);

-- 2. SaaS Tenant Subscriptions
CREATE TABLE IF NOT EXISTS public.tenant_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    plan_tier VARCHAR(50) NOT NULL DEFAULT 'starter' CHECK (plan_tier IN ('starter', 'pro', 'enterprise')),
    router_limit INT NOT NULL DEFAULT 5,
    voucher_limit INT NOT NULL DEFAULT 5000,
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'past_due', 'canceled', 'trial')),
    billing_cycle VARCHAR(20) NOT NULL DEFAULT 'monthly' CHECK (billing_cycle IN ('monthly', 'annual')),
    current_period_start TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    current_period_end TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '1 month'),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 3. Users, Administrators & Staff
CREATE TABLE IF NOT EXISTS public.users (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT REFERENCES public.tenants(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    phone VARCHAR(50),
    role VARCHAR(30) NOT NULL CHECK (role IN ('super_admin', 'business_admin', 'manager', 'cashier', 'technician')),
    status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'disabled', 'suspended')),
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_tenant_role ON public.users(tenant_id, role);

-- 4. Administration Audit Logs
CREATE TABLE IF NOT EXISTS public.audit_logs (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT REFERENCES public.tenants(id) ON DELETE SET NULL,
    user_id BIGINT REFERENCES public.users(id) ON DELETE SET NULL,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id VARCHAR(100),
    details JSONB,
    ip_address INET,
    user_agent TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_tenant_date ON public.audit_logs(tenant_id, created_at);

-- 5. Customers Directory
CREATE TABLE IF NOT EXISTS public.customers (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    phone VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    national_id_or_doc VARCHAR(100),
    status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'inactive')),
    notes TEXT,
    tags TEXT[],
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (tenant_id, phone)
);

CREATE INDEX IF NOT EXISTS idx_customers_tenant_phone ON public.customers(tenant_id, phone);
CREATE INDEX IF NOT EXISTS idx_customers_status ON public.customers(tenant_id, status);

-- 6. Router Physical Sites / Branches
CREATE TABLE IF NOT EXISTS public.router_sites (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    location_address TEXT,
    gps_latitude NUMERIC(10, 8),
    gps_longitude NUMERIC(11, 8),
    contact_person VARCHAR(255),
    contact_phone VARCHAR(50),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 7. Router Inventory & Onboarding Lifecycle
CREATE TABLE IF NOT EXISTS public.routers (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    site_id BIGINT REFERENCES public.router_sites(id) ON DELETE SET NULL,
    name VARCHAR(255) NOT NULL,
    ip_address INET NOT NULL UNIQUE,
    mac_address MACADDR,
    model VARCHAR(100),
    firmware_version VARCHAR(50),
    radius_secret VARCHAR(128) NOT NULL,
    shortname VARCHAR(64) NOT NULL UNIQUE,
    coa_port INT DEFAULT 3799,
    api_username VARCHAR(100) DEFAULT 'admin',
    api_password_encrypted TEXT,
    api_port INT DEFAULT 8728,
    status VARCHAR(20) DEFAULT 'pending_setup' CHECK (status IN (
        'pending_setup', 'provisioning', 'connected', 'active', 'offline',
        'failed', 'suspended', 'maintenance', 'revoked', 'decommissioned'
    )),
    last_heartbeat_at TIMESTAMPTZ,
    health_status VARCHAR(20) DEFAULT 'unknown',
    cpu_load INT,
    free_memory_mb BIGINT,
    total_memory_mb BIGINT,
    active_hotspot_users INT,
    wg_public_key VARCHAR(64),
    hotspot_mode VARCHAR(20) NOT NULL DEFAULT 'existing' CHECK (hotspot_mode IN ('existing', 'new')),
    hotspot_profile VARCHAR(100) NOT NULL DEFAULT 'default',
    hotspot_interface VARCHAR(100),
    hotspot_pool_cidr VARCHAR(32) NOT NULL DEFAULT '10.5.50.1/24',
    hotspot_dns_name VARCHAR(255) NOT NULL DEFAULT 'wifi.login',
    allow_provider_migration BOOLEAN NOT NULL DEFAULT FALSE,
    heartbeat_token VARCHAR(64),
    last_wg_handshake_at TIMESTAMPTZ,
    vpn_verified_at TIMESTAMPTZ,
    radius_verified_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_routers_tenant ON public.routers(tenant_id, status);

-- 8. Router Enrollment Tokens
CREATE TABLE IF NOT EXISTS public.router_enrollment_tokens (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    router_id BIGINT NOT NULL REFERENCES public.routers(id) ON DELETE CASCADE,
    token VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'provisioning', 'used', 'expired', 'revoked', 'failed')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    used_at TIMESTAMPTZ,
    created_by_user_id BIGINT REFERENCES public.users(id) ON DELETE SET NULL,
    script_fetched_at TIMESTAMPTZ,
    pubkey_registered_at TIMESTAMPTZ,
    verified_at TIMESTAMPTZ,
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_router_enrollment_token ON public.router_enrollment_tokens(token);

-- 9. Internet Packages (Product Catalog)
CREATE TABLE IF NOT EXISTS public.packages (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    price NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
    currency VARCHAR(10) NOT NULL DEFAULT 'UGX',
    duration_seconds INT NOT NULL DEFAULT 3600,
    rate_limit_up_kbps INT NOT NULL DEFAULT 2048,
    rate_limit_down_kbps INT NOT NULL DEFAULT 5120,
    simultaneous_use INT NOT NULL DEFAULT 1,
    session_timeout INT NOT NULL DEFAULT 3600,
    interim_interval INT NOT NULL DEFAULT 300,
    data_quota_bytes BIGINT,
    is_free_trial BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_packages_tenant ON public.packages(tenant_id, is_active);

-- 10. Voucher Batches
CREATE TABLE IF NOT EXISTS public.voucher_batches (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    package_id BIGINT NOT NULL REFERENCES public.packages(id) ON DELETE RESTRICT,
    batch_code VARCHAR(64) NOT NULL UNIQUE,
    quantity INT NOT NULL,
    generated_by BIGINT REFERENCES public.users(id) ON DELETE SET NULL,
    note VARCHAR(255),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 11. Vouchers
CREATE TABLE IF NOT EXISTS public.vouchers (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    package_id BIGINT NOT NULL REFERENCES public.packages(id) ON DELETE RESTRICT,
    batch_id BIGINT REFERENCES public.voucher_batches(id) ON DELETE SET NULL,
    customer_id BIGINT REFERENCES public.customers(id) ON DELETE SET NULL,
    code VARCHAR(64) NOT NULL,
    status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'used', 'expired', 'disabled', 'suspended')),
    first_used_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    total_time_used BIGINT DEFAULT 0,
    total_bytes_in BIGINT DEFAULT 0,
    total_bytes_out BIGINT DEFAULT 0,
    data_quota_bytes BIGINT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (tenant_id, code)
);

CREATE INDEX IF NOT EXISTS idx_vouchers_tenant_code ON public.vouchers(tenant_id, code);
CREATE INDEX IF NOT EXISTS idx_vouchers_customer ON public.vouchers(customer_id);

-- 12. Plan Cycles
CREATE TABLE IF NOT EXISTS public.plan_cycles (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    customer_id BIGINT NOT NULL REFERENCES public.customers(id) ON DELETE CASCADE,
    package_id BIGINT NOT NULL REFERENCES public.packages(id) ON DELETE RESTRICT,
    voucher_id BIGINT REFERENCES public.vouchers(id) ON DELETE SET NULL,
    started_at TIMESTAMPTZ DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    total_data_used_bytes BIGINT DEFAULT 0,
    total_time_used_seconds BIGINT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'expired', 'canceled')),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_plan_cycles_customer ON public.plan_cycles(customer_id, status);

-- 13. Payments
CREATE TABLE IF NOT EXISTS public.payments (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    customer_id BIGINT REFERENCES public.customers(id) ON DELETE SET NULL,
    package_id BIGINT REFERENCES public.packages(id) ON DELETE SET NULL,
    voucher_id BIGINT REFERENCES public.vouchers(id) ON DELETE SET NULL,
    amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(10) DEFAULT 'UGX',
    payment_method VARCHAR(50) NOT NULL CHECK (payment_method IN ('mpesa', 'mtn_momo', 'airtel_money', 'cash', 'card', 'bank', 'free_trial')),
    transaction_ref VARCHAR(100) UNIQUE,
    phone_number VARCHAR(50),
    status VARCHAR(20) DEFAULT 'completed' CHECK (status IN ('pending', 'completed', 'failed', 'refunded')),
    gateway_response JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payments_tenant_date ON public.payments(tenant_id, created_at);

-- 14. Expenses
CREATE TABLE IF NOT EXISTS public.expenses (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    category VARCHAR(100) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(10) DEFAULT 'UGX',
    description TEXT,
    receipt_url TEXT,
    expense_date DATE NOT NULL DEFAULT CURRENT_DATE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_expenses_tenant_date ON public.expenses(tenant_id, expense_date);

-- 15. Blocked Devices
CREATE TABLE IF NOT EXISTS public.blocked_devices (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    mac_address MACADDR NOT NULL,
    reason VARCHAR(255),
    status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'revoked')),
    blocked_by VARCHAR(100) DEFAULT 'admin',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (tenant_id, mac_address)
);

CREATE INDEX IF NOT EXISTS idx_blocked_devices_tenant_mac ON public.blocked_devices(tenant_id, mac_address, status);

-- 16. Router IP Bindings
CREATE TABLE IF NOT EXISTS public.router_ip_bindings (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    router_id BIGINT NOT NULL REFERENCES public.routers(id) ON DELETE CASCADE,
    mac_address MACADDR NOT NULL,
    ip_address INET,
    binding_type VARCHAR(20) NOT NULL CHECK (binding_type IN ('bypassed', 'blocked', 'regular')),
    comment VARCHAR(255),
    sync_status VARCHAR(20) DEFAULT 'pending' CHECK (sync_status IN ('pending', 'synced', 'failed', 'removed')),
    last_synced_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (router_id, mac_address)
);

CREATE INDEX IF NOT EXISTS idx_router_ip_bindings_sync ON public.router_ip_bindings(router_id, sync_status);

-- 17. Customer Feedback
CREATE TABLE IF NOT EXISTS public.customer_feedback (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    customer_id BIGINT REFERENCES public.customers(id) ON DELETE SET NULL,
    router_id BIGINT REFERENCES public.routers(id) ON DELETE SET NULL,
    rating INT CHECK (rating BETWEEN 1 AND 5),
    comment TEXT,
    status VARCHAR(20) DEFAULT 'new' CHECK (status IN ('new', 'reviewed', 'resolved')),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 18. Router Heartbeats
CREATE TABLE IF NOT EXISTS public.router_heartbeats (
    id BIGSERIAL PRIMARY KEY,
    router_id BIGINT NOT NULL REFERENCES public.routers(id) ON DELETE CASCADE,
    cpu_load INT,
    free_memory_mb BIGINT,
    total_memory_mb BIGINT,
    active_hotspot_users INT,
    vpn_status VARCHAR(20),
    vpn_ping_ms INT,
    uptime_seconds BIGINT,
    recorded_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_heartbeats_router_time ON public.router_heartbeats(router_id, recorded_at DESC);

-- ==============================================================================
-- Authoritative Analytics & Health Views (5 Views)
-- ==============================================================================

CREATE OR REPLACE VIEW public.v_customer_analytics AS
SELECT 
    c.id AS customer_id,
    c.tenant_id,
    t.name AS tenant_name,
    c.name AS customer_name,
    c.phone,
    c.email,
    c.status AS customer_status,
    count(DISTINCT p.id) AS total_purchases,
    COALESCE(sum(p.amount), 0.00) AS total_spend,
    count(DISTINCT v.id) AS total_vouchers_used,
    round(COALESCE(sum(v.total_bytes_in + v.total_bytes_out) / (1024.0 * 1024.0), 0), 2) AS total_data_consumed_mb,
    round(COALESCE(sum(v.total_time_used) / 60.0, 0), 1) AS total_minutes_online,
    max(v.first_used_at) AS last_active_at,
    c.created_at AS customer_since
FROM public.customers c
JOIN public.tenants t ON t.id = c.tenant_id
LEFT JOIN public.payments p ON p.customer_id = c.id AND p.status = 'completed'
LEFT JOIN public.vouchers v ON v.customer_id = c.id
GROUP BY c.id, c.tenant_id, t.name, c.name, c.phone, c.email, c.status, c.created_at;

CREATE OR REPLACE VIEW public.v_expired_users AS
SELECT 
    v.id AS voucher_id,
    v.tenant_id,
    t.name AS tenant_name,
    v.code AS voucher_code,
    c.name AS customer_name,
    c.phone AS customer_phone,
    pkg.name AS package_name,
    v.status,
    v.first_used_at,
    v.expires_at,
    round(v.total_time_used / 60.0, 1) AS minutes_used,
    round((v.total_bytes_in + v.total_bytes_out) / (1024.0 * 1024.0), 2) AS total_mb_used,
    CASE
        WHEN v.expires_at <= NOW() THEN 'Wall-clock time elapsed'
        WHEN pkg.duration_seconds > 0 AND v.total_time_used >= pkg.duration_seconds THEN 'Duration quota reached'
        WHEN (v.total_bytes_in + v.total_bytes_out) >= COALESCE(v.data_quota_bytes, pkg.data_quota_bytes) THEN 'Data quota exhausted'
        ELSE 'Status ' || v.status
    END AS expiration_reason
FROM public.vouchers v
JOIN public.tenants t ON t.id = v.tenant_id
JOIN public.packages pkg ON pkg.id = v.package_id
LEFT JOIN public.customers c ON c.id = v.customer_id
WHERE v.status IN ('expired', 'used') OR (v.expires_at IS NOT NULL AND v.expires_at <= NOW());

CREATE OR REPLACE VIEW public.v_financial_analytics AS
WITH monthly_revenue AS (
    SELECT 
        tenant_id,
        to_char(created_at, 'YYYY-MM') AS month_year,
        count(id) AS transaction_count,
        sum(amount) AS total_revenue
    FROM public.payments
    WHERE status = 'completed'
    GROUP BY tenant_id, to_char(created_at, 'YYYY-MM')
),
monthly_expenses AS (
    SELECT 
        tenant_id,
        to_char(expense_date::TIMESTAMPTZ, 'YYYY-MM') AS month_year,
        count(id) AS expense_count,
        sum(amount) AS total_expenses
    FROM public.expenses
    GROUP BY tenant_id, to_char(expense_date::TIMESTAMPTZ, 'YYYY-MM')
)
SELECT 
    COALESCE(r.tenant_id, e.tenant_id) AS tenant_id,
    t.name AS tenant_name,
    COALESCE(r.month_year, e.month_year) AS month_year,
    COALESCE(r.transaction_count, 0) AS total_transactions,
    COALESCE(r.total_revenue, 0.00) AS total_revenue,
    COALESCE(e.expense_count, 0) AS total_expenses_count,
    COALESCE(e.total_expenses, 0.00) AS total_expenses,
    (COALESCE(r.total_revenue, 0.00) - COALESCE(e.total_expenses, 0.00)) AS net_profit
FROM monthly_revenue r
FULL OUTER JOIN monthly_expenses e ON r.tenant_id = e.tenant_id AND r.month_year = e.month_year
JOIN public.tenants t ON t.id = COALESCE(r.tenant_id, e.tenant_id);

CREATE OR REPLACE VIEW public.v_revenue_history AS
SELECT 
    p.id AS payment_id,
    p.tenant_id,
    t.name AS tenant_name,
    p.transaction_ref,
    p.payment_method,
    p.amount,
    p.currency,
    p.phone_number,
    c.name AS customer_name,
    pkg.name AS package_name,
    v.code AS voucher_code,
    p.status AS payment_status,
    p.created_at AS payment_date
FROM public.payments p
JOIN public.tenants t ON t.id = p.tenant_id
LEFT JOIN public.customers c ON c.id = p.customer_id
LEFT JOIN public.packages pkg ON pkg.id = p.package_id
LEFT JOIN public.vouchers v ON v.id = p.voucher_id;

CREATE OR REPLACE VIEW public.v_router_health AS
SELECT 
    r.id AS router_id,
    r.tenant_id,
    t.name AS tenant_name,
    r.name AS router_name,
    host(r.ip_address) AS router_ip,
    r.shortname,
    r.status AS lifecycle_status,
    r.hotspot_mode,
    r.hotspot_profile,
    r.hotspot_interface,
    r.model,
    r.firmware_version,
    r.wg_public_key IS NOT NULL AS has_wg_pubkey,
    r.vpn_verified_at,
    r.radius_verified_at,
    r.last_wg_handshake_at,
    r.last_heartbeat_at,
    r.last_error,
    CASE 
        WHEN r.status IN ('revoked', 'decommissioned') THEN 'DECOMMISSIONED'
        WHEN r.status = 'failed' THEN 'FAILED'
        WHEN r.status = 'pending_setup' THEN 'PENDING_SETUP'
        WHEN r.status = 'provisioning' THEN 'PROVISIONING'
        WHEN (r.last_heartbeat_at IS NOT NULL AND r.last_heartbeat_at >= NOW() - INTERVAL '3 minutes')
          OR (r.last_wg_handshake_at IS NOT NULL AND r.last_wg_handshake_at >= NOW() - INTERVAL '3 minutes') THEN 'ONLINE'
        ELSE 'OFFLINE'
    END AS vpn_connectivity_status,
    r.active_hotspot_users,
    h.cpu_load,
    h.free_memory_mb,
    h.uptime_seconds,
    h.vpn_ping_ms
FROM public.routers r
JOIN public.tenants t ON t.id = r.tenant_id
LEFT JOIN LATERAL (
    SELECT cpu_load, free_memory_mb, uptime_seconds, vpn_ping_ms
    FROM public.router_heartbeats
    WHERE router_id = r.id
    ORDER BY recorded_at DESC
    LIMIT 1
) h ON TRUE;
