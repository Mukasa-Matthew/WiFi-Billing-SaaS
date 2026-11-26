import { test, expect, type MockApi } from "./fixtures";

const WALLET = {
  businessName: "E2E Hotspot", available: 45587, pending: 0, reserved: 0,
  totalCollected: 366660, totalFees: 3000, totalWithdrawn: 321073, currency: "UGX", openWithdrawals: 0,
  limits: { min: 1000, max: 5_000_000, approvalRequired: false, livePayouts: true, feeReservePercent: 10, feeReserveMin: 1000 },
  maxWithdrawable: 41442, platformFeePercent: 0,
};

async function openWithdraw(api: MockApi, page: import("@playwright/test").Page) {
  api.on("GET", "/api/wallet", WALLET);
  await page.goto("/dashboard/wallet");
  await expect(page.getByText("UGX 45,587").first()).toBeVisible();
  await page.getByRole("button", { name: "Withdraw" }).click();
  await page.getByLabel("Amount (UGX)").fill("1000");
  await page.getByLabel("Mobile money number").fill("0755032436");
  await page.getByLabel("Reason").fill("Rent");
}

test.describe("Withdraw", () => {
  test("sends a code, auto-submits the 6 digits once with an idempotency key", async ({ page, api }) => {
    api.on("POST", "/api/wallet/withdrawals/otp", { sent: true, devCode: null, sentTo: "o•••@example.test" })
       .on("POST", "/api/wallet/withdrawals", { id: 9, status: "PROCESSING" });
    await openWithdraw(api, page);

    await page.getByRole("button", { name: "Send verification code" }).click();
    await expect(page.getByText("Check your email")).toBeVisible();
    await expect(page.getByText("o•••@example.test")).toBeVisible();
    await expect(page.getByText(/Resend in 0:5\d|Resend in 1:00/)).toBeVisible();

    await page.getByLabel("6-digit verification code").pressSequentially("482913");
    await expect(page.getByText("Withdrawal request submitted for processing.")).toBeVisible();

    const posts = api.calls("/api/wallet/withdrawals", "POST");
    expect(posts).toHaveLength(1);
    expect(posts[0].postDataJSON()).toEqual({ amount: 1000, phone: "0755032436", reason: "Rent", otp: "482913" });
    expect(posts[0].headers()["idempotency-key"]).toBeTruthy();
    await expect(page.getByRole("dialog")).toHaveCount(0);
  });

  test("a wrong code shows the server's message and clears the boxes", async ({ page, api }) => {
    api.on("POST", "/api/wallet/withdrawals/otp", { sent: true, sentTo: "o•••@example.test" })
       .on("POST", "/api/wallet/withdrawals", { status: 400, body: { message: "Invalid or expired code" } });
    await openWithdraw(api, page);
    await page.getByRole("button", { name: "Send verification code" }).click();
    await page.getByLabel("6-digit verification code").pressSequentially("111111");

    await expect(page.getByRole("alert").filter({ hasText: "Invalid or expired code" })).toBeVisible();
    await expect(page.getByLabel("6-digit verification code")).toHaveValue("");
    await expect(page.getByRole("dialog")).toBeVisible();
  });

  test("a code sent under a minute ago goes straight to code entry instead of erroring", async ({ page, api }) => {
    api.on("POST", "/api/wallet/withdrawals/otp", { status: 429, body: { message: "A code was just sent — check your email or wait a minute" } });
    await openWithdraw(api, page);
    await page.getByRole("button", { name: "Send verification code" }).click();

    await expect(page.getByText("Check your email")).toBeVisible();
    await expect(page.getByText(/less than a minute ago/)).toBeVisible();
    await expect(page.getByRole("alert").filter({ hasText: "A code was just sent" })).toHaveCount(0);
  });

  test("going back and continuing reuses the code instead of requesting another", async ({ page, api }) => {
    api.on("POST", "/api/wallet/withdrawals/otp", { sent: true, sentTo: "o•••@example.test" });
    await openWithdraw(api, page);
    await page.getByRole("button", { name: "Send verification code" }).click();
    await expect(page.getByText("Check your email")).toBeVisible();
    await page.getByRole("button", { name: "Back" }).click();
    await page.getByRole("button", { name: "Continue" }).click();
    await expect(page.getByText("Check your email")).toBeVisible();
    expect(api.calls("/api/wallet/withdrawals/otp", "POST")).toHaveLength(1);
  });

  test("@phone the wallet balance and Withdraw button fit a phone screen", async ({ page, api }) => {
    api.on("GET", "/api/wallet", WALLET);
    await page.goto("/dashboard/wallet");
    const button = page.getByRole("button", { name: "Withdraw" });
    await expect(button).toBeInViewport();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });
});
