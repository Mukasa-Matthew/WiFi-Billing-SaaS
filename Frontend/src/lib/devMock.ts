/**
 * Dev-only fixture backend. Active only when MOCK_API=1 (see .env.local).
 * Lets the whole UI be built and reviewed without the Spring Boot backend,
 * FreeRADIUS or Postgres running. Never enabled in production builds.
 */

const DAY = 86_400_000;
const now = Date.now();
const iso = (offsetMs: number) => new Date(now + offsetMs).toISOString();

/* deterministic pseudo-random so charts look stable across reloads */
let seed = 1337;
const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
const between = (min: number, max: number) => Math.floor(min + rnd() * (max - min));

const ROUTERS = [
  { id: 1, name: "Kampala Central Hub", status: "online" },
  { id: 2, name: "Ntinda Branch", status: "online" },
  { id: 3, name: "Entebbe Road Kiosk", status: "registered" },
  { id: 4, name: "Mukono Satellite", status: "offline" },
];

const PACKAGES = [
  { id: 1, name: "1 Hour Pass", price: 1000, rateLimit: "3M/3M", sessionTimeout: 3600, sharedUsers: 1, availability: "ACTIVE", featured: false, routerName: null },
  { id: 2, name: "Daily Unlimited", price: 3000, rateLimit: "5M/5M", sessionTimeout: 86400, sharedUsers: 2, availability: "ACTIVE", featured: true, routerName: null },
  { id: 3, name: "Weekly Saver", price: 15000, rateLimit: "10M/10M", sessionTimeout: 604800, sharedUsers: 3, availability: "ACTIVE", featured: false, routerName: "Kampala Central Hub" },
  { id: 4, name: "Monthly Home", price: 55000, rateLimit: "20M/20M", sessionTimeout: 2592000, sharedUsers: 5, availability: "ACTIVE", featured: true, routerName: null },
  { id: 5, name: "Student Night Owl", price: 800, rateLimit: "4M/4M", sessionTimeout: 28800, sharedUsers: 1, availability: "HIDDEN", featured: false, routerName: "Ntinda Branch" },
  { id: 6, name: "Legacy 30min", price: 500, rateLimit: "2M/2M", sessionTimeout: 1800, sharedUsers: 1, availability: "DISABLED", featured: false, routerName: null },
];

const NAMES = [
  "Nakato Sarah", "Okello Brian", "Namuli Grace", "Mugisha Peter", "Atim Joy",
  "Ssemujju Eric", "Birungi Linda", "Kato Daniel", "Auma Rebecca", "Tumusiime Alex",
  "Nabirye Ruth", "Odongo Samuel", "Kirabo Patience", "Wasswa Moses", "Acheng Mary",
  "Kyeyune Isaac", "Nansubuga Agnes", "Opio Charles", "Zawedde Esther", "Byaruhanga Job",
];

const phone = (i: number) => `+2567${String(60000000 + i * 131713).slice(0, 8)}`;
const mac = (i: number) =>
  ["AA", "BB", ...Array.from({ length: 4 }, (_, k) => (((i + 1) * (k + 7) * 23) % 256).toString(16).padStart(2, "0").toUpperCase())].join(":");

const customers = NAMES.map((name, i) => ({
  id: i + 1,
  name,
  phone: phone(i),
  notes: i % 4 === 0 ? "Walk-in, pays cash weekly" : i % 4 === 1 ? "Mobile money (MTN)" : "",
}));

/* ---------- /api/users ---------- */
const users = NAMES.map((name, i) => {
  const plan = PACKAGES[i % 4];
  const online = i % 3 !== 2;
  const expiresIn = i % 5 === 4 ? -between(1, 40) * 3_600_000 : between(1, 20) * 3_600_000;
  return {
    id: i + 1,
    customerId: i + 1,
    customerName: name,
    phone: phone(i),
    voucher: `WB-${(2000 + i * 37).toString(36).toUpperCase()}${i}`,
    packageName: plan.name,
    status: expiresIn < 0 ? (i % 10 === 9 ? "revoked" : "expired") : "active",
    online: expiresIn < 0 ? false : online,
    ip: `10.9.${1 + (i % 4)}.${20 + i}`,
    mac: mac(i),
    router: ROUTERS[i % ROUTERS.length].name,
    deviceLimit: plan.sharedUsers,
    devices: Math.min(plan.sharedUsers, 1 + (i % 3)),
    firstLogin: iso(-between(1, 120) * 3_600_000),
    expiresAt: iso(expiresIn),
    dataBytes: between(12, 9800) * 1024 * 1024,
    timeLeftSeconds: expiresIn < 0 ? 0 : Math.floor(expiresIn / 1000),
  };
});

