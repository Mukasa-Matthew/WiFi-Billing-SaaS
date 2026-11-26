import { test, expect } from "./fixtures";

const PLANS = [
  { id: 1, name: "Daily 1,000", price: 1000, rateLimit: "2M/5M", sessionTimeout: 86400, sharedUsers: 1, availability: "ACTIVE", featured: true },
  { id: 2, name: "Weekly 5,000", price: 5000, rateLimit: "3M/8M", sessionTimeout: 604800, sharedUsers: 2, availability: "ACTIVE", featured: false },
];

test.describe("Delete confirmation", () => {
  test("covers the whole screen, and deletes only after confirming", async ({ page, api }) => {
    api.on("GET", "/api/packages", PLANS).on("DELETE", "/api/packages/2", { status: 204, raw: "" });
    await page.goto("/dashboard/packages");
    await page.getByRole("button", { name: "Delete Weekly 5,000" }).click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toContainText("Delete “Weekly 5,000”?");
    // Regression: the backdrop used to be clipped to the content area by an animated parent.
    const viewport = page.viewportSize()!;
    const backdrop = await page.locator('[role="presentation"]').filter({ has: dialog }).boundingBox();
    expect(backdrop).toMatchObject({ x: 0, y: 0, width: viewport.width, height: viewport.height });

    expect(api.calls("/api/packages/2", "DELETE")).toHaveLength(0);
    await dialog.getByRole("button", { name: "Delete plan" }).click();
    await expect(page.getByText("Plan deleted.")).toBeVisible();
    expect(api.calls("/api/packages/2", "DELETE")).toHaveLength(1);
  });

  test("Escape cancels without deleting", async ({ page, api }) => {
    api.on("GET", "/api/packages", PLANS);
    await page.goto("/dashboard/packages");
    await page.getByRole("button", { name: "Delete Daily 1,000" }).click();
    await expect(page.getByRole("alertdialog")).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("alertdialog")).toHaveCount(0);
    expect(api.calls(/\/api\/packages\/\d+$/, "DELETE")).toHaveLength(0);
  });
});
