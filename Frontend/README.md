# WiFi Billing dashboard

Next.js tenant and super-administrator interface for WiFi Billing. Tenant identity comes from the backend-issued JWT; the frontend does not make tenant authorization decisions locally.

## Development

```bash
npm ci
npm run dev
```

The browser uses the same-origin `/api` proxy by default. Configure `BACKEND_INTERNAL_URL` for the Next.js server. Use `NEXT_PUBLIC_API_URL` only for a deliberate cross-origin deployment.

## Checks

```bash
npm run lint
npm run build
```

Read the root architecture and provisioning documentation before changing router onboarding or status semantics.
