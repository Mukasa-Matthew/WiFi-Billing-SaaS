# Router provisioning credential exposure: remediation runbook

Status: fix implemented and tested locally. **Not deployed. No credentials rotated. No routers changed.** Every production step below needs explicit approval.

Never paste real tokens, setup codes, RADIUS secrets or passwords into tickets, chat or logs while following this runbook. Commands below redact them.

## 1. Root cause and impact

Each router had one value, `routers.token`, doing two incompatible jobs:

- **Public identifier.** It is written into the captive portal page and portal templates (`var TOKEN`, `ROUTER_TOKEN_PLACEHOLDER`) that every hotspot customer's browser loads, and it is sent by the router's recurring schedulers.
- **Setup credential.** Without any login it unlocked:
  - `GET /api/router/provision/{token}`: the full setup script, regenerated on every call, containing the router's **RADIUS shared secret**. The same value is also the router's **`wb-api` management password**.
  - `POST /api/router/provision/{token}/register`: **replaces the router's WireGuard peer** with any public key.
  - `POST .../complete` and `GET .../install-management.rsc`.

Anyone who had connected to a hotspot could therefore:

1. **Read credentials:** the RADIUS secret and management API password for that router.
2. **Cause an outage:** register their own WireGuard key. The hub drops the real router's peer, so its tunnel, and with it RADIUS logins and accounting, stops working.
3. **Take over the tunnel identity:** the attacker's key receives the router's tunnel IP on the hub.

There is no indication either way of whether this was exploited; see §9 for how to check.

**Code history.** The token was in the setup URL from the first commit (2026-10-20). It has been embedded in the public portal since commit `cf06894` (2026-10-22).

## 2. Where the token is used

| Use | Path | After fix |
|---|---|---|
| Captive portal page, templates, assets, packages | `/api/portal/*?token=` | Unchanged (public id; non-sensitive) |
| Customer checkout | `POST /api/payments/initiate` body `token` | Unchanged |
| Router telemetry (every 2 min) | `POST /api/router/telemetry` body `token` | Unchanged; see residual risk §8 |
| Management polling (every 1 min), task/binding acks, host list | `/api/router/provision/{id}/management.rsc`, `/bindings-ack`, `/tasks/{n}/ack`, `/hosts` | Unchanged; residual risk §8 |
| Portal and package sync schedulers | `/api/portal/templates/...?token=` | Unchanged |
| **Full setup script** (RADIUS secret, `wb-api` password) | `GET /api/router/provision/{id}` | **410 Gone**, builds nothing |
| **WireGuard key registration** | `POST /api/router/provision/{id}/register` | **410 Gone** |
| Setup completion | `POST /api/router/provision/{id}/complete` | **410 Gone** |
| Management-agent installer | `GET /api/router/provision/{id}/install-management.rsc` | **410 Gone** |
| Router list/detail API | `GET /api/routers`, `/api/routers/{id}` | Token and WG key removed from responses |
| Super Admin (NOC, fleet, live probe) | server-side only | Never exposed the token. The live probe and portal push use the `wb-api` password server-side over the tunnel |

**Do deployed routers need the retired routes?** No.
- `wb-register` deletes itself after its first successful registration.
- `complete` is called once, at the end of the setup script.
- `install-management.rsc` is only run by hand from the dashboard.

Everything deployed routers call on a schedule is unchanged, so the backend change does not disrupt them.

## 3. New provisioning architecture

**Public router id** (`routers.token`, unchanged values): portal and recurring router calls only. It authorizes nothing credential-bearing.

**Setup code** (new, table `router_setup_codes`):

1. **Issue.** An authenticated business admin calls `POST /api/routers/{id}/setup-code` with body `{"purpose":"FULL"|"MANAGEMENT"}`.
   - The call is scoped to the caller's own business; another business's router returns 404.
   - The code is `su_` plus 192 random bits. Only its SHA-256 hash is stored, and the plaintext appears once in the response.
   - Issuing a new code revokes any unfinished earlier code for that router.
