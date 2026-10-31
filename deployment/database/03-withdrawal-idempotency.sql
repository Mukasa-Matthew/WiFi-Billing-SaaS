-- WiFi-Billing SaaS — withdrawal idempotency and dispatch claim.
-- Additive and backward compatible: safe to apply while the previous release runs.
-- Apply BEFORE deploying the release that maps these columns (ddl-auto=none in production).

BEGIN;

-- Client-supplied Idempotency-Key: a retried or double-submitted request returns
-- the original withdrawal instead of creating (and paying) a second one.
ALTER TABLE withdrawal_requests ADD COLUMN IF NOT EXISTS idempotency_key varchar(64);
CREATE UNIQUE INDEX IF NOT EXISTS ux_withdrawal_requests_tenant_idempotency
    ON withdrawal_requests (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Set atomically when a payout is claimed for sending; at most one caller can
-- ever claim it. PROCESSING rows with this set are re-checked against MarzPay.
ALTER TABLE withdrawal_requests ADD COLUMN IF NOT EXISTS dispatched_at timestamptz;
CREATE INDEX IF NOT EXISTS ix_withdrawal_requests_status_dispatched
    ON withdrawal_requests (status, dispatched_at);

COMMIT;
