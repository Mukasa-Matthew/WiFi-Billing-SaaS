-- ==============================================================================
-- WiFi-Billing SaaS — Authoritative Distributed Job Coordination Schema
-- Database: PostgreSQL (16+)
-- Backward compatible: Does not alter existing billing, radius, or tenant tables.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS app_job_leases (
    job_name VARCHAR(64) PRIMARY KEY,
    owner_id VARCHAR(128) NOT NULL,
    fence_token BIGINT NOT NULL DEFAULT 1,
    lease_acquired_at TIMESTAMPTZ NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    last_cycle_start TIMESTAMPTZ NOT NULL,
    last_completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_app_job_leases_expiry ON app_job_leases (lease_expires_at);

COMMENT ON TABLE app_job_leases IS 'Coordinates scheduled background jobs across instances with fenced leases and cycle protection.';
