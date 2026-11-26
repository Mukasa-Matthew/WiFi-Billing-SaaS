-- The router's DHCP lease report (Devices tab, new binding form) outgrew varchar(12000) at a busy
-- school (SYNERGY: 100+ leases). varchar -> text is a metadata-only change in PostgreSQL, no rewrite.
ALTER TABLE routers ALTER COLUMN hosts_json TYPE text;
