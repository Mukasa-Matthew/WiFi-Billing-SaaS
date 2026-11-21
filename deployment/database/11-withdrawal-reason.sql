-- Tenant-provided reason for a wallet withdrawal (business record keeping).
ALTER TABLE withdrawal_requests ADD COLUMN IF NOT EXISTS reason varchar(255);
