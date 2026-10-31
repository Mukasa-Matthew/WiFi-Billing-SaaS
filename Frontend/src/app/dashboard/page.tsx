"use client";
import Link from "next/link";
import { useMemo, useState, useSyncExternalStore } from "react";
import { useAuth } from "@/context/AuthContext";
import { DashboardLoading, ListSkeleton } from "@/components/LoadingUI";
import {
  ArrowClockwise, ArrowDown, ArrowRight, CellTower, Eye, EyeSlash, Plus, Radio, Stack, Ticket, Users, WarningCircle,
} from "@/components/icons";
import {
  Badge, Card, CardLink, CountUp, ErrorNotice, Segmented, StatCard, StatusDot, button, buttonGhost,
  bytes, compactNumber, field, relativeTime, useApi, useNow, useResource,
} from "@/components/BillingUI";
import { BarChart, Meter, MiniBars, StatusBar } from "@/components/charts";
import { routerState, servicesState } from "@/lib/routerState";
import type { CSSProperties } from "react";

interface SeriesPoint { date: string; label: string; revenue?: number; transactions?: number; users?: number; registrations?: number }
interface WeeklySale { date: string; day: string; revenue: number; transactions: number }
interface RouterRevenue { routerId: number; name: string; status: string; revenueToday: number; revenueMonth: number; revenueTotal: number; transactions: number; activeUsers: number }
interface TopDataUser { username: string; customer: string | null; phone: string | null; package: string | null; octets: number }
interface Consumer { username: string; totalOctets: number }
interface PackagePerf { id: number; name: string; price: number; activeUsers: number; monthlyRevenue: number; totalSold: number; arpu: number }
interface DashboardStats {
  revenueToday: number; revenueMonth: number; revenueAllTime: number; activeUsers: number;
  totalRouters: number; onlineRouters: number; totalVouchers: number; activeVouchers: number; usedVouchers: number; expiredVouchers: number;
  weeklySales: WeeklySale[]; routerRevenue: RouterRevenue[]; topConsumers: Consumer[];
  range: string; revenueSeries: SeriesPoint[]; dailyActiveUsers: SeriesPoint[]; registrations: SeriesPoint[];
  topDataUsers: TopDataUser[]; packagePerformance: PackagePerf[];
  dataMonthDownBytes: number; dataMonthUpBytes: number; totalCustomers: number; newCustomersThisWeek: number;
}
// Tenant router list. The response also carries provisioning secrets; only these fields are read.
interface RouterRec { id: number; name: string; status: string; lastSeen: string | null; cpuLoad: number | null; memoryUsage: number | null; hotspotReady: boolean | null; radiusReady: boolean | null; portalReady: boolean | null }
interface Payment { id: number; reference: string; method: string; amount: number; status: string; phone: string | null; voucher: string | null; packageName: string | null; router: string | null; createdAt: string }
interface UsageBucket { start: string; label: string; downBytes: number; upBytes: number; future: boolean }
interface DataUsage { period: string; downBytes: number; upBytes: number; users: number; previousBytes: number; buckets: UsageBucket[] }

const RANGES = [
  { key: "today", label: "Today" }, { key: "7d", label: "7 days" }, { key: "week", label: "This week" }, { key: "lastweek", label: "Last week" },
  { key: "month", label: "This month" }, { key: "lastmonth", label: "Last month" }, { key: "year", label: "This year" }, { key: "lastyear", label: "Last year" },
];
const MOBILE_MONEY = ["MTN", "AIRTEL"];
const USAGE_PERIODS = [{ key: "today", label: "Today" }, { key: "week", label: "Week" }, { key: "month", label: "Month" }] as const;
type UsagePeriod = (typeof USAGE_PERIODS)[number]["key"];
// Upload is a lighter tint of the same hue, so the pair reads as one measure.
const UPLOAD_FILL = "color-mix(in srgb, var(--chart-1) 38%, transparent)";

/* The hero is ink in both themes, so its charts use the sidebar's signal colours. */
const HERO_TOKENS = { "--chart-1": "#37E2C4", "--chart-muted": "rgb(255 255 255 / 0.2)", "--fg-faint": "#8FB5B0" } as CSSProperties;

/* Revenue visibility is a per-device preference, so it lives in localStorage. */
const HIDE_KEY = "wb_hide_revenue";
const hideStore = {
  subscribe(cb: () => void) { window.addEventListener("storage", cb); window.addEventListener(HIDE_KEY, cb); return () => { window.removeEventListener("storage", cb); window.removeEventListener(HIDE_KEY, cb); }; },
  get() { try { return localStorage.getItem(HIDE_KEY) === "1"; } catch { return false; } },
  set(v: boolean) { try { localStorage.setItem(HIDE_KEY, v ? "1" : "0"); } catch { /* unavailable */ } window.dispatchEvent(new Event(HIDE_KEY)); },
};

function greeting(now: number) {
  if (!now) return "Welcome back";
  const h = new Date(now).getHours();
  return h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
}

