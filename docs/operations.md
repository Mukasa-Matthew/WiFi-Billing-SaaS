# Production operations and recovery

## Deployment checklist

1. Back up PostgreSQL and supply secrets outside Git.
2. Ensure `PROVISIONING_PUBLIC_BASE_URL` is the reachable HTTPS origin.
3. Run backend tests and frontend lint/build.
4. Deploy backend before frontend when API semantics change.
5. Verify `/api/portal/config` from an unauthenticated lab hotspot client.
6. Test provisioning-version changes on RouterOS v7 lab hardware first.

## Router verification

```routeros
/system/resource/print
/system/script/print detail where name=wb-provisioned
/interface/wireguard/print detail where name=wg-billing
/ip/hotspot/print detail where name=wb-hotspot
/radius/print detail where comment=wifi-billing
/file/print detail where name=flash/hotspot/login.html
/log/print where message~"wifi-billing"
```

From an unauthenticated client, test DNS, portal configuration, payment initiation, voucher login, and automatic refresh after a package edit. Router `/tool fetch` proves router egress only, not client walled-garden access.

```routeros
/ip/hotspot/walled-garden/ip/print detail
/ip/hotspot/walled-garden/print detail
/ip/firewall/nat/print detail where chain=pre-hotspot
```

Do not apply firewall changes to production before testing the same RouterOS/configuration family.

## FreeRADIUS verification

Run `sudo deployment/verify-voucher-radius.sh` on the FreeRADIUS host. Then use a test voucher and inspect `radcheck`, `radreply`, `radacct`, and service logs. SQL presence alone does not prove UDP reachability or secret agreement.

## Partial provisioning recovery

1. Preserve router logs and the `before-wifi-billing` export.
2. Correct preflight conflicts instead of deleting unrelated objects.
3. Without a `wb-provisioned` marker, rerun the same command to repair partial state.
4. With a marker but degraded readiness, inspect hotspot, RADIUS, portal, WireGuard and walled-garden state before full repair.
5. Restore an export only in a maintenance window with local administrative access.

## Rollback

Application rollback uses the prior database-compatible image. Router rollback is not automatic because it can remove management access. Use RouterOS Safe Mode/local access and the pre-provision export. Never automate `reset-configuration`.

## Remaining infrastructure work

- Enrollment now uses short-lived, single-use setup codes (see `security/router-provisioning-remediation.md`). Still open: a private per-router agent credential for recurring router calls, and rotation of credentials exposed under the old scheme.
- Baseline production under Flyway/Liquibase before schema-backed provisioning audit events.
- Test CHR and physical devices across supported v7 and wireless-package variants.
- Add an active RADIUS Access-Request readiness probe; current readiness confirms configured presence.
