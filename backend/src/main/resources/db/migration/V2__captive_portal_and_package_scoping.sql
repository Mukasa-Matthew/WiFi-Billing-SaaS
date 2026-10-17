-- ==============================================================================
-- Flyway V2: Dynamic Captive Portal Preferences & Package Router/Site Scoping
-- Non-destructive additive extensions on public.tenants and public.packages.
-- ==============================================================================

ALTER TABLE public.tenants
    ADD COLUMN IF NOT EXISTS branding_secondary_color VARCHAR(10) DEFAULT '#0f172a',
    ADD COLUMN IF NOT EXISTS whatsapp_phone VARCHAR(50),
    ADD COLUMN IF NOT EXISTS support_phone VARCHAR(50),
    ADD COLUMN IF NOT EXISTS portal_welcome_title VARCHAR(255) DEFAULT 'Fast, Reliable High-Speed WiFi',
    ADD COLUMN IF NOT EXISTS portal_welcome_message TEXT DEFAULT 'Select a package below to get online instantly or enter an existing voucher code.',
    ADD COLUMN IF NOT EXISTS portal_promotional_banner TEXT,
    ADD COLUMN IF NOT EXISTS portal_enable_mtn_momo BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS portal_enable_airtel_money BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS portal_enable_voucher_code BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE public.packages
    ADD COLUMN IF NOT EXISTS portal_visible BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS badge_text VARCHAR(50);

-- Optional scoping of packages to specific routers or sites (empty = eligible on all tenant routers)
CREATE TABLE IF NOT EXISTS public.package_router_scopes (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    package_id BIGINT NOT NULL REFERENCES public.packages(id) ON DELETE CASCADE,
    router_id BIGINT REFERENCES public.routers(id) ON DELETE CASCADE,
    site_id BIGINT REFERENCES public.router_sites(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (package_id, router_id)
);

CREATE INDEX IF NOT EXISTS idx_package_router_scopes_pkg ON public.package_router_scopes(package_id);
