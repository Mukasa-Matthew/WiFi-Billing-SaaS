-- WiFi-Billing SaaS — school tenants linked to the school identity system.
-- Additive and backward compatible: safe to apply while the previous release runs.
-- Apply BEFORE deploying the release that maps these columns (ddl-auto=none in production).
-- Every new column is nullable; NULL means "not a school" / "not a school voucher".

BEGIN;

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS tenant_type varchar(16);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS school_visitors_enabled boolean;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS school_visitor_daily_cap integer;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS school_staff_rate_limit varchar(30);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS school_visitor_rate_limit varchar(30);

ALTER TABLE vouchers ADD COLUMN IF NOT EXISTS person_id varchar(80);
ALTER TABLE vouchers ADD COLUMN IF NOT EXISTS school_category varchar(8);
ALTER TABLE vouchers ADD COLUMN IF NOT EXISTS visitor_pass_id varchar(36);
ALTER TABLE vouchers ADD COLUMN IF NOT EXISTS hard_expires_at timestamp(6) with time zone;
ALTER TABLE vouchers ADD COLUMN IF NOT EXISTS school_hold boolean;
CREATE INDEX IF NOT EXISTS ix_vouchers_school_person ON vouchers (tenant_id, person_id) WHERE person_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_vouchers_visitor_pass ON vouchers (tenant_id, visitor_pass_id) WHERE visitor_pass_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_vouchers_hard_expiry ON vouchers (status, hard_expires_at) WHERE hard_expires_at IS NOT NULL;

ALTER TABLE transactions ADD COLUMN IF NOT EXISTS person_id varchar(80);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS person_access_until date;

-- Single-use nonces of signed calls from the identity system (kept ~15 minutes).
CREATE TABLE IF NOT EXISTS integration_nonces (
    nonce varchar(64) PRIMARY KEY,
    received_at timestamp(6) with time zone NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_integration_nonces_received ON integration_nonces (received_at);

COMMIT;