/* ---------- /api/vouchers ---------- */
const VOUCHER_STATUSES = ["unused", "unused", "active", "used", "expired", "revoked"];
const SALE_TYPES = ["WALK_IN", "ONLINE", "GIVEAWAY"];
const vouchers = Array.from({ length: 48 }, (_, i) => {
  const plan = PACKAGES[i % PACKAGES.length];
  const status = VOUCHER_STATUSES[i % VOUCHER_STATUSES.length];
  const assigned = status !== "unused";
  const customer = assigned ? customers[i % customers.length] : null;
  return {
    id: i + 1,
    code: `WB${String(100000 + i * 7919).slice(0, 6)}`,
    status,
    packageName: plan.name,
    price: plan.price,
    customerId: customer?.id ?? null,
    customerName: customer?.name ?? null,
    customerPhone: customer?.phone ?? null,
    batchReference: `BATCH-${2026}-${String(1 + (i % 6)).padStart(2, "0")}`,
    saleType: SALE_TYPES[i % SALE_TYPES.length],
    delivery: i % 2 === 0 ? "PRINTED" : "SMS",
    archived: status === "revoked" && i % 7 === 0,
    createdAt: iso(-between(1, 60) * DAY),
    firstLogin: assigned ? iso(-between(1, 50) * DAY) : null,
    expiresAt: assigned ? iso(between(-20, 25) * DAY) : null,
  };
});

/* ---------- /api/finance/payments ---------- */
const METHODS = ["MTN", "AIRTEL", "CASH", "BANK_TRANSFER"];
const payments = Array.from({ length: 36 }, (_, i) => {
  const plan = PACKAGES[i % 4];
  const failed = i % 11 === 7;
  return {
    reference: `PAY-${String(880000 + i * 1471)}`,
    method: METHODS[i % METHODS.length],
    amount: plan.price,
    status: failed ? "FAILED" : i % 13 === 5 ? "PENDING" : "SUCCESSFUL",
    phone: phone(i % NAMES.length),
    voucher: i % 6 === 5 ? null : vouchers[i % vouchers.length].code,
    packageName: plan.name,
    router: ROUTERS[i % ROUTERS.length].name,
    createdAt: iso(i < 8 ? -between(1, 10) * 3_600_000 : -between(1, 30) * DAY),
    notes: failed ? "Insufficient balance on wallet" : null,
  };
});

/* ---------- /api/sessions/active + /api/sessions/history ---------- */
const session = (i: number, closed: boolean) => {
  const inOct = between(5, 900) * 1024 * 1024;
  const outOct = between(2, 300) * 1024 * 1024;
  const seconds = between(120, 36_000);
  return {
    radAcctId: 50_000 + i,
    username: users[i % users.length].voucher,
    framedIpAddress: `10.9.${1 + (i % 4)}.${20 + (i % 60)}`,
    callingStationId: mac(i),
    nasIpAddress: `10.9.0.${2 + (i % 4)}`,
    acctStartTime: iso(-(seconds * 1000) - (closed ? between(1, 10) * DAY : 0)),
    acctStopTime: closed ? iso(-between(1, 10) * DAY) : null,
    acctSessionTime: seconds,
    acctInputOctets: inOct,
    acctOutputOctets: outOct,
    totalOctets: inOct + outOct,
    status: closed ? "CLOSED" : "ONLINE",
    acctTerminateCause: closed ? ["User-Request", "Session-Timeout", "Idle-Timeout", "Admin-Reset"][i % 4] : null,
  };
};
const activeSessions = Array.from({ length: 14 }, (_, i) => session(i, false));
const sessionHistory = Array.from({ length: 40 }, (_, i) => session(i + 20, true));

