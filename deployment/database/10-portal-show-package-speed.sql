-- WiFi-Billing SaaS — owners can hide each package's speed on the captive portal.
-- Nullable-safe additive column (ddl-auto=none in production): default true keeps existing behaviour.

BEGIN;

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS portal_show_package_speed boolean NOT NULL DEFAULT true;

COMMIT;
