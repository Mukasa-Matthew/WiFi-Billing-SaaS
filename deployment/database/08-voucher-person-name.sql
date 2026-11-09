-- WiFi-Billing SaaS — the identity person's name on school vouchers and their payment transactions.
-- Lets a school owner find a connected student or staff member by name instead of hunting for the voucher code.
-- Nullable and additive: safe to apply while the previous release runs (ddl-auto=none in production).

BEGIN;

ALTER TABLE vouchers ADD COLUMN IF NOT EXISTS person_name varchar(200);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS person_name varchar(200);

COMMIT;