/* ---------- /api/bindings ---------- */
const bindings = Array.from({ length: 12 }, (_, i) => ({
  id: i + 1,
  name: `${customers[i % customers.length].name.split(" ")[0]}-Static`,
  address: `10.9.${1 + (i % 4)}.${200 + i}`,
  mac: mac(i + 5),
  router: ROUTERS[i % ROUTERS.length].name,
  packageName: PACKAGES[i % 4].name,
  status: i % 5 === 4 ? "EXPIRED" : i % 11 === 10 ? "REMOVED" : "ACTIVE",
  synced: i % 6 !== 5,
  expiresAt: iso(between(-5, 40) * DAY),
}));

/* ---------- /api/feedback ---------- */
const COMMENTS = [
  "Connection drops around 8pm in the evening, otherwise great.",
  "Very fast today, thank you!",
  "Please add a cheaper 30 minute option.",
  "Voucher took two tries to activate.",
  "Signal is weak at the back of the building.",
  "Best hotspot in the area, keep it up.",
  "Payment went through but access was delayed by 5 minutes.",
];
const feedback = Array.from({ length: 16 }, (_, i) => ({
  id: i + 1,
  name: i % 4 === 3 ? null : customers[i % customers.length].name,
  phone: i % 5 === 4 ? null : phone(i),
  mac: mac(i + 2),
  router: ROUTERS[i % ROUTERS.length].name,
  rating: 1 + (i % 5),
  message: COMMENTS[i % COMMENTS.length],
  createdAt: iso(-between(0, 21) * DAY),
}));

/* ---------- /api/routers ---------- */
const routers = ROUTERS.map((r, i) => ({
  id: r.id,
  name: r.name,
  token: `tok_${(r.id * 918273).toString(36)}xk`,
  wgIp: `10.9.0.${2 + i}`,
  wgPublicKey: r.status === "offline" ? null : `${(r.id * 7771).toString(36)}Lq8ZxV2pQ9sT4uW7yA1bC3dE5fG=`,
  cpuLoad: r.status === "online" ? between(8, 62) : 0,
  memoryUsage: r.status === "online" ? between(25, 78) : 0,
  status: r.status,
  lastSeen: r.status === "offline" ? iso(-3 * DAY) : iso(-between(1, 110) * 1000),
  uptime: r.status === "online" ? `${between(2, 40)}d ${between(1, 23)}h` : null,
  hotspotReady: r.status === "online",
  radiusReady: r.status === "online",
  portalReady: r.status !== "offline",
}));

/* ---------- /api/dashboard/stats ---------- */
const RANGE_BUCKETS: Record<string, number> = {
  today: 24, "7d": 7, week: 7, lastweek: 7, month: 30, lastmonth: 30, year: 12, lastyear: 12,
};
function series(range: string) {
  const count = RANGE_BUCKETS[range] ?? 7;
  const hourly = range === "today";
  const monthly = range === "year" || range === "lastyear";
  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  return Array.from({ length: count }, (_, i) => {
    const back = count - 1 - i;
    const d = new Date(now - back * (hourly ? 3_600_000 : monthly ? 30 * DAY : DAY));
    const label = hourly
      ? `${String(d.getHours()).padStart(2, "0")}:00`
      : monthly
        ? months[d.getMonth()]
        : d.toLocaleDateString(undefined, { weekday: "short" });
    const scale = monthly ? 28 : hourly ? 1 : 1;
    const transactions = between(hourly ? 0 : 6, hourly ? 9 : 44) * scale;
    return {
      date: d.toISOString().slice(0, hourly ? 13 : 10),
      label,
      revenue: transactions * between(1200, 4200),
      transactions,
      users: between(hourly ? 2 : 18, hourly ? 26 : 140) * (monthly ? 2 : 1),
      registrations: between(hourly ? 0 : 3, hourly ? 5 : 28) * scale,
    };
  });
}

