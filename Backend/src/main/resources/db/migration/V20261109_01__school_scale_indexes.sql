-- ==============================================================================
-- WiFi-Billing SaaS — 2000-user school capacity indexes
-- Database: PostgreSQL (16+)
-- Backward compatible: adds indexes only. Applied manually per migration policy.
-- ==============================================================================

-- /api/portal/school/resume looks up a live voucher by claimed device MAC on
-- every captive-portal page load. Without this, each connect scans all of the
-- tenant's vouchers.
CREATE INDEX IF NOT EXISTS ix_vouchers_claimed_mac
    ON vouchers (tenant_id, upper(claimed_mac))
    WHERE claimed_mac IS NOT NULL;

-- Every tenant aggregate, cap check, user directory, and first-login stamp
-- filters radacct by username (the voucher code). Only (acctstarttime,
-- username) existed before, which cannot serve username-led lookups.
CREATE INDEX IF NOT EXISTS ix_radacct_username
    ON radacct (username);

-- The 30s voucher worker sweeps findAllByStatus('unused') / ('active')
-- platform-wide; the existing (tenant_id, status) composite can't lead on
-- status alone.
CREATE INDEX IF NOT EXISTS ix_vouchers_status
    ON vouchers (status);

-- Open-session lookups (disconnects, "who's online") filter
-- acctstoptime IS NULL + username; supports findAllByUsernameAndAcctStopTimeIsNull.
CREATE INDEX IF NOT EXISTS ix_radacct_open_username
    ON radacct (username)
    WHERE acctstoptime IS NULL;
