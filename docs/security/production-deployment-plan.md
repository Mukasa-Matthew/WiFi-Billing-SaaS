# Router Setup Security Fix: Production Deployment Plan

**Status:** Ready for review.  
**Strict Safety Rule:** Nothing in this plan has been deployed, reloaded, migrated, rotated, or modified on any router or database. Every production action requires explicit operator approval.

Background and design specification: `docs/security/router-provisioning-remediation.md`.  
Never print or paste real router tokens, setup codes, WireGuard private keys, or RADIUS secrets into chat, issue trackers, or tickets. All examples below use placeholder values.

---

## 0. Context & Verified Bug Fixes

Verification against concrete backend components (Spring Boot, Hibernate JPA, PostgreSQL) identified and resolved two critical issues in the remediation code:

1. **LazyInitializationException on Setup Endpoints (500 Error):**  
   The initial setup service passed around an entity whose JPA session closed before building scripts. The service now passes explicit scalar identifiers (`routerId`), with each retrieval executing inside its own isolated `@Transactional` boundary.
2. **Setup Code Invalidation on Script Generation Failure:**  
   If script assembly encountered a failure, redemption had already occurred, burning the owner's code. Code redemption and script compilation are now executed in a single atomic transaction; any compilation failure rolls back redemption, leaving the owner's code valid for retry.

### Implemented Hardening Measures:
- **WireGuard Key Binding:** A setup code binds immutably to the first WireGuard public key registered with it (`registered_key_hash`). Re-transmissions from the router using the same key succeed, while foreign keys are rejected even within the active callback window.
- **Router Flash Script Scrubbing:** The generated MikroTik setup sequence explicitly removes the downloaded script (`/file remove [find name="..."]`) upon completion or failure, preventing secrets from lingering in non-volatile flash storage.
- **Public API Redaction:** `RouterDto.Response` no longer includes `token` or raw `wgPublicKey`. Owners obtain ephemeral paths via `POST /api/routers/{id}/setup-code` and read-only preview tokens via `GET /api/routers/{id}/portal-id`.

---

## 1. Production Preflight Checks (Read-Only)

Before executing any deployment steps, verify the live state without altering any configurations:

1. **Vulnerability & Route Status Probe:**  
   Check whether the edge or backend is currently running the legacy route using a nonexistent token:
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' https://app.qticket.dev/api/router/provision/tok_probe_nonexistent_0000
   ```
   - `404 Not Found`: Legacy code is running (vulnerable to credential harvesting).
   - `410 Gone`: Hardened containment or new backend is active.

2. **Database Engine & Connection Health:**  
   Verify database connectivity and check if the migration table already exists:
   ```sql
   SELECT table_name FROM information_schema.tables WHERE table_name = 'router_setup_codes';
   ```

3. **FreeRADIUS SQL Client Reload Capability:**  
   Inspect FreeRADIUS configuration to determine if client definition changes require a daemon reload:
   ```bash
   grep -n "read_clients" /etc/freeradius/3.0/mods-enabled/sql
   ```
   *(If `read_clients = no`, any future NAS secret change necessitates `systemctl reload freeradius`).*

4. **Router Fleet Connectivity & Task Channel Validation:**  
   Check that live routers are checking in via the management channel:
   - Verify `lastSeen` telemetry timestamps in the dashboard.
   - Dispatch a benign management task (e.g., `SYNC_TIME`) to a staging/canary router and confirm it reports `APPLIED`.

---

## 2. Edge Nginx Containment (Can Be Applied Prior to Full Deploy)

To instantly sever the credential leakage and key hijacking vulnerability without waiting for container compilation or scheduling downtime, apply the retired setup rules at the Nginx reverse-proxy:

1. **Install Log Redaction & Endpoint Retirement Configs:**
   Place `deployment/nginx/airpairy-log-redaction.conf` in the Nginx `http` block and include `deployment/nginx/airpairy-retired-setup.conf` inside the HTTPS `server` block prior to `location /`.
   
   Direct containment rules:
   ```nginx
   location ~* ^/api/router/provision/[^/]+/?$ {
       access_log /var/log/nginx/airpairy-retired-setup.log airpairy_redacted;
       return 410;
   }
   location ~* ^/api/router/provision/[^/]+/(register|complete|install-management\.rsc)/?$ {
       access_log /var/log/nginx/airpairy-retired-setup.log airpairy_redacted;
       return 410;
   }
   ```
2. **Validate Syntax & Reload:**
   ```bash
   sudo nginx -t && sudo systemctl reload nginx
   ```
   - **Impact on Deployed Routers:** Zero impact. Operational routes (`management.rsc`, telemetry, task acks, host lists) and the captive portal are deliberately excluded from this block.
   - **Trade-off:** New router onboarding and dashboard "Reprovision" actions are paused until the updated application containers go live.

---

## 3. Database Migration (Safe, Additive DDL)

The new table is completely additive; it creates one table and index with no modifications or locking on existing tables (`routers`, `tenants`, `nas`).

Run the DDL script (`docs/security/router_setup_codes.sql`):
```sql
BEGIN;
CREATE TABLE IF NOT EXISTS router_setup_codes (
    id                  bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    router_id           bigint NOT NULL REFERENCES routers(id),
    code_hash           varchar(64) NOT NULL,
    purpose             varchar(16) NOT NULL CHECK (purpose IN ('FULL', 'MANAGEMENT')),
    created_at          timestamp(6) with time zone NOT NULL,
    expires_at          timestamp(6) with time zone NOT NULL,
    script_fetched_at   timestamp(6) with time zone,
    registered_key_hash varchar(64),
    completed_at        timestamp(6) with time zone,
    revoked_at          timestamp(6) with time zone,
    CONSTRAINT ux_router_setup_code_hash UNIQUE (code_hash)
);
CREATE INDEX IF NOT EXISTS idx_router_setup_codes_router_id ON router_setup_codes(router_id);
COMMIT;
```

---

## 4. Application Deployment Execution

Both backend and frontend must be deployed synchronously because the updated frontend references `/setup-code` while the old frontend attempts to call `/api/router/provision/{token}`.

1. **Schedule Maintenance Window:**  
   Select an off-peak traffic period. Existing connected WiFi users will continue browsing uninterrupted, as local router state and RADIUS session accounting remain intact.
2. **Backend Deployment:**  
   Deploy the backend service container/binary.
   - Telemetry reports push every 2 minutes; routers are marked offline only after 6 minutes. A restart under 2 minutes produces zero offline flapping in the NOC.
3. **Frontend Deployment:**  
   Deploy the Next.js production build (`.next/standalone`).

---

## 5. Post-Deployment Verification Checklist

1. **Verify Route Retirement (Probe Test):**
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' https://app.qticket.dev/api/router/provision/tok_probe_nonexistent_0000
   ```
   Must return `410`.
