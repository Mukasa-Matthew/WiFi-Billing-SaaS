-- WiFi-Billing SaaS — student daily free study data and per-group website/app filtering.
-- Additive and backward compatible: safe to apply while the previous release runs.
-- Apply BEFORE deploying the release that maps these columns (ddl-auto=none in production).

BEGIN;

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS school_student_free_enabled boolean;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS school_student_daily_free_mb integer;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS school_student_free_rate_limit varchar(30);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS school_student_allowed_sites text;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS school_staff_site_mode varchar(16);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS school_staff_site_rules text;

COMMIT;
