import { test, expect } from "./fixtures";

const VOUCHERS = [
  { id: 1, code: "K7Q2M9", status: "unused", packageName: "Daily 1,000", price: 1000, customerId: null, customerName: null, customerPhone: null, batchReference: "B-12", saleType: "INVENTORY", delivery: "", archived: false, createdAt: "2026-11-20T09:00:00Z", firstLogin: null, expiresAt: null },
  { id: 2, code: "P9X1AB", status: "active", packageName: "Weekly 5,000", price: 5000, customerId: 3, customerName: "Grace N.", customerPhone: "0772123456", batchReference: null, saleType: "ONLINE", delivery: "", archived: false, createdAt: "2026-11-21T09:00:00Z", firstLogin: "2026-11-21T10:00:00Z", expiresAt: "2026-11-28T10:00:00Z" },
];
const ROUTERS = [{ id: 4, name: "Main gate", wgIp: "10.8.0.4", registered: true, cpuLoad: 3, memoryUsage: 40, status: "online", lastSeen: "2026-11-26T09:00:00Z", uptime: "3d 4h", hotspotReady: true, radiusReady: true, portalReady: true }];

test.describe("@phone layouts", () => {
  test("@phone vouchers: code, plan and status fit; the action is reachable", async ({ page, api }) => {
    api.on("GET", "/api/vouchers", VOUCHERS);
    await page.goto("/dashboard/vouchers");
    await page.getByRole("tab", { name: /^All/ }).click();
    const row = page.getByRole("row").filter({ hasText: "P9X1AB" });
    await expect(row).toContainText("Weekly 5,000 · In use");
    // Scroll to the row, then require the button fully visible: fails if the table clips it sideways.
    await row.scrollIntoViewIfNeeded();
    await expect(row.getByRole("button", { name: "Revoke" })).toBeInViewport({ ratio: 1 });
  });

  test("@phone routers: name, status and actions fit", async ({ page, api }) => {
    api.on("GET", "/api/routers", ROUTERS);
    await page.goto("/dashboard/routers");
    const row = page.getByRole("row").filter({ hasText: "Main gate" });
    await expect(row).toContainText("Online");
    await row.scrollIntoViewIfNeeded();
    await expect(row.getByRole("button", { name: "Actions for Main gate" })).toBeInViewport({ ratio: 1 });
  });
});
