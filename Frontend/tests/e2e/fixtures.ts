import { test as base, expect, type Page, type Request } from "@playwright/test";

/** A canned API reply: JSON body (default 200), or a full response. */
export type Reply = unknown | { status: number; body?: unknown; headers?: Record<string, string>; raw?: string };
type Handler = { method: string; path: string | RegExp; reply: Reply | ((req: Request, url: URL) => Reply | Promise<Reply>) };

export const ACTIVE_SUBSCRIPTION = {
  status: "ACTIVE", displayStatus: "Active", planId: 2, planName: "Standard", price: 50000,
  startDate: "2026-08-01T00:00:00Z", expiresAt: "2026-12-20T09:00:00Z", graceUntil: "2026-12-22T09:00:00Z",
  daysRemaining: 24, hoursRemaining: 580, inTrial: false, inGrace: false, isExpired: false, isSuspended: false,
  canAcceptCustomerPayments: true, subscribed: true,
  availablePlans: [{ id: 2, name: "Standard", price: 50000, durationUnit: "DAYS", durationValue: 30, description: "" }],
  paymentHistory: [], periodHistory: [], invoices: [], notifications: [],
};

/**
 * Mocks every /api call for one page. Tests register specific handlers with `on()`;
 * anything unregistered gets a harmless empty reply. Every request is recorded so tests
 * can assert what the UI actually sent.
 */
export class MockApi {
  readonly requests: Request[] = [];
  private handlers: Handler[] = [];

  constructor(private page: Page) {}

  on(method: string, path: string | RegExp, reply: Handler["reply"]) {
    this.handlers.unshift({ method, path, reply }); // later registrations win
    return this;
  }

  /** Requests to a path (exact pathname or regex) with an optional method. */
  calls(path: string | RegExp, method?: string) {
    return this.requests.filter((r) => {
      const url = new URL(r.url());
      return (!method || r.method() === method) && (typeof path === "string" ? url.pathname === path : path.test(url.pathname + url.search));
    });
  }

  async install() {
    await this.page.route("**/api/**", async (route) => {
      const req = route.request();
      const url = new URL(req.url());
      this.requests.push(req);
      const h = this.handlers.find((x) => x.method === req.method() &&
        (typeof x.path === "string" ? x.path === url.pathname : x.path.test(url.pathname + url.search)));
      const reply: Reply = h ? (typeof h.reply === "function" ? await (h.reply as (r: Request, u: URL) => Reply)(req, url) : h.reply) : fallback(url.pathname);
      const full = isFull(reply) ? reply : { status: 200, body: reply };
      await route.fulfill({
        status: full.status,
        headers: { "content-type": "application/json", ...(full.headers ?? {}) },
        body: full.raw ?? JSON.stringify(full.body ?? {}),
      });
    });
  }
}

function isFull(r: Reply): r is { status: number; body?: unknown; headers?: Record<string, string>; raw?: string } {
  return !!r && typeof r === "object" && "status" in r && typeof (r as { status: unknown }).status === "number" && ("body" in r || "raw" in r || "headers" in r);
}

function fallback(path: string): unknown {
  if (path === "/api/subscription") return ACTIVE_SUBSCRIPTION;
  if (/^\/api\/(tenant|settings|dashboard)/.test(path)) return {};
  return [];
}

/** Signed-in hotspot owner with a mocked API. */
export const test = base.extend<{ api: MockApi }>({
  api: async ({ page }, provide) => {
    await page.addInitScript(() => {
      localStorage.setItem("wb_token", "e2e-token");
      localStorage.setItem("wb_user", JSON.stringify({ token: "e2e-token", tenantId: 1, name: "E2E Hotspot", email: "owner@example.test", role: "ROLE_ADMIN", mustChangePassword: false }));
    });
    const api = new MockApi(page);
    await api.install();
    await provide(api);
  },
});

export { expect };
