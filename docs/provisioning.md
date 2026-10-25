# MikroTik RouterOS v7 provisioning

## Customer workflow

1. The tenant authenticates and creates a router record.
2. The backend binds it to the tenant, allocates a WireGuard address, and creates opaque router/RADIUS credentials.
3. The dashboard displays a router-specific fetch/import command.
4. The tenant runs it in a RouterOS v7 terminal.
5. The script performs preflight checks, exports a backup, installs WiFi-Billing-owned configuration, registers WireGuard, installs management/telemetry, and reports completion.
6. The dashboard shows ready only after telemetry confirms the managed hotspot, RADIUS entry and portal file are present.

Routine pricing, package and support changes use the dynamic portal API and never require reprovisioning.

## Provisioning states

- `pending`: record exists; script has not been fetched.
- `provisioning`: script was fetched and server prerequisites were prepared.
- `registered`: router public key reached the backend; not readiness.
- `provisioned`: script completion callback received; telemetry pending.
- `online`: telemetry is current and all new-agent readiness checks pass.
- `degraded`: telemetry arrived but at least one readiness check failed.
- `offline`: a previously online router missed the telemetry window.

Older agents omit readiness fields and retain their prior behavior for compatibility.

## Retry and conflict behavior

Scripts carry `Provisioning-Version: 2026.10.1`. A successful install creates `wb-provisioned` with the same version. Re-running that command exits without moving interfaces, clearing leases, or duplicating rules.

Before modification, the script verifies RouterOS v7, the WAN interface, ownership of `wb-hotspot-bridge`, and ownership of `10.10.0.1/24`. Conflicts stop before cleanup. Managed rules use comment `wifi-billing` and managed objects use `wb-` names.

A partial run has no final marker, so retrying cleans known WiFi-Billing objects and resumes. Every modifying run requests `/export file=before-wifi-billing` first.

## Security

- Provisioning responses use `Cache-Control: no-store` because they contain a RADIUS secret.
- Router identifiers are high-entropy and tenant-bound server-side.
- The management REST user is restricted to the WireGuard subnet and a limited RouterOS group.
- Hub peer management uses a forced SSH command and constrained sudo rule.

Enrollment and runtime identifiers are separate:

- The router's public identifier (`routers.token`) is embedded in captive portal pages and used by the router's recurring calls (portal sync, telemetry, management polling). It authorizes nothing credential-bearing.
- Setup uses a one-time code the owner generates in the dashboard (`POST /api/routers/{id}/setup-code`). `GET /api/router/setup/{code}` releases the script once, within 30 minutes; the code then authorizes only that setup's `register`/`complete` callbacks for 2 hours. Only a hash of the code is stored.
- The old `/api/router/provision/{token}` script, `register`, `complete` and `install-management.rsc` routes return `410 Gone`.

See `security/router-provisioning-remediation.md` for the migration, rotation and verification runbook.

## Deliberate repair

Full repair can move LAN interfaces and interrupt customers. Schedule maintenance, export the router, then remove only the version marker:

```routeros
/export file=before-wifi-billing-repair
/system/script/remove [find name=wb-provisioned comment="wifi-billing:2026.10.1"]
```

Rerun the dashboard command. Never reset the router or delete unrelated configuration as a recovery shortcut.