2. **Redeem.** `GET /api/router/setup/{code}` releases the full script **once**, within **30 minutes** of issue.
   - Redemption is a conditional database update, so concurrent fetches cannot both succeed.
   - Responses carry `Cache-Control: no-store`, `Pragma: no-cache`, `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`.
3. **Callbacks.** The script's `register` and `complete` calls use `/api/router/setup/{code}/...`. They are accepted only after the script was fetched, for at most **2 hours**, and stop at `complete`.
4. **Management installer.** A `MANAGEMENT` code releases only `install-management.rsc`. It can never release the credential script or authorize key registration.
5. **Uniform failures.** Every failure (unknown, expired, used, revoked or wrong-purpose code, or the public id) returns the same 404, so callers learn nothing.

**Configuration** (defaults shown):

```
provisioning.setup-ttl-minutes=30
provisioning.callback-window-minutes=120
```

The owner-only `GET /api/routers/{id}/portal-id` supplies the public id for the Settings portal preview.

**Transport.** The setup command uses the dashboard's HTTPS origin, and nginx already redirects HTTP to HTTPS. The setup code appears in nginx access-log paths. It is single-use and short-lived, but redacting it is recommended (§9).

## 4. Files changed (backend)

New:
- `model/RouterSetupCode.java`
- `repository/RouterSetupCodeRepository.java`
- `service/RouterSetupService.java`
- `controller/RouterSetupController.java`

Changed:
- `controller/ProvisioningController.java`: legacy routes return 410.
- `controller/RouterManagementController.java`: legacy installer returns 410.
- `controller/RouterController.java`: setup-code and portal-id endpoints; deletes codes when a router is deleted; responses carry no secrets.
- `dto/RouterDto.java`
- `service/ProvisioningService.java`: takes an authorized router; no token lookup.
- `service/RouterScriptGenerator.java`: callbacks use the setup code.
- `service/RouterManagementService.java`
- `config/SecurityConfig.java`: `/api/router/setup/**` reachable without a session; the code is the authorization.

Frontend:
- `dashboard/routers/page.tsx`, `dashboard/routers/new/page.tsx`: request a setup code for each command; show its expiry and a "generate new command" option.
- `dashboard/settings/page.tsx`: portal preview uses portal-id.

Docs: `docs/provisioning.md`, `docs/operations.md`, this file.

## 5. Regression tests (isolated, no database, no schedulers)

`mvn -o -Dtest='*Test,!ApiApplicationTests,!CoreFlowIntegrationTest' test`: **130 passed, 0 failed**.

The excluded classes boot the full application against the configured Postgres and start schedulers, so they must not be run against shared databases.

`ProvisioningSecurityWebTest` drives the real security filter chain over HTTP. It checks that:
- legacy script, register, complete and installer routes return 410 and build nothing;
- the public id is rejected as a setup code;
- anonymous callers get 401 or 403;
- another business gets 404;
- list and detail responses carry no token or secret;
- the owner flow works end to end: issue, fetch once, second fetch 404, register retries accepted, complete, then callbacks rejected;
- a management code installs the agent but never releases the script;
- deployed routers' `management.rsc` polling still works.

`RouterSetupServiceTest` checks:
- codes are random and only their hash is stored;
- single use;
- expiry;
- revocation by reissue;
- wrong purpose is rejected;
- callbacks are rejected before the script fetch, after completion, and after the window;
- a lost concurrent redemption fails;
- malformed values and the public id are rejected.

Also covered: `RouterControllerTest` (tenant scoping, no secrets in responses) and `RouterScriptGeneratorTest` (callbacks use the code, never the public id).

## 6. Deployed routers (RouterOS v6 / v7)

