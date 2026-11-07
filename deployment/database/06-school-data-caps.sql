-- WiFi-Billing SaaS — school staff & visitor data caps and staff WiFi switch.
-- Additive and backward compatible: safe to apply while the previous release runs.
-- Apply BEFORE deploying the release that maps these columns (ddl-auto=none in production).
-- NULL or <= 0 data cap means unlimited; NULL or true school_staff_enabled means enabled.

BEGIN;

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS school_staff_enabled boolean;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS school_staff_daily_data_cap_mb integer;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS school_visitor_data_cap_mb integer;

COMMIT;
