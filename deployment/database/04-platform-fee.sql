-- WiFi-Billing SaaS — optional per-tenant platform fee on customer payments.
-- Additive and backward compatible: safe to apply while the previous release runs.
-- Apply BEFORE deploying the release that maps these columns (ddl-auto=none in production).

BEGIN;

-- Off by default; the rate is kept in basis points (300 = 3%).
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS platform_fee_enabled boolean NOT NULL DEFAULT false;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS platform_fee_bps integer NOT NULL DEFAULT 300;

-- Each payment keeps the rate it was started under and the fee it actually paid,
-- so turning the fee on or off never rewrites history.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS platform_fee_bps integer;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS platform_fee bigint;
CREATE INDEX IF NOT EXISTS ix_transactions_platform_fee
    ON transactions (completed_at) WHERE platform_fee > 0;

COMMIT;