function dashboardStats(range: string) {
  const revenueSeries = series(range);
  const revenueMonth = series("month").reduce((s, p) => s + p.revenue, 0);
  return {
    range,
    revenueToday: 418_000,
    revenueMonth,
    activeUsers: activeSessions.length,
    totalDataConsumedBytes: 1_942_000_000_000,
    totalRouters: routers.length,
    onlineRouters: routers.filter((r) => r.status === "online").length,
    totalVouchers: vouchers.filter((v) => v.status === "unused" || v.status === "active").length,
    activeVouchers: vouchers.filter((v) => v.status === "active").length,
    usedVouchers: vouchers.filter((v) => v.status === "used").length,
    expiredVouchers: vouchers.filter((v) => v.status === "expired" || v.status === "revoked").length,
    avgCpuLoad: Math.round(routers.reduce((s, r) => s + r.cpuLoad, 0) / routers.length),
    avgMemoryUsage: Math.round(routers.reduce((s, r) => s + r.memoryUsage, 0) / routers.length),
    dataMonthDownBytes: 812_000_000_000,
    dataMonthUpBytes: 133_000_000_000,
    totalCustomers: customers.length,
    newCustomersThisWeek: 4,
    weeklySales: revenueSeries.map((p) => ({ date: p.date, day: p.label, revenue: p.revenue, transactions: p.transactions })),
    revenueSeries,
    dailyActiveUsers: revenueSeries.map((p) => ({ date: p.date, label: p.label, users: p.users })),
    registrations: revenueSeries.map((p) => ({ date: p.date, label: p.label, registrations: p.registrations })),
    routerRevenue: routers.map((r, i) => ({
      routerId: r.id,
      name: r.name,
      status: r.status,
      revenueToday: r.status === "online" ? between(40_000, 180_000) : 0,
      revenueMonth: between(900_000, 6_400_000),
      revenueTotal: between(7_000_000, 48_000_000),
      transactions: between(80, 1400),
      activeUsers: r.status === "online" ? between(3, 32) : 0,
    })),
    topConsumers: activeSessions.slice(0, 5).map((s) => ({
      radAcctId: s.radAcctId, username: s.username,
      framedIpAddress: s.framedIpAddress, totalOctets: s.totalOctets, acctSessionTime: s.acctSessionTime,
    })),
    topDataUsers: [...users].sort((a, b) => b.dataBytes - a.dataBytes).slice(0, 6).map((u) => ({
      username: u.voucher, customer: u.customerName, phone: u.phone, package: u.packageName, octets: u.dataBytes,
    })),
    packagePerformance: PACKAGES.filter((p) => p.availability === "ACTIVE").map((p) => {
      const totalSold = between(40, 900);
      const monthlyRevenue = p.price * between(12, 180);
      return {
        id: p.id, name: p.name, price: p.price,
        activeUsers: between(2, 40), monthlyRevenue, totalSold,
        arpu: Math.round(monthlyRevenue / Math.max(1, between(10, 120))),
      };
    }),
  };
}

/* ---------- /api/settings ---------- */
const settings = {
  name: "Shemue Hotspot Co.",
  email: "ops@shemuehotspot.ug",
  supportPhone: "+256700112233",
  supportPhone2: "+256780445566",
  supportEmail: "support@shemuehotspot.ug",
  whatsappPhone: "+256700112233",
  brandColor: "#122B49",
  redirectUrl: "https://shemuehotspot.ug/welcome",
  voucherPrefix: "WB",
  voucherCodeLength: 8,
  voucherCodeFormat: "ALPHANUMERIC",
  portalShowPackages: true,
  notifyEmails: "ops@shemuehotspot.ug,finance@shemuehotspot.ug",
  notifyPhones: "+256700112233",
  portalTemplate: "midnight",
  tetheringProtection: "WARN",
  portalFont: "Inter",
  portalLanguage: "English",
  portalPrimaryColor: "#1D4ED8",
  portalSecondaryColor: "#0EA472",
  portalLogoSize: 72,
  portalShowDeviceLimits: true,
  portalShowDeviceMac: false,
  portalShowPackageTime: true,
  portalAllowPurchases: true,
  portalBuyForOther: true,
  portalShowFeedback: true,
  hasLogo: false,
  hasBackground: false,
};

