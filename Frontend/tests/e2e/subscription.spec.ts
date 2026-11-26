import { test, expect, ACTIVE_SUBSCRIPTION } from "./fixtures";

test.describe("Subscription renewal", () => {
  test("pays from the wallet when it covers the plan, once, with an idempotency key", async ({ page, api }) => {
    api.on("GET", "/api/wallet", { available: 60000 })
       .on("POST", "/api/subscription/pay-from-wallet", { reference: "SUB-1", status: "SUCCESSFUL", message: "ok" });
    await page.goto("/dashboard/subscription");
    await page.getByRole("button", { name: "Renew", exact: true }).click();

    const wallet = page.getByRole("button", { name: /Wallet/ });
    await expect(wallet).toHaveAttribute("aria-pressed", "true");
    await page.getByRole("button", { name: "Pay UGX 50,000" }).click();

    await expect(page.getByText("Paid from your wallet. Your plan is renewed.")).toBeVisible();
    const posts = api.calls("/api/subscription/pay-from-wallet", "POST");
    expect(posts).toHaveLength(1);
    expect(posts[0].postDataJSON()).toEqual({ planId: 2 });
    expect(posts[0].headers()["idempotency-key"]).toBeTruthy();
  });

  test("mobile money: sends the prompt, then confirms when the payment lands", async ({ page, api }) => {
    let polls = 0;
    api.on("GET", "/api/wallet", { available: 0 })
       .on("POST", "/api/subscription/checkout", { reference: "CHK-1", status: "PENDING", message: "sent" })
       .on("GET", "/api/subscription/checkout/CHK-1", () => ({ status: ++polls < 2 ? "PENDING" : "SUCCESSFUL", message: "done" }));
    await page.goto("/dashboard/subscription");
    await page.getByRole("button", { name: "Renew", exact: true }).click();

    await expect(page.getByRole("button", { name: /Wallet/ })).toBeDisabled();
    await page.getByLabel("Phone number").fill("0772123456");
    await page.getByRole("button", { name: "Pay UGX 50,000" }).click();
    await expect(page.getByText(/Check your phone and enter your PIN/)).toBeVisible();
    await expect(page.getByText("Payment received. Your plan is renewed.")).toBeVisible({ timeout: 15_000 });
    expect(api.calls("/api/subscription/checkout", "POST")[0].postDataJSON()).toEqual({ planId: 2, phone: "0772123456" });
  });

  test("a plan ending soon shows one warning; an active one shows none", async ({ page, api }) => {
    api.on("GET", "/api/subscription", { ...ACTIVE_SUBSCRIPTION, daysRemaining: 1, hoursRemaining: 30 });
    await page.goto("/dashboard/subscription");
    await expect(page.getByText("Your plan ends in 1 day")).toBeVisible();

    api.on("GET", "/api/subscription", ACTIVE_SUBSCRIPTION);
    await page.reload();
    await expect(page.getByText("Standard").first()).toBeVisible();
    await expect(page.getByText(/Your plan ends in/)).toHaveCount(0);
  });
});
