-- WiFi-Billing SaaS — captive portal logo and background images, stored in the database.
-- Additive: safe to apply while the previous release runs. Apply BEFORE deploying the
-- release that maps this table (ddl-auto=none in production).
--
-- Images used to be written to a folder inside the running slot, which was never served
-- and is replaced on every blue-green deploy. In the database they survive deploys and
-- slot switches, and are included in database backups. Each image is at most ~1 MB.

BEGIN;

CREATE TABLE IF NOT EXISTS tenant_portal_assets (
    id           bigserial PRIMARY KEY,
    tenant_id    bigint      NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    kind         varchar(16) NOT NULL,            -- 'logo' or 'background'
    content_type varchar(40) NOT NULL,
    data         bytea       NOT NULL,
    sha256       varchar(64) NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_tenant_portal_assets_tenant_kind UNIQUE (tenant_id, kind)
);

COMMIT;