/* ---------- /api/admin/* ---------- */
const TENANT_NAMES = [
  "Shemue Hotspot Co.", "Nile Breeze Networks", "Kabale Connect", "Jinja Fibre Hotspot",
  "Gulu Metro WiFi", "Masaka Link", "Arua Mesh", "Fort Portal Online",
];
const tenants = TENANT_NAMES.map((name, i) => ({
  id: i + 1,
  name,
  email: `ops@${name.toLowerCase().replace(/[^a-z]+/g, "")}.ug`,
  phone: phone(i + 3),
  role: "ROLE_ADMIN",
  status: i % 7 === 6 ? "SUSPENDED" : "ACTIVE",
  routerCount: between(1, 14),
  voucherCount: between(50, 3200),
  createdAt: iso(-between(10, 700) * DAY),
}));

const adminMetrics = {
  totalTenants: tenants.length,
  totalRouters: tenants.reduce((s, t) => s + t.routerCount, 0),
  onlineRouters: Math.round(tenants.reduce((s, t) => s + t.routerCount, 0) * 0.72),
  totalVouchers: tenants.reduce((s, t) => s + t.voucherCount, 0),
  totalTransactions: 48_219,
  totalRevenueUgx: 186_440_000,
  activeHotspotSessions: 312,
};

const PORTAL_PREVIEW = (template: string) => `<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
 body{margin:0;font-family:Inter,system-ui,sans-serif;background:#F5F7FA;color:#111827;display:grid;place-items:center;min-height:100vh}
 .card{background:#fff;border:1px solid #e2e8f0;border-radius:18px;padding:32px;width:320px;box-shadow:0 1px 2px rgba(0,0,0,.05);text-align:center}
 .logo{width:56px;height:56px;border-radius:14px;background:#122B49;margin:0 auto 16px}
 h1{font-size:18px;margin:0 0 4px}p{font-size:13px;color:#64748b;margin:0 0 20px}
 input{width:100%;box-sizing:border-box;padding:10px 12px;border:1px solid #cbd5e1;border-radius:10px;font-size:14px;margin-bottom:12px}
 button{width:100%;padding:11px;border:0;border-radius:10px;background:#1D4ED8;color:#fff;font-weight:600;font-size:14px}
 .tag{font-size:11px;color:#94a3b8;margin-top:18px}
</style></head>
<body><div class="card"><div class="logo"></div>
<h1>Shemue Hotspot Co.</h1><p>Enter your voucher code to get online</p>
<input placeholder="WB123456"><button>Connect</button>
<div class="tag">Template: ${template} &middot; mock preview</div></div></body></html>`;


/* ---------- /api/portal/* (customer-facing captive portal) ---------- */
const DEVICE = { mac: "AA:BB:3C:91:0E:77", ip: "10.9.1.48", router: "Kampala Central Hub" };

function portalConfig() {
  return {
    tenantName: settings.name,
    template: settings.portalTemplate,
    font: settings.portalFont,
    language: settings.portalLanguage,
    primaryColor: settings.portalPrimaryColor,
    logoSize: settings.portalLogoSize,
    hasLogo: settings.hasLogo,
    showPackages: settings.portalShowPackages,
    allowPurchases: settings.portalAllowPurchases,
    showDeviceLimits: settings.portalShowDeviceLimits,
    showPackageTime: settings.portalShowPackageTime,
    showDeviceMac: settings.portalShowDeviceMac,
    buyForOther: settings.portalBuyForOther,
    showFeedback: settings.portalShowFeedback,
    supportPhone: settings.supportPhone,
    whatsappPhone: settings.whatsappPhone,
    supportEmail: settings.supportEmail,
    redirectUrl: settings.redirectUrl,
    voucherCodeLength: settings.voucherCodeLength,
    device: DEVICE,
    packages: PACKAGES.filter((p) => p.availability === "ACTIVE").map((p) => ({
      id: p.id,
      name: p.name,
      price: p.price,
      duration: p.sessionTimeout,
      rateLimit: p.rateLimit,
      deviceLimit: p.sharedUsers,
      featured: p.featured,
    })),
  };
}

