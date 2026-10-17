# WiFi Billing SaaS — Multi-Tenant Hotspot, MikroTik & Dynamic Captive Portal Platform

Commercial multi-tenant WiFi Billing SaaS web application built with **Spring Boot 3.3 (Java 21)** and **React 18 + TypeScript + Vite + Tailwind CSS**, designed to integrate with the `cloudserv` FreeRADIUS + WireGuard + PostgreSQL infrastructure.

---

## Repository Structure

```text
WiFi-Billing-SaaS/
├── backend/       # Java 21 + Spring Boot 3.3 + Spring Security + JPA + Flyway REST API
├── frontend/      # React 18 + TypeScript + Vite + Tailwind CSS + TanStack Query SPA
├── docs/          # Architecture, database contracts, and integration documentation
├── scripts/       # Local PostgreSQL cluster launcher & cloudserv SSH tunnel helper
├── README.md      # Local setup, development credentials, and milestone roadmap
└── .gitignore     # Excludes build artifacts, node_modules, .env, keys, and .pgdata
```

---

## Quick Start (Local Development on Ubuntu PC)

### 1. Start the Isolated Local PostgreSQL Database (`127.0.0.1:5435`)
An unprivileged local PostgreSQL 18 instance is managed inside `.pgdata/` so development never touches `cloudserv` production data:

```bash
./scripts/start_local_db.sh
```
- **JDBC URL**: `jdbc:postgresql://127.0.0.1:5435/wifi_app_dev`
- **User**: `wifi_app_user`
- **Password**: `local_dev_password_2026`

### 2. Run Backend Tests & Start the Spring Boot API (`http://localhost:8080`)
```bash
cd backend
mvn test
mvn spring-boot:run
```
On startup, Flyway automatically applies `V1` (18 baseline `wifi_app` tables + 5 views), `V2` (dynamic captive portal & package scoping columns), and `V3` (seed tenants & packages), and `DevelopmentDataSeeder` seeds the local test accounts.

### 3. Configure & Start the Frontend (`http://localhost:5173`)
Ensure `frontend/.env` exists (copied from `frontend/.env.example`):
```bash
cd frontend
cp -n .env.example .env
NPM_CONFIG_USERCONFIG=/dev/null npm install
NPM_CONFIG_USERCONFIG=/dev/null npm run dev
```

---

## Seeded Local Development Accounts

All seeded development accounts use password: **`Admin@2026!`**

| Role | Email | Assigned Tenant | Default Route |
| :--- | :--- | :--- | :--- |
| **Super Admin** | `superadmin@edubridge.world` | Platform-wide (`null`) | `/super-admin` |
| **Business Admin (Tenant #1)** | `admin@default-hotspot.local` | Tenant `#1` (`default-hotspot`) | `/business` |
| **Business Admin (Tenant #2)** | `admin@tenant2.local` | Tenant `#2` (`tenant-2`) | `/business` |

### Key Application URLs
- **Admin Login**: `http://localhost:5173/login`
- **Super Admin Console**: `http://localhost:5173/super-admin`
- **Business Admin Console**: `http://localhost:5173/business`
- **Dynamic Customer Captive Portal (Tenant #1)**: `http://localhost:5173/portal/hotspot?tenantSlug=default-hotspot`
- **Dynamic Customer Captive Portal (Tenant #2)**: `http://localhost:5173/portal/hotspot?tenantSlug=tenant-2`

---

## Optional: Connecting to `cloudserv` Provisioning Service via SSH Tunnel

When testing real MikroTik enrollment token generation against `cloudserv` (`136.0.9.160`), open an SSH local port-forward tunnel using:

```bash
./scripts/start_cloudserv_tunnel.sh
```
This forwards `127.0.0.1:15432 -> cloudserv:5432` and `127.0.0.1:18085 -> cloudserv:8085` without exposing PostgreSQL or the provisioning admin endpoint publicly.
