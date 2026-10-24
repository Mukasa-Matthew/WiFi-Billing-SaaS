# AirPairy UI/UX audit

Audit date: 2026-10-25

## Current foundation

- The application has 29 tenant/admin/auth routes plus a mobile-first captive portal.
- Next.js 16, React 19, Tailwind CSS 4 and Phosphor Icons are already installed. No animation or chart dependency is required for the current refinement phase.
- Shared primitives in `BillingUI.tsx` cover panels, buttons, fields, headers, statistics, filters, empty states and tables, but several older pages still use one-off equivalents.
- Light/dark semantic colors and route-level loading shells already exist. The captive portal has isolated theme tokens and deliberately avoids remote assets.

## Prioritized findings

### P0 — reliability and accessibility

- Add recoverable route error boundaries for tenant and admin workspaces.
- Trap focus, restore invoking focus and lock background scrolling for shared dialogs.
- Preserve backend-confirmed states for payments and withdrawals; do not add optimistic completion UI.
- Replace generic table loading messages with dimensionally stable row skeletons.
- Keep all motion disabled or effectively instant under `prefers-reduced-motion`.

### P1 — consistency and responsive behavior

- Migrate bespoke dialogs in wallet, subscriptions and tenant administration to the shared dialog.
- Migrate bespoke page headers, tabs, buttons and fields to shared primitives, beginning with vouchers, sessions and settings.
- Audit wide financial and accounting tables at 320–390px; retain horizontal scrolling where column comparison matters and introduce card views only where it materially improves comprehension.
- Standardize status language and tone mapping across payments, vouchers, routers, subscriptions and reconciliation.
- Ensure mobile primary actions are full-width or at least 44px high where they are the page's main task.

### P2 — loading, motion and feedback

- Add context-specific skeletons for package cards, wallet summaries, routers and charts.
- Consolidate inline success/error messages through shared notices and toasts while retaining persistent errors where recovery context matters.
- Use the shared fast entrance easing for route content, dialogs and toasts; avoid staggered table animation.
- Add explicit stale-data timestamps only where the API exposes reliable observation time.

### P3 — page polish and performance

- Split the 580-line settings screen into focused sections without changing its API contract.
- Review dashboard chart semantics, keyboard descriptions and large-number formatting.
- Reduce duplicate form/table styles to lower maintenance cost and CSS churn.
- Measure bundle size and Core Web Vitals against a production build before claiming performance targets.

## Route review summary

- Tenant overview: strong information coverage; needs chart accessibility and narrower mobile density.
- Customers/users/vouchers/bindings: shared tables are the right base; voucher page still has bespoke header/tabs.
- Packages/routers: useful flows; add structural card/table loading and normalize action menus.
- Sessions/logs: data dense and visually inconsistent with newer shared components; high-value migration target.
- Wallet/payments/subscription: preserve conservative transaction state handling; unify dialogs and status presentation.
- Settings/gateway: functionally comprehensive but long; needs component extraction and clearer section hierarchy.
- Admin overview/health/routers: relatively consistent; tenant, subscription and finance pages contain bespoke modal/table patterns.
- Login/password reset: branded and responsive; validate touch targets, autocomplete and live error announcements during visual QA.
- Captive portal: appropriately self-contained and dependency-light; verify real RouterOS templates and 320px behavior before further visual changes.

## Verification matrix

For each implementation checkpoint: targeted ESLint, TypeScript/production build, keyboard dialog flow, reduced-motion mode, and representative widths of 320, 375, 390, 768, 1024 and 1440px. Browser screenshots require a runnable authenticated environment and must not be reported unless captured.
