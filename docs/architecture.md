# System architecture

## Request and trust boundaries

```text
Tenant browser ──JWT──> Next.js / Spring Boot
                              │
                              ├── PostgreSQL (tenant, package, transaction and RADIUS tables)
                              ├── payment gateway
                              └── WireGuard hub management

Hotspot client ──router token──> public portal/config and payment endpoints
MikroTik router ──router token──> provisioning, telemetry and management endpoints
MikroTik hotspot ──RADIUS secret over WireGuard──> FreeRADIUS
```

Authenticated tenant identity comes from the JWT principal. Public portal requests resolve tenant and router identity from the opaque router token; they never accept a tenant ID. Checkout reloads the package and validates tenant, router restriction, availability, and authoritative price.

## Component responsibilities

### Backend

- `controller/`: HTTP contracts and authentication boundaries.
- `service/`: billing, vouchers, sessions, provisioning, router management and WireGuard orchestration.
- `radius/`: mappings for the FreeRADIUS SQL schema.
- `model/` and `repository/`: tenant-scoped persistence.
- `static/hotspot/`: version-controlled RouterOS hotspot shell and supporting pages.

`RouterScriptGenerator` owns RouterOS syntax. `ProvisioningService` owns router resolution, secret allocation, NAS registration and script context. Routine package/settings changes remain database operations and are read dynamically by the portal; they are not provisioning operations.

### Frontend

- `app/dashboard/`: tenant operations and router onboarding.
- `app/admin/`: platform-wide super-administrator functions.
- `app/api/[...path]/`: same-origin proxy to the backend.
- `lib/api.ts`: authenticated browser API client.

### FreeRADIUS

The backend and FreeRADIUS share PostgreSQL RADIUS tables. Voucher creation writes check/reply attributes; accounting reads `radacct`; `nas` identifies routers by WireGuard address and per-router shared secret. Host FreeRADIUS configuration is not overwritten by this repository.

### Captive portal

RouterOS stores the HTML shell. Runtime packages, pricing, branding and support details come from `/api/portal/config` every four seconds. Router files are not a business-data source of truth. When current configuration cannot be confirmed, purchases are paused.

## Deployment definitions

- Root `docker-compose.yml`: canonical full stack.
- `Backend/docker-compose.yml`: backend-only development.
- `Frontend/docker-compose.yml`: frontend-only development.
- `deployment/`: privileged host helpers; not container entrypoints.

There is no checked-in schema migration tool. Introducing Flyway or Liquibase requires a reviewed baseline and production migration plan.
