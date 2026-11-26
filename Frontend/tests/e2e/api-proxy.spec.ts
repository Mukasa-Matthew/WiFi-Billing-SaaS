import { test, expect } from "@playwright/test";
import http from "node:http";
import zlib from "node:zlib";

/**
 * The real /api proxy (src/app/api/[...path]/route.ts) against a stub backend.
 * Other tests mock /api inside the browser, so they never exercise the proxy.
 *
 * Regression: Next 16.4.6 compresses route-handler output itself, and the proxy
 * also gzipped JSON, so browsers got gzip inside gzip. The sign-in reply was
 * unreadable, no token was stored, and every dashboard login looped to /login.
 */
const BACKEND_PORT = Number(process.env.E2E_BACKEND_PORT || 3916);
const SCRIPT = `# router script\n${":put line;\n".repeat(400)}`;

// One worker for the whole file: the stub backend listens on a fixed port.
test.describe.configure({ mode: "serial" });

let backend: http.Server;

test.beforeAll(async () => {
  backend = http.createServer((req, res) => {
    if (req.url?.startsWith("/api/router/")) {
      res.writeHead(200, { "content-type": "text/plain", "content-length": Buffer.byteLength(SCRIPT) });
      res.end(SCRIPT);
      return;
    }
    // Large enough (>1 KB) for compression to kick in.
    const body = JSON.stringify({ token: "tok-123", authorization: req.headers.authorization ?? null, pad: "x".repeat(4000) });
    res.writeHead(200, { "content-type": "application/json" });
    res.end(body);
  });
  await new Promise<void>((resolve) => backend.listen(BACKEND_PORT, "127.0.0.1", resolve));
});

test.afterAll(async () => {
  await new Promise<void>((resolve) => backend.close(() => resolve()));
});

function rawGet(path: string, headers: Record<string, string>) {
  const base = new URL(test.info().project.use.baseURL!);
  return new Promise<{ headers: http.IncomingHttpHeaders; body: Buffer }>((resolve, reject) => {
    http.get({ host: base.hostname, port: base.port, path, headers }, (res) => {
      const chunks: Buffer[] = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => resolve({ headers: res.headers, body: Buffer.concat(chunks) }));
    }).on("error", reject);
  });
}

test("a browser can read proxied JSON and the bearer token reaches the backend", async ({ page }) => {
  await page.goto("/login");
  const data = await page.evaluate(async () => {
    const r = await fetch("/api/auth/login", { method: "POST", headers: { Authorization: "Bearer abc" } });
    return r.json();
  });
  expect(data.token).toBe("tok-123");
  expect(data.authorization).toBe("Bearer abc");
});

test("gzipped JSON is compressed exactly once", async () => {
  const res = await rawGet("/api/wallet", { "Accept-Encoding": "gzip" });
  expect(res.headers["content-encoding"]).toBe("gzip");
  const once = zlib.gunzipSync(res.body);
  expect(JSON.parse(once.toString("utf8")).token).toBe("tok-123");
});

test("router scripts pass through uncompressed and byte for byte", async () => {
  const res = await rawGet("/api/router/provision/tok_x/management.rsc", { "Accept-Encoding": "gzip" });
  expect(res.headers["content-encoding"]).toBeUndefined();
  expect(res.body.toString("utf8")).toBe(SCRIPT);
});
