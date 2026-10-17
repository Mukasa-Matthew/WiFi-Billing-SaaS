# WiFi Billing SaaS — Architecture & Infrastructure Integration Guide

## 1. System Overview & Two-Database Architecture

The WiFi Billing SaaS platform is built on top of the completed Phase 1 infrastructure deployed on `cloudserv` (`/opt/wifi-billing-infrastructure`) and follows a strict **two-database separation of duties**:

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                 React + TypeScript + Vite SPA (Frontend)                     │
│  • Super Admin Console (/super-admin)                                        │
│  • Business Admin Console (/business)                                        │
│  • Central Dynamic Customer Captive Portal (/portal/hotspot)                 │
└───────────────────────────────────────┬──────────────────────────────────────┘
                                        │ HTTPS / REST (VITE_API_BASE_URL)
                                        ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│               Spring Boot 3.3 / Java 21 Backend (wifi-billing-backend)       │
│  • Server-side Session Auth (WIFISESSIONID) & TenantAccessGuard              │
│  • Role-Based Access Control (super_admin, business_admin, manager, etc.)    │
│  • Flyway Schema Governance & Audit Logging                                  │
└───────────────┬──────────────────────────────────────────────┬───────────────┘
                │ JDBC (Direct Read/Write)                     │ HTTPS / Loopback REST
                ▼                                              ▼
