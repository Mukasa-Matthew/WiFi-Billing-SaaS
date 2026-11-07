# School tenants: rollout and test-router checklist

School tenants link AirPairy to the school identity system (the SYNERGY WIFI repository; its `docs/airpairy-integration.md` has the API contract). Ordinary tenants are unaffected: every school feature is switched on per tenant.

## Configuration

| AirPairy | Identity system | Value |
|---|---|---|
| `IDENTITY_BASE_URL` | | Identity backend on the internal Docker network, e.g. `http://identity-backend:8080` |
| `IDENTITY_SHARED_SECRET` | `AIRPAIRY_SHARED_SECRET` | The same random secret of at least 32 characters (`openssl rand -base64 48`) |
| `IDENTITY_DASHBOARD_URL` | | Public address of the school IT dashboard, used in IT account emails |
| | `AIRPAIRY_BASE_URL` | AirPairy backend on the internal network |

- Both backends must reach each other privately. AirPairy's `server.address` defaults to `127.0.0.1`; bind it to an address the identity container can reach, or put both on one Docker network.
- Never route `/api/integrations/identity/**` (AirPairy) or `/api/platform/**` (identity) through the public reverse proxy.
- `/api/portal/school/**` is public, like the rest of `/api/portal/**`. The identity host does **not** need to be in the walled garden; only AirPairy is contacted by phones.

## Database

All new columns are nullable and new tables are additive, so Hibernate's automatic update applies them on start-up without touching existing rows:

- `tenants`: `tenant_type`, `school_visitors_enabled`, `school_visitor_daily_cap`, `school_staff_rate_limit`, `school_visitor_rate_limit`
- `vouchers`: `person_id`, `school_category`, `visitor_pass_id`, `hard_expires_at`, `school_hold`
- `transactions`: `person_id`, `person_access_until`
- new table `integration_nonces`

Take a database backup before deploying anyway.

## Portal rollout

Routers re-download `portal.html` every 30 minutes, so the new portal reaches every router shortly after deploy. Ordinary tenants see no change (verified in headless Chrome), but watch a few ordinary routers after release.

## Test-router checklist

Use a spare MikroTik provisioned as a test school tenant, and two real phones (one Android, one iPhone) with private/random MAC on.

1. **Ordinary tenant (regression):** buy a package and connect as usual. The page looks unchanged.
2. **Student first sign-in:** ID + temporary PIN → asked for a new PIN → weak PIN rejected → good PIN accepted → packages appear → pay → connected automatically.
3. **Wrong PIN:** shows attempts left; after 5 tries, locked with a wait time; IT unlock works.
4. **Returning student:** forget the network, rejoin, sign in → connected with the same package, nothing to pay.
5. **New phone:** sign in on the second phone → second phone connects, first phone is dropped.
6. **Code sharing:** type the student's voucher code on another phone → rejected.
7. **Suspension:** IT suspends the student → device is signed out within seconds → sign-in now says access is inactive.
8. **Leaving date:** set a student's end date to today; after midnight they can't sign in and the package stops.
9. **Staff:** sign in with a staff ID → connected free at the owner's staff speed; the next day they sign in again.
10. **Former student as staff:** IT is blocked → requests an exception → owner approves on the School page → IT moves them → free access works.
11. **Visitors:** owner switches visitors off → no button on the portal and no Visitors page for IT. Switch on with a cap of 2 → third pass refused → pass connects one phone; a second phone is refused; IT cancels the pass → visitor dropped.
12. **School paused:** super-admin pauses the school → IT and portal sign-in both stop; reactivate → both work.

## Known limits

- Data caps per category are not implemented; only speed limits. Packages keep their own time limits.
- Staff free access lasts 24 hours per sign-in, then they sign in again.
- Inventory and walk-in vouchers sold by the owner still work at a school without a school sign-in. That is the owner's choice to sell them.
