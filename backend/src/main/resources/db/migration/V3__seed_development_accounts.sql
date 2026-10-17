-- ==============================================================================
-- Flyway V3: Idempotent Baseline Tenants & Sample Packages for Local Development
-- (User accounts with verified BCrypt hashes are seeded by DevelopmentDataSeeder)
-- ==============================================================================

INSERT INTO public.tenants (
    id, name, slug, status, company_name, contact_name, contact_email, contact_phone,
    whatsapp_phone, support_phone, currency, timezone,
    branding_primary_color, branding_secondary_color,
    portal_welcome_title, portal_welcome_message, portal_promotional_banner
)
VALUES
    (
        1, 'Default WiFi Hotspot', 'default-hotspot', 'active',
        'Default Hotspot Networks Ltd', 'Primary Business Admin', 'admin@default-hotspot.local', '+256700100100',
        '+256700100100', '+256700100100', 'UGX', 'Africa/Kampala',
        '#2563eb', '#0f172a',
        'Connect to Default WiFi Hotspot',
        'Choose an affordable high-speed internet package below or enter your 8-digit voucher PIN.',
        'Instant activation via MTN MoMo & Airtel Money'
    ),
    (
        2, 'Secondary Isolated Tenant', 'tenant-2', 'active',
        'Savannah Lounge & Cafe WiFi', 'Savannah Manager', 'admin@tenant2.local', '+256772200200',
        '+256772200200', '+256772200200', 'UGX', 'Africa/Kampala',
        '#059669', '#064e3b',
        'Welcome to Savannah Lounge WiFi',
        'Enjoy uninterrupted fiber internet across the lounge and garden terrace.',
        'Weekend Special: Unlimited 24-Hour Pass for UGX 2,500!'
    )
ON CONFLICT (id) DO NOTHING;

SELECT setval('public.tenants_id_seq', GREATEST((SELECT COALESCE(MAX(id), 1) FROM public.tenants), 2));

INSERT INTO public.tenant_subscriptions (tenant_id, plan_tier, router_limit, voucher_limit, status, billing_cycle)
SELECT 1, 'pro', 10, 25000, 'active', 'monthly'
WHERE NOT EXISTS (SELECT 1 FROM public.tenant_subscriptions WHERE tenant_id = 1);

INSERT INTO public.tenant_subscriptions (tenant_id, plan_tier, router_limit, voucher_limit, status, billing_cycle)
SELECT 2, 'starter', 5, 5000, 'active', 'monthly'
WHERE NOT EXISTS (SELECT 1 FROM public.tenant_subscriptions WHERE tenant_id = 2);

INSERT INTO public.packages (
    tenant_id, name, description, price, currency, duration_seconds,
    rate_limit_up_kbps, rate_limit_down_kbps, simultaneous_use, session_timeout,
    interim_interval, data_quota_bytes, is_free_trial, is_active, portal_visible, sort_order, badge_text
)
SELECT 1, '1 Hour Express Pass', 'Fast browsing & social media for 60 minutes', 1000.00, 'UGX', 3600,
       2048, 5120, 1, 3600, 300, NULL, FALSE, TRUE, TRUE, 1, 'Popular'
WHERE NOT EXISTS (SELECT 1 FROM public.packages WHERE tenant_id = 1 AND name = '1 Hour Express Pass');

INSERT INTO public.packages (
    tenant_id, name, description, price, currency, duration_seconds,
    rate_limit_up_kbps, rate_limit_down_kbps, simultaneous_use, session_timeout,
    interim_interval, data_quota_bytes, is_free_trial, is_active, portal_visible, sort_order, badge_text
)
SELECT 1, '24 Hour Unlimited Day Pass', 'Full day HD streaming & remote work', 3000.00, 'UGX', 86400,
       4096, 10240, 2, 86400, 300, NULL, FALSE, TRUE, TRUE, 2, 'Best Value'
WHERE NOT EXISTS (SELECT 1 FROM public.packages WHERE tenant_id = 1 AND name = '24 Hour Unlimited Day Pass');

INSERT INTO public.packages (
    tenant_id, name, description, price, currency, duration_seconds,
    rate_limit_up_kbps, rate_limit_down_kbps, simultaneous_use, session_timeout,
    interim_interval, data_quota_bytes, is_free_trial, is_active, portal_visible, sort_order, badge_text
)
SELECT 2, 'Savannah 2-Hour Coffee Pass', 'High-speed WiFi while you dine', 1500.00, 'UGX', 7200,
       4096, 10240, 1, 7200, 300, NULL, FALSE, TRUE, TRUE, 1, 'Guest Favorite'
WHERE NOT EXISTS (SELECT 1 FROM public.packages WHERE tenant_id = 2 AND name = 'Savannah 2-Hour Coffee Pass');