export default function TenantDashboardOverview() {
  const { user } = useAuth();
  const now = useNow(60000);
  const [range, setRange] = useState("7d");
  // Stats is the heaviest aggregate the backend serves, so 30 s rather than
  // 20 s; keepPrevious keeps the page on screen while another range loads.
  const stats = useApi<DashboardStats>(`/api/dashboard/stats?range=${range}`, 30000, { keepPrevious: true });
  // Routers report every 2 minutes; polling faster than 30 s finds nothing new.
  const routers = useResource<RouterRec>("/api/routers", 30000);
  // Only the newest rows are needed here; the Payments page loads full history.
  const payments = useApi<Payment[]>("/api/finance/payments?limit=6", 60000);
  const [usagePeriod, setUsagePeriod] = useState<UsagePeriod>("today");
  const usage = useApi<DataUsage>(`/api/dashboard/data-usage?period=${usagePeriod}`, 60000, { keepPrevious: true });
  const hidden = useSyncExternalStore(hideStore.subscribe, hideStore.get, () => false);

  const s = stats.data;
  const money = (v: number | null | undefined) => (hidden ? "UGX ••••••" : `UGX ${Number(v || 0).toLocaleString()}`);
  const moneyShort = (v: number) => (hidden ? "•••" : compactNumber(v));

  // Today vs yesterday from the fixed 7-day series (today is the last point).
  const week = s?.weeklySales ?? [];
  const today = week[week.length - 1];
  const yesterday = week[week.length - 2];
  const weekTotal = week.reduce((sum, d) => sum + Number(d.revenue), 0);
  const delta = today && yesterday && Number(yesterday.revenue) > 0 ? ((Number(today.revenue) - Number(yesterday.revenue)) / Number(yesterday.revenue)) * 100 : null;

  const fleet = useMemo(() => routers.data.map((r) => ({ ...r, st: routerState(r, now) })), [routers.data, now]);
  const down = fleet.filter((r) => r.st.state === "offline" || r.st.state === "stale");
  const degraded = fleet.filter((r) => r.st.state === "degraded" || (r.st.state === "online" && servicesState([r.hotspotReady, r.radiusReady, r.portalReady]).tone === "amber"));
  const online = fleet.filter((r) => r.st.state === "online").length;

  const recent = payments.data ?? [];

  const series = s?.revenueSeries?.length ? s.revenueSeries : week.map((d) => ({ date: d.date, label: d.day, revenue: d.revenue, transactions: d.transactions }));
  const rangeLabel = RANGES.find((r) => r.key === (s?.range || range))?.label ?? "7 days";
  const chartDate = (iso: string) => new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, range === "year" || range === "lastyear" ? { month: "long", year: "numeric" } : { weekday: "short", day: "numeric", month: "short" });

  if (stats.loading && !s && !stats.error) return <DashboardLoading />;

  return (
    <div className={`mx-auto max-w-7xl space-y-6 transition-opacity ${stats.placeholder ? "opacity-60" : ""}`} aria-busy={stats.placeholder}>
      {/* Greeting and quick actions */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div className="min-w-0">
          <p className="text-[13px] font-medium text-[var(--fg-muted)]">{now ? new Date(now).toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" }) : " "}</p>
          <h1 className="mt-0.5 truncate text-[1.85rem] font-bold leading-tight tracking-[-0.025em] text-[var(--fg)]">{greeting(now)}, {user?.name?.split(" ")[0] || "there"}</h1>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button onClick={() => { stats.load(); routers.load(); payments.load(); usage.load(); }} disabled={stats.refreshing} className={`${buttonGhost} !px-3`} aria-label="Refresh dashboard" title="Refresh dashboard">
            <ArrowClockwise className={`h-4 w-4 ${stats.refreshing ? "animate-spin" : ""}`} />
          </button>
          <button onClick={() => hideStore.set(!hidden)} className={`${buttonGhost} !px-3`} aria-pressed={hidden} aria-label={hidden ? "Show revenue" : "Hide revenue"} title={hidden ? "Show revenue" : "Hide revenue"}>
            {hidden ? <EyeSlash className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
          </button>
          <Link href="/dashboard/packages/new" className={`${buttonGhost} max-sm:!hidden`}><Stack className="h-4 w-4" />New package</Link>
          <Link href="/dashboard/vouchers/new" className={`${button} flex-1 sm:flex-none`}><Plus className="h-4 w-4" />Generate vouchers</Link>
        </div>
      </div>

      {stats.error && <ErrorNotice text={s ? `Couldn't refresh the dashboard (${stats.error}). Showing data from ${relativeTime(new Date(stats.updatedAt!).toISOString(), now)}.` : `Dashboard data couldn't be loaded: ${stats.error}`} />}

      {/* Only shown when something needs the owner's attention. */}
      {(down.length > 0 || degraded.length > 0) && (
        <Link href="/dashboard/routers" className={`flex items-start gap-3 rounded-xl border px-4 py-3 transition-colors ${down.length ? "border-red-200 bg-red-50 text-red-800 hover:bg-red-100/60" : "border-amber-200 bg-amber-50 text-amber-900 hover:bg-amber-100/60"}`}>
          <WarningCircle className="mt-0.5 h-5 w-5 shrink-0" />
          <span className="min-w-0 flex-1 text-[13.5px]">
            <span className="font-semibold">
              {down.length ? `${down.map((r) => r.name).slice(0, 2).join(", ")}${down.length > 2 ? ` and ${down.length - 2} more` : ""} ${down.length === 1 ? "is" : "are"} not reporting` : `${degraded.length} router${degraded.length === 1 ? " has" : "s have"} a service not ready`}
            </span>
            <span className="block opacity-90">{down.length ? `Customers on ${down.length === 1 ? "that router" : "those routers"} may not be able to connect. Check ${down.length === 1 ? "its" : "their"} power and internet link.` : "Hotspot, login or portal files aren't ready on a router. Open Routers to see which."}</span>
          </span>
          <ArrowRight className="mt-0.5 h-4 w-4 shrink-0" />
        </Link>
      )}

      {/* Revenue: the number owners open the app for. */}
      <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
        <section className="relative isolate flex min-w-0 flex-col overflow-hidden rounded-2xl bg-[#08282E] text-white shadow-[var(--shadow-overlay)] lg:col-span-2" style={HERO_TOKENS}>
          <div aria-hidden className="absolute inset-0 -z-10 bg-[radial-gradient(38rem_24rem_at_100%_0%,rgb(11_122_117/0.7),transparent_70%)]" />
          <svg aria-hidden viewBox="0 0 400 400" fill="none" className="absolute -right-28 -top-36 -z-10 h-[26rem] w-[26rem] text-[#37E2C4]">
            {[60, 110, 160, 210].map((r, i) => <circle key={r} cx="200" cy="200" r={r} stroke="currentColor" strokeOpacity={0.3 - i * 0.06} />)}
          </svg>
          <div className="grid flex-1 gap-6 p-5 sm:grid-cols-[1fr_minmax(0,15rem)] sm:p-7">
            <div className="min-w-0">
              <p className="text-[13px] font-medium text-[#BFDAD6]">Today&apos;s revenue</p>
              <p className="num mt-3 font-display text-[2.1rem] font-bold leading-none tracking-[-0.03em] [overflow-wrap:anywhere] sm:text-[3rem]">
                {s ? (hidden ? money(s.revenueToday) : <CountUp value={Number(s.revenueToday || 0)} format={money} />) : "—"}
              </p>
              <p className="mt-4 flex flex-wrap items-center gap-x-2 gap-y-1 text-[13px] text-[#BFDAD6]">
                {today && <span className="num">{today.transactions} successful payment{today.transactions === 1 ? "" : "s"}</span>}
                {delta != null && !hidden && (
                  <span className={`inline-flex items-center gap-0.5 font-semibold ${delta >= 0 ? "text-[#5EEAD4]" : "text-[#FCA5A5]"}`}>
                    <ArrowDown className={`h-3.5 w-3.5 ${delta >= 0 ? "rotate-180" : ""}`} aria-hidden />{Math.abs(delta).toFixed(0)}% vs yesterday
                  </span>
                )}
                {delta == null && yesterday && Number(yesterday.revenue) === 0 && <span>No sales yesterday</span>}
              </p>
            </div>
            {week.length > 0 && (
              <div className="min-w-0 self-end">
                <p className="mb-2 text-xs font-medium text-[#BFDAD6]">Last 7 days</p>
                {hidden ? <p className="py-4 text-xs text-[#8FB5B0]">Hidden</p> : (
                  <MiniBars label="Revenue, last 7 days" format={(v) => money(v)}
                    data={week.map((d) => ({ key: d.date, label: d.day.slice(0, 2), value: Number(d.revenue), detail: `${chartDate(d.date)} · ${d.transactions} payments` }))} />
                )}
              </div>
            )}
          </div>
          <dl className="grid divide-y divide-white/10 border-t border-white/10 bg-white/[0.04] sm:grid-cols-3 sm:divide-x sm:divide-y-0">
            {[
              { label: "Last 7 days", value: s ? money(weekTotal) : "—" },
              { label: "This month", value: s ? money(s.revenueMonth) : "—" },
              { label: "All time", value: s ? money(s.revenueAllTime) : "—" },
            ].map((m) => (
              <div key={m.label} className="flex min-w-0 items-baseline justify-between gap-3 px-5 py-3 sm:block sm:px-6 sm:py-3.5">
                <dt className="text-xs text-[#BFDAD6]">{m.label}</dt>
                <dd className="num truncate text-[14px] font-semibold sm:mt-0.5 sm:text-[15px]">{m.value}</dd>
              </div>
            ))}
          </dl>
        </section>

        <DataUsageCard usage={usage} period={usagePeriod} onPeriod={setUsagePeriod} />
      </div>

      {/* Live operational figures */}
      <div className="grid grid-cols-2 gap-3 sm:gap-4 xl:grid-cols-4">
        <StatCard label="Online now" href="/dashboard/users" icon={<Radio />} tone="green" value={s ? s.activeUsers.toLocaleString() : "—"} hintText="Customers connected right now" />
        <StatCard label="Customers" href="/dashboard/customers" icon={<Users />} value={s ? s.totalCustomers.toLocaleString() : "—"} hintText={s ? `+${s.newCustomersThisWeek.toLocaleString()} new this week` : undefined} />
        <StatCard label="Routers online" href="/dashboard/routers" icon={<CellTower />} tone={down.length ? "red" : degraded.length ? "amber" : "default"}
          value={routers.error ? "—" : <>{online}<span className="text-[var(--fg-faint)]">/{fleet.length}</span></>}
          hintText={!fleet.length ? (routers.error ? "Router status unavailable" : routers.loading ? "Checking…" : "No router linked yet") : down.length ? `${down.length} not reporting` : degraded.length ? `${degraded.length} need attention` : "All reporting"} />
        <StatCard label="Vouchers left" href="/dashboard/vouchers" icon={<Ticket />} value={s ? s.totalVouchers.toLocaleString() : "—"} hintText={s ? `${s.activeVouchers.toLocaleString()} in use now` : undefined} />
      </div>

      {/* Period-scoped analytics */}
      <section aria-label="Sales and usage over time" className="space-y-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <h2 className="text-[15px] font-semibold text-[var(--fg)]">Sales and usage</h2>
          <div className="hidden md:block"><Segmented label="Period" value={range} onChange={setRange} options={RANGES} /></div>
          <select aria-label="Period" value={range} onChange={(e) => setRange(e.target.value)} className={`${field} !mt-0 md:hidden`}>
            {RANGES.map((r) => <option key={r.key} value={r.key}>{r.label}</option>)}
          </select>
        </div>
        <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
          <Card className="lg:col-span-2" title="Revenue" description={`Successful payments · ${rangeLabel.toLowerCase()}`}
            action={s && !hidden && <span className="num text-sm font-semibold text-[var(--fg)]">{money(series.reduce((n, p) => n + Number(p.revenue || 0), 0))}</span>}>
            {hidden ? <p className="py-16 text-center text-sm text-[var(--fg-muted)]">Revenue is hidden. Use the eye button to show it.</p>
              : !s ? <p className="py-16 text-center text-sm text-[var(--fg-muted)]">Data unavailable.</p>
              : !series.some((p) => Number(p.revenue) > 0) ? <p className="py-16 text-center text-sm text-[var(--fg-muted)]">No sales in this period yet.</p>
              : <BarChart height={240} label={`Revenue, ${rangeLabel}`} dim={stats.loading} format={(v) => money(v)} tickFormat={moneyShort}
                  data={series.map((p) => ({ key: p.date, label: p.label, value: Number(p.revenue || 0), detail: `${chartDate(p.date)} · ${p.transactions ?? 0} payments` }))} />}
          </Card>
          <Card title="Customers online" description={`Unique customers per ${range === "year" || range === "lastyear" ? "month" : "day"}`}>
            {!s ? <p className="py-16 text-center text-sm text-[var(--fg-muted)]">Data unavailable.</p>
              : !s.dailyActiveUsers?.some((p) => (p.users || 0) > 0) ? <p className="py-16 text-center text-sm text-[var(--fg-muted)]">No customer sessions in this period.</p>
              : <BarChart height={240} integer label={`Customers online, ${rangeLabel}`} dim={stats.loading} format={(v) => `${v} customer${v === 1 ? "" : "s"}`}
                  data={s.dailyActiveUsers.map((p) => ({ key: p.date, label: p.label, value: p.users || 0, detail: chartDate(p.date) }))} />}
          </Card>
        </div>
      </section>

      {/* Money in, and the network that earns it */}
      <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
        <Card className="lg:col-span-2" title="Recent transactions" description="Latest payments from customers" action={<CardLink href="/dashboard/payments">All payments</CardLink>} bodyClassName="">
          {payments.error && !payments.data ? <p className="px-5 py-12 text-center text-sm text-[var(--fg-muted)]">Transactions unavailable right now.</p>
            : !payments.data ? <ListSkeleton rows={5} />
            : recent.length === 0 ? <p className="px-5 py-12 text-center text-sm text-[var(--fg-muted)]">No payments yet. They&apos;ll appear here as customers buy packages.</p>
            : (
              <ul className="divide-y divide-[var(--border-soft)]">
                {recent.map((p) => {
                  const mm = MOBILE_MONEY.includes(p.method);
                  return (
                    <li key={p.id} className="flex items-center gap-3 px-5 py-3">
                      <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-[11px] font-semibold ${mm ? "bg-[var(--primary-soft)] text-[var(--accent-text)]" : "bg-[var(--fill)] text-[var(--fg-muted)]"}`}>{mm ? p.method.slice(0, 3) : "Cash"}</span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-[13.5px] font-medium text-[var(--fg)]">{p.packageName || "Walk-in sale"}</span>
                        <span className="block truncate text-xs text-[var(--fg-muted)]">{[p.phone, p.voucher, p.router].filter(Boolean).join(" · ")}</span>
                      </span>
                      <span className="shrink-0 text-right">
                        <span className={`num block text-[13.5px] font-semibold ${p.status === "SUCCESSFUL" ? "text-[var(--fg)]" : "text-[var(--fg-muted)]"}`}>{money(p.amount)}</span>
                        <span className="mt-0.5 flex items-center justify-end gap-1.5 text-[11.5px] text-[var(--fg-muted)]">
                          {p.status !== "SUCCESSFUL" && <Badge tone={p.status === "FAILED" ? "red" : "amber"}>{p.status === "FAILED" ? "Failed" : "Pending"}</Badge>}
                          <span title={new Date(p.createdAt).toLocaleString()}>{relativeTime(p.createdAt, now)}</span>
                        </span>
                      </span>
                    </li>
                  );
                })}
              </ul>
            )}
        </Card>

        <Card title="Router health" description="Latest report from each location" action={fleet.length > 0 && <CardLink href="/dashboard/routers">Routers</CardLink>} bodyClassName="flex flex-col">
          {routers.error && !fleet.length ? <p className="px-5 py-12 text-center text-sm text-[var(--fg-muted)]">Router status unavailable.</p>
            : routers.loading && !fleet.length ? <ListSkeleton rows={2} />
            : fleet.length === 0 ? (
              <div className="flex flex-col items-center gap-3 px-5 py-10 text-center">
                <span className="flex h-10 w-10 items-center justify-center rounded-full bg-[var(--fill)] text-[var(--fg-faint)]"><CellTower className="h-5 w-5" /></span>
                <p className="text-sm text-[var(--fg-muted)]">Link a MikroTik router to start selling WiFi.</p>
                <Link href="/dashboard/routers/new" className={button}><Plus className="h-4 w-4" />Add router</Link>
              </div>
            ) : (
              <ul className="divide-y divide-[var(--border-soft)]">
                {fleet.map((r) => {
                  const current = r.st.state === "online" || r.st.state === "degraded";
                  return (
                    <li key={r.id} className="px-5 py-3.5">
                      <div className="flex items-center justify-between gap-3">
                        <span className="min-w-0">
                          <span className="block truncate text-[13.5px] font-medium text-[var(--fg)]">{r.name}</span>
                          <span className="block text-xs text-[var(--fg-muted)]" title={r.lastSeen ? new Date(r.lastSeen).toLocaleString() : undefined}>{r.lastSeen ? `Reported ${relativeTime(r.lastSeen, now)}` : "Never reported"}</span>
                        </span>
                        <span title={r.st.detail}><Badge tone={r.st.tone} dot live={r.st.state === "online"}>{r.st.label}</Badge></span>
                      </div>
                      {current && (
                        <div className="mt-2.5 flex flex-wrap gap-x-5 gap-y-1.5 text-xs text-[var(--fg-muted)]">
                          <span className="flex items-center gap-2">CPU <Meter value={r.cpuLoad} label={`${r.name} CPU`} /></span>
                          <span className="flex items-center gap-2">RAM <Meter value={r.memoryUsage} label={`${r.name} memory`} /></span>
                        </div>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          {fleet.length > 0 && (
            <div className="mt-auto border-t border-[var(--border)] px-5 py-3">
              <Link href="/dashboard/routers/new" className="inline-flex items-center gap-1.5 text-xs font-medium text-[var(--accent-text)] hover:underline max-sm:min-h-11"><Plus className="h-3.5 w-3.5" />Add another router</Link>
            </div>
          )}
        </Card>
      </div>

      {/* What sells */}
      <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
        <Card className="lg:col-span-2" title="Package performance" description="Sales this month and customers using each package now" action={<CardLink href="/dashboard/packages">Packages</CardLink>} bodyClassName="">
          {!s?.packagePerformance?.length ? (
            <div className="flex flex-col items-center gap-3 px-5 py-10 text-center">
              <p className="text-sm text-[var(--fg-muted)]">{s ? "No packages yet. Create one so customers can buy access." : "Data unavailable."}</p>
              {s && <Link href="/dashboard/packages/new" className={buttonGhost}><Plus className="h-4 w-4" />Create package</Link>}
            </div>
          ) : (
            <PackageTable rows={s.packagePerformance} money={money} />
          )}
        </Card>

        <Card title="Vouchers" description={`Inventory now · ${(s?.registrations ?? []).reduce((n, p) => n + (p.registrations || 0), 0)} issued ${rangeLabel.toLowerCase() === "today" ? "today" : `in ${rangeLabel.toLowerCase()}`}`} action={<CardLink href="/dashboard/vouchers">Vouchers</CardLink>}>
          {s ? (
            <>
              <StatusBar label="Voucher inventory" segments={[
                { key: "unused", label: "Available", value: Math.max(0, s.totalVouchers - s.activeVouchers), tone: "info" },
                { key: "active", label: "In use", value: s.activeVouchers, tone: "good" },
                { key: "used", label: "Used up", value: s.usedVouchers, tone: "muted" },
                { key: "expired", label: "Expired or revoked", value: s.expiredVouchers, tone: "idle" },
              ]} />
              {s.registrations?.some((p) => (p.registrations || 0) > 0) && (
                <div className="mt-5 border-t border-[var(--border)] pt-4">
                  <p className="mb-2 text-xs font-medium text-[var(--fg-muted)]">Vouchers issued</p>
                  <MiniBars height={56} label={`Vouchers issued, ${rangeLabel}`} format={(v) => `${v} issued`}
                    data={s.registrations.slice(-14).map((p) => ({ key: p.date, label: p.label.slice(0, 2), value: p.registrations || 0, detail: chartDate(p.date) }))} />
                </div>
              )}
              <Link href="/dashboard/vouchers/new" className={`${buttonGhost} mt-5 w-full`}><Ticket className="h-4 w-4" />Generate vouchers</Link>
            </>
          ) : <p className="py-8 text-center text-sm text-[var(--fg-muted)]">Data unavailable.</p>}
        </Card>
      </div>

      {/* Where money and data go */}
      <div className="grid grid-cols-1 gap-5 lg:grid-cols-3 lg:items-start">
        <Card className="lg:col-span-2" title="Revenue by router" description="What each location earns" bodyClassName="">
          {!s?.routerRevenue?.length ? <p className="px-5 py-10 text-center text-sm text-[var(--fg-muted)]">{s ? "Revenue is tracked per router once you link one." : "Data unavailable."}</p> : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="border-b border-[var(--border)] bg-[var(--subtle)] text-xs text-[var(--fg-muted)]">
                  <tr><th scope="col" className="px-5 py-2.5 font-medium">Router</th><th scope="col" className="px-4 py-2.5 text-right font-medium">Today</th><th scope="col" className="hidden px-4 py-2.5 text-right font-medium sm:table-cell">This month</th><th scope="col" className="hidden px-4 py-2.5 text-right font-medium md:table-cell">All time</th><th scope="col" className="px-5 py-2.5 text-right font-medium">Online</th></tr>
                </thead>
                <tbody className="divide-y divide-[var(--border-soft)]">
                  {s.routerRevenue.map((r) => {
                    const live = fleet.find((x) => x.id === r.routerId);
                    return (
                      <tr key={r.routerId} className="hover:bg-[var(--subtle)]">
                        <td className="px-5 py-3"><span className="block font-medium text-[var(--fg)]">{r.name}</span>{live && <span className="text-xs"><StatusDot tone={live.st.tone}>{live.st.label}</StatusDot></span>}</td>
                        <td className="num px-4 py-3 text-right font-medium text-[var(--fg)]">{money(r.revenueToday)}</td>
                        <td className="num hidden px-4 py-3 text-right sm:table-cell">{money(r.revenueMonth)}</td>
                        <td className="num hidden px-4 py-3 text-right md:table-cell">{money(r.revenueTotal)}</td>
                        <td className="num px-5 py-3 text-right">{r.activeUsers}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <Card title="Top data users" description={s ? `${bytes(s.dataMonthDownBytes + s.dataMonthUpBytes)} used this month · ↓ ${bytes(s.dataMonthDownBytes)} ↑ ${bytes(s.dataMonthUpBytes)}` : "Highest usage"} action={<CardLink href="/dashboard/users">Active users</CardLink>} bodyClassName="">
          {(() => {
            const rows = s?.topDataUsers?.length ? s.topDataUsers : (s?.topConsumers ?? []).map((c) => ({ username: c.username, customer: null, phone: null, package: null, octets: c.totalOctets }));
            if (!rows.length) return <p className="px-5 py-10 text-center text-sm text-[var(--fg-muted)]">{s ? `No data usage ${rangeLabel.toLowerCase() === "today" ? "today" : `in ${rangeLabel.toLowerCase()}`}.` : "Data unavailable."}</p>;
            const max = Math.max(1, ...rows.map((r) => r.octets));
            return (
              <ol className="divide-y divide-[var(--border-soft)]">
                {rows.slice(0, 6).map((u, i) => (
                  <li key={u.username} className="px-5 py-3">
                    <div className="flex items-center justify-between gap-3">
                      <span className="flex min-w-0 items-center gap-2.5">
                        <span className="num w-4 shrink-0 text-xs text-[var(--fg-faint)]">{i + 1}</span>
                        <span className="min-w-0"><span className="block truncate text-[13.5px] font-medium text-[var(--fg)]">{u.customer || u.username}</span><span className="block truncate text-xs text-[var(--fg-muted)]">{[u.package, u.phone].filter(Boolean).join(" · ") || u.username}</span></span>
                      </span>
                      <span className="num shrink-0 text-[13px] font-medium text-[var(--fg-body)]">{bytes(u.octets)}</span>
                    </div>
                    <span className="ml-6 mt-2 block h-1 overflow-hidden rounded-full bg-[var(--fill)]"><span className="block h-full rounded-full bg-[var(--chart-1)]" style={{ width: `${(u.octets / max) * 100}%` }} /></span>
                  </li>
                ))}
              </ol>
            );
          })()}
        </Card>
      </div>

      <p className="flex items-center justify-end gap-2 text-xs text-[var(--fg-faint)]">
        {stats.loading && <ArrowClockwise className="h-3 w-3 animate-spin motion-reduce:animate-none" aria-hidden />}
        {stats.updatedAt ? `Updated ${new Date(stats.updatedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })} · refreshes every 30 seconds` : "Waiting for first update"}
      </p>
    </div>
  );
}

const COMPARE_LABEL: Record<UsagePeriod, string> = { today: "vs this time yesterday", week: "vs last week so far", month: "vs last month so far" };
const EMPTY_LABEL: Record<UsagePeriod, string> = { today: "No data used yet today", week: "No data used yet this week", month: "No data used yet this month" };

/** Download/upload for the tenant's customers, with its own period switch. */
function DataUsageCard({ usage, period, onPeriod }: {
  usage: ReturnType<typeof useApi<DataUsage>>; period: UsagePeriod; onPeriod: (p: UsagePeriod) => void;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const d = usage.data;
  const total = d ? d.downBytes + d.upBytes : 0;
  const change = d && d.previousBytes > 0 ? ((total - d.previousBytes) / d.previousBytes) * 100 : null;
  const buckets = d?.buckets ?? [];
  const max = Math.max(1, ...buckets.map((b) => b.downBytes + b.upBytes));
  const hourly = d?.period === "today";
  const hovered = hover != null ? buckets[hover] : null;
  const when = (b: UsageBucket) => {
    const t = new Date(b.start);
    return hourly
      ? `${t.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" })} – ${new Date(t.getTime() + 3600000).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" })}`
      : t.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });
  };
  // Hours and month days are too dense to label every bar.
  const showTick = (i: number) => (hourly ? i % 6 === 0 : d?.period === "month" ? i % 7 === 0 : true);
  const tick = (b: UsageBucket) => hourly ? new Date(b.start).toLocaleTimeString(undefined, { hour: "numeric" }) : b.label;

  return (
    <section aria-label="Data usage" className={`panel-shadow flex min-w-0 flex-col overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)] transition-opacity ${usage.placeholder ? "opacity-60" : ""}`} aria-busy={usage.placeholder}>
      <div className="flex flex-1 flex-col p-5 sm:p-6">
        <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
          <p className="whitespace-nowrap text-[13px] font-medium text-[var(--fg-muted)]">Data usage</p>
          <Segmented label="Data usage period" value={period} onChange={onPeriod} options={[...USAGE_PERIODS]} />
        </div>
        <p className="num mt-2 truncate font-display text-[2rem] font-bold leading-none tracking-[-0.025em] text-[var(--fg)]">{d ? bytes(total) : usage.error ? "—" : "…"}</p>
        <p className="mt-2.5 min-h-[1.25rem] text-[13px] text-[var(--fg-muted)]">
          {usage.error && !d ? "Usage unavailable right now"
            : !d ? " "
            : total === 0 ? EMPTY_LABEL[period]
            : change != null ? (
              <span title="Compared over the same length of time" className="inline-flex items-center gap-1">
                <span className="inline-flex items-center gap-0.5 font-medium text-[var(--fg-body)]">
                  <ArrowDown className={`h-3.5 w-3.5 ${change >= 0 ? "rotate-180" : ""}`} aria-hidden />{Math.abs(change).toFixed(0)}%
                </span>
                {COMPARE_LABEL[period]}
              </span>
            ) : "Download and upload combined"}
        </p>

        {buckets.length > 0 && (
          <div className="relative mt-auto pt-5">
            <div className="flex h-14 items-end gap-[3px]" role="img" aria-label={`Data used per ${hourly ? "hour" : "day"}`}>
              {buckets.map((b, i) => {
                const sum = b.downBytes + b.upBytes;
                const h = b.future ? 0 : Math.max(sum > 0 ? 6 : 0, (sum / max) * 100);
                return (
                  <button key={b.start} type="button" aria-label={`${when(b)}: ${bytes(sum)}`}
                    onPointerEnter={() => setHover(i)} onPointerLeave={() => setHover(null)} onFocus={() => setHover(i)} onBlur={() => setHover(null)}
                    className="relative flex h-full min-w-0 flex-1 cursor-default flex-col justify-end outline-none">
                    {/* Baseline keeps empty and upcoming slots visible as the scale of the period. */}
                    <span className="absolute inset-x-0 bottom-0 h-[2px] rounded-full bg-[var(--chart-grid)]" />
                    <span className="relative flex w-full flex-col overflow-hidden rounded-t-[3px] transition-opacity" style={{ height: `${h}%`, opacity: hover == null || hover === i ? 1 : 0.55 }}>
                      <span style={{ flexGrow: b.upBytes, background: UPLOAD_FILL }} />
                      <span style={{ flexGrow: b.downBytes, background: "var(--chart-1)" }} />
                    </span>
                  </button>
                );
              })}
            </div>
            <div className="mt-1.5 flex gap-[3px] text-[10.5px] text-[var(--fg-faint)]">
              {buckets.map((b, i) => <span key={b.start} className="min-w-0 flex-1 overflow-visible whitespace-nowrap">{showTick(i) ? tick(b) : ""}</span>)}
            </div>
            {hovered && hover != null && (
              <div role="presentation" className="pointer-events-none absolute bottom-full z-10 -mb-3 -translate-x-1/2 whitespace-nowrap rounded-lg border border-[var(--border)] bg-[var(--surface)] px-2.5 py-1.5 shadow-[var(--shadow-overlay)]"
                style={{ left: `${Math.min(85, Math.max(15, ((hover + 0.5) / buckets.length) * 100))}%` }}>
                <p className="text-[11px] text-[var(--fg-muted)]">{when(hovered)}</p>
                <p className="num text-[13px] font-semibold text-[var(--fg)]">{hovered.future ? "Not yet" : bytes(hovered.downBytes + hovered.upBytes)}</p>
                {!hovered.future && <p className="num text-[11px] text-[var(--fg-muted)]">↓ {bytes(hovered.downBytes)} · ↑ {bytes(hovered.upBytes)}</p>}
              </div>
            )}
          </div>
        )}
      </div>
      <dl className="grid grid-cols-3 divide-x divide-[var(--border)] border-t border-[var(--border)] bg-[var(--subtle)]">
        {[
          { label: "Download", value: d ? bytes(d.downBytes) : "—", swatch: "var(--chart-1)" },
          { label: "Upload", value: d ? bytes(d.upBytes) : "—", swatch: UPLOAD_FILL },
          { label: "Customers", value: d ? d.users.toLocaleString() : "—" },
        ].map((m) => (
          <div key={m.label} className="min-w-0 px-3 py-3 sm:py-3.5">
            <dt className="flex items-center gap-1.5 text-xs text-[var(--fg-muted)]">
              {m.swatch && <span aria-hidden className="h-2 w-2 shrink-0 rounded-[2px]" style={{ background: m.swatch }} />}{m.label}
            </dt>
            <dd className="num mt-0.5 text-[13px] font-semibold leading-tight text-[var(--fg)] [overflow-wrap:anywhere]">{m.value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

function PackageTable({ rows, money }: { rows: PackagePerf[]; money: (v: number) => string }) {
  const best = Math.max(1, ...rows.map((p) => Number(p.monthlyRevenue)));
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-[var(--border)] bg-[var(--subtle)] text-xs text-[var(--fg-muted)]">
          <tr>
            <th scope="col" className="px-5 py-2.5 font-medium">Package</th>
            <th scope="col" className="px-4 py-2.5 text-right font-medium">This month</th>
            <th scope="col" className="hidden px-4 py-2.5 text-right font-medium sm:table-cell">Using now</th>
            <th scope="col" className="hidden px-4 py-2.5 text-right font-medium md:table-cell">Sold all time</th>
            <th scope="col" className="hidden px-5 py-2.5 text-right font-medium lg:table-cell">Avg. sale</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-[var(--border-soft)]">
          {[...rows].sort((a, b) => Number(b.monthlyRevenue) - Number(a.monthlyRevenue)).map((p) => (
            <tr key={p.id} className="hover:bg-[var(--subtle)]">
              <td className="px-5 py-3">
                <span className="block font-medium text-[var(--fg)]">{p.name}</span>
                <span className="num text-xs text-[var(--fg-muted)]">{money(p.price)}</span>
              </td>
              <td className="px-4 py-3 text-right">
                <span className="num block font-medium text-[var(--fg)]">{money(p.monthlyRevenue)}</span>
                <span className="ml-auto mt-1.5 block h-1 w-20 overflow-hidden rounded-full bg-[var(--fill)]"><span className="block h-full rounded-full bg-[var(--chart-1)]" style={{ width: `${(Number(p.monthlyRevenue) / best) * 100}%` }} /></span>
              </td>
              <td className="num hidden px-4 py-3 text-right sm:table-cell">{p.activeUsers}</td>
              <td className="num hidden px-4 py-3 text-right md:table-cell">{p.totalSold.toLocaleString()}</td>
              <td className="num hidden px-5 py-3 text-right lg:table-cell">{money(p.arpu)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
