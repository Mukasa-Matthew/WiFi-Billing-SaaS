-- Owner's monthly sales goal (UGX, gross sales) for the Financial analysis page.
-- Additive and nullable: existing tenants simply have no target until they set one.
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS monthly_sales_target bigint;
