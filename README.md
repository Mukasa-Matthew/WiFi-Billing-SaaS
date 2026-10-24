# AirPairy

_Connecting WiFi. Simplifying Business._ — a product by Thewton.

Multi-tenant WiFi Billing SaaS

Multi-tenant WiFi billing for MikroTik RouterOS v7. The platform combines a Spring Boot API, a Next.js tenant/admin dashboard, PostgreSQL-backed FreeRADIUS data, WireGuard router management, and a dynamically configured captive portal.

## Repository map

```text
.
├── Backend/                 Spring Boot API, billing, RADIUS data and RouterOS generation
│   └── src/main/resources/static/hotspot/
│                            Router-hosted captive portal templates
├── Frontend/                Next.js tenant and super-administrator dashboard
├── deployment/              Host-specific, reviewed operational helpers
├── docs/                    Architecture and operations runbooks
└── docker-compose.yml       Supported full local/application stack
```

The component Compose file under `Backend/` is retained for isolated backend development. The root Compose file is the canonical full-stack definition.

## Start locally

1. Copy `Backend/.env.example` to `Backend/.env` and configure development secrets.
2. Copy the frontend example environment when needed.
3. Set `POSTGRES_PASSWORD` in your shell or a root `.env` file.
4. Run `docker compose up --build`.

Never commit `.env` files, router tokens, RADIUS secrets, WireGuard private keys, or database credentials.

## Engineering documentation

- [System architecture](docs/architecture.md)
- [MikroTik provisioning lifecycle](docs/provisioning.md)
- [Production operations and recovery](docs/operations.md)
- [Deployment helper ownership](deployment/README.md)

## Verification

```bash
cd Backend && ./mvnw test
cd Frontend && npm run lint && npm run build
```

Physical RouterOS, FreeRADIUS and WireGuard verification must be performed in a test environment using `docs/operations.md`.
