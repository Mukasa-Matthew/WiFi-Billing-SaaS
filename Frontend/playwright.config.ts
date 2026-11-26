import { defineConfig, devices } from "@playwright/test";

/**
 * Browser tests for the money flows (withdraw, renew, payments, confirmations).
 * They run against the production build with the API mocked per test, so no
 * backend or database is needed. Build first: `npm run build`, then `npm run test:e2e`.
 */
const PORT = Number(process.env.E2E_PORT || 3917);

export default defineConfig({
  testDir: "./tests/e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: process.env.CI ? [["github"], ["list"]] : "list",
  use: {
    baseURL: `http://127.0.0.1:${PORT}`,
    timezoneId: "Africa/Kampala",
    locale: "en-UG",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    // Locally use the installed Google Chrome; CI installs Playwright's Chromium.
    { name: "desktop", use: { ...devices["Desktop Chrome"], channel: process.env.CI ? undefined : "chrome" }, grepInvert: /@phone/ },
    { name: "phone", use: { ...devices["Pixel 7"], channel: process.env.CI ? undefined : "chrome" }, grep: /@phone/ },
  ],
  webServer: {
    command: "node .next/standalone/server.js",
    url: `http://127.0.0.1:${PORT}/login`,
    env: { PORT: String(PORT), HOSTNAME: "127.0.0.1" },
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});