2. **API Data Protection Verification:**
   Query the authenticated `/api/routers` endpoint with an admin token:
   - Ensure the JSON response does **not** contain `token`, `radiusSecret`, or raw private keys.
   - Verify that `registered` is boolean (`true`/`false`).
3. **Canary Onboarding Flow (Test Router or CHR):**
   - Click "Add Router" or generate a setup command in the dashboard.
   - Execute the `/tool fetch` script on a test MikroTik router.
   - Confirm key registration succeeds, script deletes itself from router storage, and router switches to `PROVISIONED` / `ONLINE`.
   - Re-run the same command on the test router; verify it is rejected (single-use guarantee).
4. **Existing Deployed Fleet Health:**
   - Confirm active routers maintain `ONLINE` status.
   - Verify active sessions continue logging accounting records in `radacct`.
   - Trigger a portal sync task and verify application.

---

## 6. Rollback Precautions & Procedures

- **If Deployment Fails:**
  1. Roll back the backend and frontend application images to the previous release.
  2. The `router_setup_codes` table is additive and can remain idle in the database without side effects.
  3. **Crucial Security Warning:** Rolling back the backend code re-exposes the provisioning vulnerability. Ensure the Nginx §2 containment rule remains active during and after any rollback.
- **Router Impact:**
  - Routers that were provisioned prior to deployment are unaffected by a rollback.
  - Any router that was in the middle of executing a setup script during a rollback will simply require a newly generated setup command once services stabilize.

---

## 7. Remaining Public-ID Authentication Risks (Phase 2 Roadmap)

While this fix stops credential harvesting, the public identifier (`routers.token`) remains used for recurring operational endpoints (`management.rsc`, telemetry, task acks).

**Residual Threat Model:**
- An adversary capturing a public token from captive portal HTML could theoretically post spoofed telemetry (e.g. reporting 0% CPU or falsified active user counts) or inspect `management.rsc` to view IP-binding MAC addresses.

**Phase 2 Mitigation Plan:**
- Introduce a dedicated, cryptographically random `agent_secret` generated at provisioning time and distributed exclusively over the WireGuard tunnel or encrypted management channel.
- Require an `X-Router-Agent-Secret` header on telemetry and management polling, with backward-compatible legacy fallback until the fleet has been fully refreshed.

---

## 8. Safe Credential-Rotation Prerequisites (After Verification)

Because all pre-existing router credentials (RADIUS secret, `wb-api` password) must be considered potentially compromised from historical access:

1. **Prerequisite:** Under no circumstances rotate credentials before the new setup flow is verified in production. Rotating beforehand would expose new secrets through the legacy endpoint.
2. **Readiness Check:** Confirm that every target router is actively executing tasks via WireGuard (`lastSeen` < 2 minutes).
3. **Execution Strategy (One Router at a Time):**
   - Push updated secret `S2` to router via task: `/radius set [find comment~"wifi-billing"] secret=S2`.
   - Wait for task acknowledgment (`APPLIED`).
   - Update `nas.secret` in database and trigger FreeRADIUS client reload.
   - Validate with a test voucher authentication.
