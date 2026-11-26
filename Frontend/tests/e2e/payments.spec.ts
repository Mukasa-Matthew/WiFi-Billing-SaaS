import { test, expect } from "./fixtures";
import { presetRange } from "../../src/lib/paymentRange";

const REPORT = {
  from: "2026-11-20", to: "2026-11-26",
  summary: { collected: 186500, mobileMoney: 171000, walkIn: 15500, total: 214, successful: 196, pending: 3, failed: 15 },
  payments: [{ id: 1, reference: "TX1", method: "MOMO", amount: 1000, status: "SUCCESSFUL", phone: "0790001122", voucher: "K7Q2M", packageName: "Daily", router: "Gate", createdAt: "2026-11-26T09:00:00Z", notes: null, mobileMoneyId: null }],
  page: 0, totalPages: 9, totalElements: 214,
};

test.describe("Payments", () => {
  test("loads the last 7 days in Kampala time and shows server totals", async ({ page, api }) => {
    api.on("GET", "/api/finance/payments/report", REPORT);
    await page.goto("/dashboard/payments");

    await expect(page.getByText("UGX 186,500")).toBeVisible();
    await expect(page.getByText("UGX 15,500")).toBeVisible();
    // Other-network mobile money is labelled as mobile money, never walk-in.
    await expect(page.getByText("Mobile money", { exact: true }).last()).toBeVisible();

    const { from, to } = presetRange("7d");
    const first = new URL(api.calls("/api/finance/payments/report")[0].url());
    expect(first.searchParams.get("from")).toBe(from);
    expect(first.searchParams.get("to")).toBe(to);
    expect(first.searchParams.get("status")).toBe("ALL");
  });

  test("changing the range and status refetches from page 1", async ({ page, api }) => {
    api.on("GET", "/api/finance/payments/report", REPORT);
    await page.goto("/dashboard/payments");
    await expect(page.getByText("UGX 186,500")).toBeVisible();

    await page.getByRole("tab", { name: "Today" }).click();
    await page.getByRole("tab", { name: /^Failed/ }).click();
    await expect.poll(() => {
      const last = new URL(api.calls("/api/finance/payments/report").at(-1)!.url());
      return [last.searchParams.get("from"), last.searchParams.get("to"), last.searchParams.get("status"), last.searchParams.get("page")];
    }).toEqual([presetRange("today").from, presetRange("today").to, "FAILED", "0"]);
  });

  test("Export CSV downloads every row for the chosen range from the server", async ({ page, api }) => {
    api.on("GET", "/api/finance/payments/report", REPORT)
       .on("GET", "/api/finance/payments/export", {
         status: 200, raw: "Date,Reference\r\n\"2026-11-26 12:00\",\"TX1\"\r\n",
         headers: { "content-type": "text/csv", "content-disposition": 'attachment; filename="payments-2026-11-20-to-2026-11-26.csv"' },
       });
    await page.goto("/dashboard/payments");
    await expect(page.getByText("UGX 186,500")).toBeVisible();

    const download = page.waitForEvent("download");
    await page.getByRole("button", { name: "Export CSV" }).click();
    expect((await download).suggestedFilename()).toBe("payments-2026-11-20-to-2026-11-26.csv");
    const url = new URL(api.calls("/api/finance/payments/export")[0].url());
    expect(url.searchParams.get("status")).toBe("ALL");
  });

  test("a custom range ending before it starts is caught before calling the server", async ({ page, api }) => {
    api.on("GET", "/api/finance/payments/report", REPORT);
    await page.goto("/dashboard/payments");
    await page.getByRole("tab", { name: "Custom" }).click();
    await page.getByLabel("Start date", { exact: true }).fill("2026-11-25");
    // 25–26 Nov is still valid, so that change fetches; wait for it before counting.
    await expect.poll(() => api.calls(/from=2026-11-25/).length).toBeGreaterThan(0);
    const before = api.calls("/api/finance/payments/report").length;
    await page.getByLabel("End date", { exact: true }).fill("2026-11-10");
    await expect(page.getByText("The start date must be on or before the end date.")).toBeVisible();
    expect(api.calls("/api/finance/payments/report").length).toBe(before);
  });
});
