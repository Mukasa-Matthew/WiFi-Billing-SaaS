BEGIN;
CREATE SEQUENCE IF NOT EXISTS winbox_public_port_seq START WITH 30000 INCREMENT BY 1 MINVALUE 30000 MAXVALUE 39999 NO CYCLE;
CREATE TABLE IF NOT EXISTS winbox_access (
    port integer PRIMARY KEY DEFAULT nextval('winbox_public_port_seq') CHECK (port BETWEEN 30000 AND 39999),
    router_id bigint NOT NULL UNIQUE REFERENCES routers(id) ON DELETE CASCADE,
    tenant_id bigint NOT NULL,
    tunnel_ip varchar(15) NOT NULL,
    peer_key varchar(64) NOT NULL,
    username varchar(48) NOT NULL,
    password_ciphertext varchar(512) NOT NULL,
    agent_ciphertext varchar(512),
    agent_hash varchar(64),
    enabled boolean NOT NULL DEFAULT false,
    revision bigint NOT NULL DEFAULT 0,
    applied_revision bigint NOT NULL DEFAULT 0,
    router_checked_at timestamptz,
    router_result varchar(32),
    gateway_result varchar(32),
    gateway_revision bigint NOT NULL DEFAULT 0,
    gateway_checked_at timestamptz,
    last_successful_check timestamptz,
    version bigint NOT NULL DEFAULT 0
);
COMMIT;