┌───────────────────────────────────────────┐  ┌───────────────────────────────────────────┐
│   PostgreSQL: wifi_app (Application DB)   │  │ Provisioning Service (127.0.0.1:8085)     │
│  • 18 Core Tables + 5 Analytics Views     │  │  • POST /api/v1/admin/routers/create-token│
│  • Tenants, Users, Subscriptions,         │  │  • GET  /api/v1/provision/mikrotik        │
│    Packages, Vouchers, Payments, Routers  │  │  • POST /api/v1/provision/confirm         │
│  • Syncs to FreeRADIUS via DB triggers    │  │  • Manages WireGuard (wg0) + FreeRADIUS   │
└───────────────────────────────────────────┘  └───────────────────────────────────────────┘
```

### Why Two Databases Exist on `cloudserv`
1. **`wifi_app` (Application Database — Owned by Spring Boot Backend)**:
   - Stores multi-tenant SaaS entities (`tenants`, `tenant_subscriptions`, `users`, `router_sites`, `routers`, `router_enrollment_tokens`, `packages`, `package_router_scopes`, `customers`, `vouchers`, `voucher_batches`, `sales_transactions`, `mobile_money_payments`, `accounting_Usage_summaries`, `expenses`, `customer_feedback`, `audit_logs`, `system_settings`, `admin_sessions`).
   - Database triggers in `wifi_billing` (`002_freeradius_triggers_and_views.sql`) synchronize voucher/package state with FreeRADIUS.
2. **`wifi_billing` (FreeRADIUS & WireGuard Infrastructure Database — Owned by Infrastructure)**:
   - Stores low-level RADIUS tables (`radcheck`, `radreply`, `radusergroup`, `radgroupcheck`, `radgroupreply`, `radacct`, `radpostauth`, `nas`).
   - **Rule**: The Spring Boot backend **never** writes directly to `radcheck`, `radreply`, `nas`, or `/etc/wireguard/wg0.conf`. All router onboarding and WireGuard peer allocation go through the internal provisioning service (`127.0.0.1:8085`).

---

## 2. Documented Schema Inconsistencies Found & Resolved During Phase 1 Inspection

Before writing any backend migrations, we compared `/home/matthew/wifi-billing-infrastructure/docs/frontend-backend-integration-handover.md` against the actual SQL migrations in `/home/matthew/wifi-billing-infrastructure/database/app_migrations/` (`001` through `006`) and the live `cloudserv` `wifi_app` schema:

1. **Table Naming (`router_sites` vs. `sites`, and Actual 18-Table Inventory)**:
   - *Finding*: `frontend-backend-integration-handover.md` informally referred to `sites`, `voucher_router_assignments`, and `admin_notifications`. In the actual `002_customers_and_inventory.sql` and `005_additional_features.sql` applied on `cloudserv`, the physical locations table is `public.router_sites`, and the 18 live tables in `wifi_app` include `public.expenses` and `public.customer_feedback` (plus 5 views: `v_customer_analytics`, `v_expired_users`, `v_financial_analytics`, `v_revenue_history`, `v_router_health`).
   - *Resolution*: [`V1__baseline_wifi_app_schema.sql`](file:///home/matthew/Desktop/WiFi-Billing-SaaS/backend/src/main/resources/db/migration/V1__baseline_wifi_app_schema.sql) faithfully mirrors the exact 18 tables and 5 views of `wifi_app` using `CREATE TABLE IF NOT EXISTS` / `CREATE OR REPLACE VIEW`.
2. **`router_enrollment_tokens` Base Table Definition**:
   - *Finding*: `006_self_service_router_onboarding.sql` executed `ALTER TABLE public.router_enrollment_tokens ADD COLUMN ...` assuming the base table already existed on `cloudserv`, which would fail on a clean local development database.
   - *Resolution*: `V1__baseline_wifi_app_schema.sql` defines the complete `CREATE TABLE IF NOT EXISTS public.router_enrollment_tokens` including all columns required by `006_self_service_router_onboarding.sql`.
3. **Dynamic Captive Portal & Package Scoping Columns**:
   - *Finding*: The base `tenants` table in `001_multi_tenant_foundation.sql` included `branding_logo_url`, `branding_primary_color`, and `branding_secondary_color`, but needed explicit columns for WhatsApp support number, voice support phone, welcome title/message, promotional banner, payment method toggles, and portal visibility/ordering on `packages`.
   - *Resolution*: Added non-destructively via `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` in [`V2__captive_portal_and_package_scoping.sql`](file:///home/matthew/Desktop/WiFi-Billing-SaaS/backend/src/main/resources/db/migration/V2__captive_portal_and_package_scoping.sql).

---

## 3. Multi-Tenant & Role-Based Security Model

- **Server-Side Session Principal**: Authentication uses Spring Security HTTP sessions (`WIFISESSIONID`, `HttpOnly`, `SameSite=Lax`) backed by [`AuthenticatedUserPrincipal`](file:///home/matthew/Desktop/WiFi-Billing-SaaS/backend/src/main/java/world/edubridge/wifibilling/security/AuthenticatedUserPrincipal.java).
- **Zero Trust for Client Headers**: [`TenantAccessGuard`](file:///home/matthew/Desktop/WiFi-Billing-SaaS/backend/src/main/java/world/edubridge/wifibilling/security/TenantAccessGuard.java) never reads `X-Tenant-Id` or `X-User-Role` headers. Every `/api/v1/business/**` request resolves the effective `tenant_id` from the authenticated server session (`principal.getTenantId()`). Any attempt by a Business Admin to pass another tenant's `tenantId` returns `HTTP 403 Forbidden`.
- **Roles Supported**:
  - `super_admin`: Platform-wide access (`/api/v1/super-admin/**`) + ability to inspect any tenant via `?tenantId=<id>`.
  - `business_admin`: Full control over their single assigned `tenant_id`.
  - `manager`, `technician`, `cashier`: Scoped to their single assigned `tenant_id` with action-level restrictions.

---

## 4. Centrally Hosted Dynamic Captive Portal Contract

1. MikroTik routers provisioned by `cloudserv` have lightweight Hotspot bridge files (`login.html`, `alogin.html`, `rlogin.html`, `error.html`, `status.html`, `logout.html`) that redirect customers to `/portal/hotspot` with query parameters (`tenantSlug`, `router`, `mac`, `ip`, `link-login-only`, `chap-id`, `chap-challenge`).
2. The React route `/portal/hotspot` ([`CaptivePortalPage.tsx`](file:///home/matthew/Desktop/WiFi-Billing-SaaS/frontend/src/pages/CaptivePortalPage.tsx)) calls `GET /api/v1/public/portal/config?tenantSlug=...&routerCode=...` ([`PublicCaptivePortalController.java`](file:///home/matthew/Desktop/WiFi-Billing-SaaS/backend/src/main/java/world/edubridge/wifibilling/web/PublicCaptivePortalController.java)).
3. All branding (business name, logo, primary/secondary hex colors, WhatsApp number, support phone, welcome title/subtitle, promotional banner, payment toggles) and active, portal-visible packages are fetched live from PostgreSQL.
4. When a Business Admin saves changes in the dashboard (`PUT /api/v1/business/portal-settings` or `POST/PATCH /api/v1/business/packages`), the customer captive portal reflects the changes on the next page load **without touching the MikroTik router**.