function portalRedeem(code: string) {
  const entered = String(code || "").trim().toUpperCase();
  if (entered.length < 4) {
    return { ok: false, reason: "INVALID", message: "That code looks too short. Check the printed voucher and try again." };
  }
  const voucher = vouchers.find((v) => v.code.toUpperCase() === entered);
  if (!voucher) {
    return { ok: false, reason: "NOT_FOUND", message: "We do not recognise that code. Check for mistyped letters, or buy a package below." };
  }
  if (voucher.status === "expired" || voucher.status === "revoked") {
    return { ok: false, reason: "EXPIRED", message: "This voucher has already expired. Buy a new package below to get back online." };
  }
  if (voucher.status === "used") {
    return { ok: false, reason: "DEVICE_LIMIT", message: "This voucher is already in use on another device. Disconnect that device or buy a new package." };
  }
  const plan = PACKAGES.find((p) => p.name === voucher.packageName) || PACKAGES[0];
  return {
    ok: true,
    session: {
      code: voucher.code,
      packageName: plan.name,
      rateLimit: plan.rateLimit,
      deviceLimit: plan.sharedUsers,
      secondsRemaining: plan.sessionTimeout,
      bytesUsed: 0,
      device: DEVICE,
      redirectUrl: settings.redirectUrl,
    },
  };
}

/* ---------- router ---------- */
type Json = unknown;

export function mockRoute(method: string, path: string[], search: URLSearchParams, body: Record<string, unknown> = {}): Json | undefined {
  const route = path.join("/");

  if (method === "POST" && route === "auth/login") {
    // Sign in with super@… to land in the platform-admin area, anything else
    // for the tenant workspace. Any password is accepted by the fixtures.
    const email = String(body.email || "ops@shemuehotspot.ug");
    const superadmin = email.toLowerCase().startsWith("super");
    return {
      token: "mock.dev.jwt.token",
      tenantId: 1,
      name: superadmin ? "Platform Operations" : "Shemue Hotspot Co.",
      email,
      role: superadmin ? "ROLE_SUPERADMIN" : "ROLE_ADMIN",
    };
  }

  if (method === "GET") {
    switch (route) {
      case "dashboard/stats": return dashboardStats(search.get("range") || "7d");
      case "users": return users;
      case "customers": return customers;
      case "vouchers": return vouchers;
      case "packages": return PACKAGES;
      case "routers": return routers;
      case "bindings": return bindings;
      case "feedback": return feedback;
      case "finance/payments": return payments;
      case "sessions/active": return activeSessions;
      case "sessions/history": return sessionHistory;
      case "settings": return settings;
      case "settings/portal-preview": return PORTAL_PREVIEW(search.get("template") || "modern");
      case "admin/metrics": return adminMetrics;
      case "admin/tenants": return tenants;
      case "portal/config": return portalConfig();
    }
    if (/^routers\/\d+\/hosts$/.test(route)) {
      return users.slice(0, 6).map((u) => ({ ip: u.ip, mac: u.mac, hostname: u.customerName, comment: u.packageName }));
    }
    if (/^routers\/\d+\/tasks$/.test(route)) {
      return [
        { id: 1, action: "SYNC_HOTSPOT_PROFILE", status: "DONE", createdAt: iso(-2 * 3_600_000) },
        { id: 2, action: "PUSH_RADIUS_SECRET", status: "DONE", createdAt: iso(-6 * 3_600_000) },
        { id: 3, action: "REBUILD_WALLED_GARDEN", status: "PENDING", createdAt: iso(-15 * 60_000) },
      ];
    }
  }

  if (method === "POST" && route === "portal/redeem") return portalRedeem(String(body.code || ""));
  if (method === "POST" && route === "portal/purchase") {
    return { ok: true, reference: `PAY-${between(100000, 999999)}`, message: "Approve the payment prompt on your phone to get online." };
  }
  if (method === "POST" && route === "portal/feedback") return { ok: true, message: "Thank you — your feedback reached the operator." };

  /* every mutation succeeds so the UI flows can be walked end to end */
  if (method !== "GET") {
    if (route === "settings") return settings;
    if (route.startsWith("settings/assets")) return settings;
    return { ok: true, message: "Mock backend: change accepted (not persisted)." };
  }

  return undefined;
}