- **RouterOS v6:** the setup script refuses to run on v6 (WireGuard is v7-only), so no v6 router can have been provisioned through this flow and none is affected. If v6 routers exist from an older manual setup, they don't use these endpoints.
- **RouterOS v7, already provisioned:** **no change is required for continued operation.** RADIUS authentication and accounting, the WireGuard tunnel, the hotspot portal, vouchers, telemetry and management sync all keep their current URLs and values.
- **Mid-setup during deploy:** a router whose setup is still running at deploy time will have its `register`/`complete` callbacks refused. Generate a new setup command and run it again.
- **Re-provisioning** existing routers is only needed for credential rotation (§7), and can be done without re-provisioning through the management channel.

## 7. Credential rotation plan (prepared, not executed)

Treat every router's RADIUS secret and `wb-api` password as compromised. Rotate **after** the fix is deployed; rotating earlier would expose the new values the same way.

**Preflight (read-only):**
1. Confirm the fixed version is live (§9 probe returns 410).
2. For each router:
   - It is online, and a management task was recently applied. This proves the router executes tasks.
   - WinBox access through its tunnel IP works.
   - Its owner has been notified.
3. On the RADIUS host, check whether clients load from SQL only at startup:
   `grep -n read_clients /etc/freeradius/3.0/mods-enabled/sql`
   If they do, every `nas.secret` change needs a FreeRADIUS reload, which is a separate approval.
4. Take an encrypted backup of the `routers.radius_secret` and `nas.secret` rows. Do not print them.
5. Choose a low-traffic window.

**Per router, one at a time:**
1. Generate a new RADIUS secret S2 server-side. Keep the old value S1 in the encrypted backup.
2. Queue a router task: `/radius set [find comment~"wifi-billing"] secret=S2`. Wait for the task acknowledgement (minutes).
   - **No acknowledgement:** stop and change nothing server-side. The router keeps S1 and keeps working.
3. Immediately set `nas.secret` and `routers.radius_secret` to S2, then reload FreeRADIUS clients if required by the preflight check.
   - During the seconds between steps 2 and 3, new logins and accounting from this router fail. Existing sessions continue, and RouterOS retries accounting.
4. **Verify:** a test voucher login on that router succeeds, accounting rows update, and the router stays online.
5. **Failure:** if logins fail after step 3, restore S1 to `nas.secret` and reload, and queue a task that sets the router back to S1. If tasks aren't applying, use WinBox over the tunnel to set S1. Physical access is the last resort.

**Management password:**
- Decouple it from the RADIUS secret. This needs a new `management_password` column, which is a schema change.
- Then queue `/user set wb-api password=S3`, wait for the acknowledgement, store S3, and verify a live probe.
- The live probe and portal push use this password, so update storage only after the acknowledgement.

**Public ids** (`routers.token`): low priority now that they authorize nothing sensitive. Rotate them in phase 2 together with the agent credential. A rotation needs a portal-sync task with the new id, plus scheduler updates.

## 8. Residual risks (phase 2)

The public id still authenticates the router's recurring calls. None of them reveal credentials, but someone with the id could still:
- send fake telemetry, for example to mask an outage;
- acknowledge tasks falsely;
- post a fake host list;
- read `management.rsc`, which lists customers' bound MAC addresses.

**Fix:** a private per-router agent secret delivered over the management channel and sent in a header, with a per-router legacy fallback until each router is migrated.

Other residual items:
- The RADIUS secret doubles as the management password (§7).
- Setup codes appear in nginx access-log paths.

## 9. Production verification and log review (read-only)

**Is the vulnerable version live?** Use a token that does not exist. Neither version writes anything for an unknown token.

```
curl -s -o /dev/null -w '%{http_code}\n' https://app.qticket.dev/api/router/provision/tok_probe_nonexistent_0000
```

- `404`: old code, vulnerable.
- `410`: fixed code.

**Past access to the script and register routes** (nginx combined log). Tokens are redacted:

```
sudo zgrep -hE '"(GET /api/router/provision/[^/ ?]+ |POST /api/router/provision/[^/ ]+/register )' /var/log/nginx/access.log* \
 | sed -E 's#(/api/router/provision/)[^/ ?]+#\1<redacted>#' \
 | awk '{print $1, $4, $6, $7, $9, $12}' | sort | uniq -c | sort -rn | head -50
```

Look for:
- source IPs that aren't the routers' WAN addresses;
- user agents other than MikroTik's fetch;
- script fetches outside known setup sessions, or several fetches for one router;
- any `register` call long after a router's setup.

**Unexpected WireGuard key changes:**
- In the backend log, search for `registered WireGuard key` lines (router id and time only) and compare them with known setup sessions.
- On the hub, compare `wg show wg0 peers` against the `routers.wg_public_key` column, for example by key count and by tunnel IP. A peer whose key doesn't match the router's own exported key (`/interface/wireguard print`) indicates tampering.

**Optional access-log redaction** for setup codes: an nginx `map` that rewrites `$request` for `/api/router/setup/` before logging, with a custom `log_format`.

## 10. Containment you can apply before deploying (needs approval)

In the HTTPS server block, before `location /`:

```
location ~ ^/api/router/provision/[^/]+/?$ { return 410; }
location ~ ^/api/router/provision/[^/]+/(register|complete|install-management\.rsc)$ { return 410; }
```

- **Effect:** closes credential retrieval and key hijacking immediately. Deployed routers are unaffected: `management.rsc`, acks, hosts, telemetry and the portal still pass.
- **Cost:** onboarding new routers and the dashboard "Reprovision command" stop working until the new version is deployed.
- **Activate:** `nginx -t`, then reload nginx. A reload doesn't drop connections.

## 11. Deployment order (when approved)

1. Optionally apply the §10 containment now.
2. **Schema.** `ddl-auto=update` creates the table automatically. To control it explicitly, run this first:
   ```sql
   CREATE TABLE IF NOT EXISTS router_setup_codes (
     id bigserial PRIMARY KEY,
     router_id bigint NOT NULL REFERENCES routers(id),
     code_hash varchar(64) NOT NULL,
     purpose varchar(16) NOT NULL,
     created_at timestamptz NOT NULL,
     expires_at timestamptz NOT NULL,
     script_fetched_at timestamptz,
     completed_at timestamptz,
     revoked_at timestamptz);
   CREATE UNIQUE INDEX IF NOT EXISTS ux_router_setup_code_hash ON router_setup_codes(code_hash);
   ```
3. Deploy the backend and frontend together, because the old frontend's "Reprovision command" calls endpoints that no longer exist. Choose a low-traffic time.
   - The backend restart briefly interrupts the API. Telemetry retries every 2 minutes and routers are only marked offline after 6, so a short restart doesn't flip routers offline.
   - Customer checkouts started during the restart fail; customers can retry.
4. Verify (§12).
5. Review logs (§9) and decide on rotation (§7, separate approval).
6. Phase 2: the agent credential (§8).

## 12. Post-deploy verification

1. The §9 probe returns 410 for a nonexistent id.
2. In the dashboard, generate a setup command on a **test or CHR router**, then:
   - run it, and confirm the router registers and completes;
   - re-run the same command, and confirm it fails (single use).
3. On a deployed router, without touching it:
   - telemetry stays fresh;
   - a "Sync router time" task is applied;
   - the portal loads, and a test voucher logs in with accounting recorded.
4. `GET /api/routers` contains no `token` field.

## 13. Rollback

- Redeploy the previous backend and frontend images. The new table can stay in place unused.
- Rolling back **reopens the vulnerability**, so keep the §10 nginx block while rolled back.
- Routers provisioned on the new version are unaffected by a rollback once setup completed. Only routers mid-setup during the rollback need a new setup run.
- No router configuration changes are part of this deploy, so there is nothing to roll back on routers.
