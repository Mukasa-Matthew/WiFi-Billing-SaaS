-- WiFi-Billing SaaS — router task scripts may exceed 12,000 characters (school site filters with DNS-learned lists).
-- Widening varchar to text is metadata-only in PostgreSQL and safe while the previous release runs.
-- Apply BEFORE deploying the release that writes longer scripts (ddl-auto=none in production).

BEGIN;
ALTER TABLE router_tasks ALTER COLUMN script TYPE text;
COMMIT;
