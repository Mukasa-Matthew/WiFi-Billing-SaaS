import { test, expect } from "./fixtures";
import { presetRange } from "../../src/lib/paymentRange";

const WALLET = {
  businessName: "E2E Hotspot", available: 6940, pending: 0, reserved: 0, totalCollected: 366660, totalFees: 3000, totalWithdrawn: 321073,
  currency: "UGX", openWithdrawals: 0, maxWithdrawable: 5940, platformFeePercent: 0,
  limits: { min: 1000, max: 5_000_000, approvalRequired: false, livePayouts: true, feeReservePercent: 10, feeReserveMin: 1000 },
};
const entry = (id: number, type: string, amount: number, availableAfter: number) =>
  ({ id, type, amount, availableAfter, pendingAfter: 0, reservedAfter: 0, externalRef: `r${id}`, note: null, createdAt: "2026-11-20T09:00:00Z" });

test("statement asks for this month, balances, and shows fees as already deducted", async ({ page, api }) => {
  api.on("GET", "/api/wallet", WALLET)
     .on("GET", "/api/wallet/activity/report", {
       from: "2026-11-01", to: "2026-11-26", openingBalance: 10000, moneyIn: 2440, moneyOut: 5500, closingBalance: 6940,
       entries: [entry(3, "WITHDRAWAL_RESERVE", 5500, 6440), entry(2, "FEE_DEBIT", 60, 11940), entry(1, "COLLECTION_CREDIT", 1940, 11940)],
       page: 0, totalPages: 1, totalElements: 3,
     });
  await page.goto("/dashboard/wallet");
  await page.getByRole("tab", { name: "Statement" }).click();

  await expect(page.getByText("+UGX 2,440")).toBeVisible();
  await expect(page.getByText("−UGX 5,500").first()).toBeVisible();
  await expect(page.getByText("UGX 6,940").first()).toBeVisible();

  // The fee row: no minus sign, explained as already taken off.
  const feeRow = page.getByRole("row").filter({ hasText: "Gateway fee" });
  await expect(feeRow).toContainText("Already taken off the customer payment");
  await expect(feeRow).not.toContainText("−");

  const url = new URL(api.calls("/api/wallet/activity/report")[0].url());
  const month = presetRange("month");
  expect([url.searchParams.get("from"), url.searchParams.get("to")]).toEqual([month.from, month.to]);
  // Opened lazily: no statement request before the tab was clicked is fine, but exactly one after.
  expect(api.calls("/api/wallet/activity/report")).toHaveLength(1);
});
