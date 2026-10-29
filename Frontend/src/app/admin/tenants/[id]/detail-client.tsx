"use client";
import { ListSkeleton } from "@/components/LoadingUI";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useState } from "react";
import api from "@/lib/api";
import { ActionMenu } from "@/components/ui/ActionMenu";
import { accountTone, subscriptionTone, useTenantActions } from "@/components/admin/useTenantActions";
import {
  Building2, CaretRight, CellTower as RouterIcon, CheckCircle, EnvelopeSimple, Key, WarningCircle,
} from "@/components/icons";
import {
  BackLink, Badge, Card, CardLink, ErrorNotice, Field, Segmented, StatusDot, Table, Tabs, buttonGhost,
  bytes, date, humanize, relativeTime, shortDate, ugx, useApi, useNow, useResource,
} from "@/components/BillingUI";
import { Meter } from "@/components/charts";
import { duration, routerState, servicesState, sessionState, severityTone, type RouterStateInfo } from "@/lib/routerState";

interface Tenant { id: number; name: string; email: string; phone: string; status: string; routerCount: number; voucherCount: number; createdAt: string }
interface RouterRow {
  id: number; tenantId: number; tenantName: string; name: string; status: string; wgIp: string | null;
  cpuLoad: number | null; memoryUsage: number | null; uptime: string | null; activeUsers: number | null;
  hotspotReady: boolean | null; radiusReady: boolean | null; portalReady: boolean | null;
  bindingsSynced: boolean; provisioningState: string; lastSeen: string | null; openSessions: number; openIncidents: number; createdAt: string;
}
interface Incident { id: number; routerId: number; routerName: string; severity: string; title: string; detail: string | null; openedAt: string }
interface Overview { incidents: Incident[] }
interface Subscription { id: number; tenantId: number; monthlyFee: number; startDate: string; nextRenewalDate: string; status: string; displayStatus: string; outstanding: number }
interface WithdrawalRow {
  id: number; tenantId: number; businessName: string; amount: number; fee: number;
  totalDeduction: number; phone: string; status: string;
  failureReason: string | null; createdAt: string;
}
interface Wallet { tenantId: number; available: number; pending: number; reserved: number; totalCollected: number; totalWithdrawn: number }
interface Billing {
  invoices: { id: number; periodStart: string; periodEnd: string; amountDue: number; status: string; paidAt: string | null }[];
  payments: { id: number; amount: number; method: string; externalRef: string | null; paidAt: string; note: string | null }[];
}
interface SessionRow {
  id: number; username: string; framedIp: string | null; mac: string | null; startedAt: string | null; lastUpdateAt: string | null;
  sessionSeconds: number | null; uploadOctets: number | null; downloadOctets: number | null; state: string; packageName: string | null; remainingSeconds: number | null;
}
interface AuthEvent { id: number; at: string; username: string; reply: string; mac: string | null; description: string }

const WITHDRAWAL_STATUS_TONE: Record<string, "green" | "blue" | "amber" | "red" | "slate"> = {
  COMPLETED: "green",
  PROCESSING: "blue",
  PENDING_APPROVAL: "amber",
  REQUESTED: "amber",
  FAILED: "red",
  CANCELLED: "slate",
};
interface Paged<T> { content: T[]; totalElements: number }

const TABS = ["Overview", "Routers", "Live sessions", "Auth failures", "Billing"] as const;
type Tab = typeof TABS[number];

/** One request per router; partial failures are reported, not hidden. */
async function acrossRouters<T>(routers: RouterRow[], path: string, params: Record<string, unknown>) {
  const results = await Promise.allSettled(routers.map((r) => api.get<Paged<T>>(`/api/admin/noc/routers/${r.id}/${path}`, { params })));
  return {
    pages: results.map((res, i) => ({ router: routers[i], page: res.status === "fulfilled" ? res.value.data : null })),
    failed: results.filter((r) => r.status === "rejected").length,
  };
}

export default function TenantDetailClient() {
  return <Suspense><TenantDetail /></Suspense>;
}

function TenantDetail() {
  const { id } = useParams<{ id: string }>();
  const tenantId = Number(id);
  const now = useNow();
  const [tab, setTab] = useState<Tab>("Overview");

  const tenants = useResource<Tenant>("/api/admin/tenants");
  const fleet = useResource<RouterRow>("/api/admin/noc/routers", true);
  const overview = useApi<Overview>("/api/admin/noc/overview", 60000);
  const subs = useResource<Subscription>("/api/admin/subscriptions");
  const wallets = useResource<Wallet>("/api/admin/finance/wallets");
  const actions = useTenantActions(tenants.load);

  const tenant = tenants.data.find((t) => t.id === tenantId);
  const routers = useMemo(() => fleet.data.filter((r) => r.tenantId === tenantId).map((r) => ({ ...r, s: routerState(r, now) })), [fleet.data, tenantId, now]);
  const routerIds = useMemo(() => new Set(routers.map((r) => r.id)), [routers]);
  const incidents = useMemo(() => (overview.data?.incidents ?? []).filter((i) => routerIds.has(i.routerId)), [overview.data, routerIds]);
  const sub = subs.data.find((s) => s.tenantId === tenantId);
  const wallet = wallets.data.find((w) => w.tenantId === tenantId);

  // Live vs silent sessions and 24h auth failures, counted per router from RADIUS.
  const [radius, setRadius] = useState<{ live: number; stale: number; rejects24h: number; failed: number } | null>(null);
  const reachable = useMemo(() => routers.filter((r) => r.wgIp), [routers]);
  const reachableKey = reachable.map((r) => r.id).join(",");
  useEffect(() => {
    if (!reachable.length) return;
    let live = true;
    // Rounded to the minute so the request key is stable between renders.
    const since = new Date(Math.floor(Date.now() / 60000) * 60000 - 86400000).toISOString();
    Promise.all([
      acrossRouters<SessionRow>(reachable, "sessions", { openOnly: true, state: "LIVE", size: 1 }),
      acrossRouters<SessionRow>(reachable, "sessions", { openOnly: true, state: "OPEN_STALE", size: 1 }),
      acrossRouters<AuthEvent>(reachable, "auth-events", { reply: "Access-Reject", from: since, size: 1 }),
    ]).then(([l, s, a]) => {
      if (!live) return;
      const sum = (x: { pages: { page: { totalElements: number } | null }[] }) => x.pages.reduce((n, p) => n + (p.page?.totalElements ?? 0), 0);
      setRadius({ live: sum(l), stale: sum(s), rejects24h: sum(a), failed: Math.max(l.failed, s.failed, a.failed) });
    });
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- keyed on router ids, not the array identity
  }, [reachableKey]);

  if (tenants.loading && !tenant) return <DetailSkeleton />;
  if (!tenant) {
    return (
      <div className="mx-auto max-w-3xl space-y-4">
        <BackLink href="/admin/tenants">Tenants</BackLink>
        <Card>
          <div className="flex flex-col items-center gap-2 py-10 text-center">
            <span className="flex h-10 w-10 items-center justify-center rounded-full bg-[var(--fill)] text-[var(--fg-faint)]"><Building2 className="h-5 w-5" /></span>
            <p className="text-sm font-medium text-[var(--fg-body)]">{tenants.error ? "Couldn't load this tenant" : "Tenant not found"}</p>
            <p className="text-xs text-[var(--fg-muted)]">{tenants.error || "It may have been removed, or the link is wrong."}</p>
          </div>
        </Card>
      </div>
    );
  }

  const online = routers.filter((r) => r.s.state === "online").length;
  const down = routers.filter((r) => r.s.state === "offline" || r.s.state === "stale");
  const degraded = routers.filter((r) => r.s.state === "degraded");
  const verdict = healthVerdict(routers.length, online, down, degraded.length, incidents.length, radius?.live, fleet.loading, now);

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      {actions.dialog}
      <div>
        <BackLink href="/admin/tenants">Tenants</BackLink>
        <div className="mt-3 flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
          <div className="flex min-w-0 items-start gap-3.5">
            <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-[var(--sidebar)] text-base font-semibold text-white">{initials(tenant.name)}</span>
            <div className="min-w-0">
              <h1 className="flex flex-wrap items-center gap-2.5 text-[1.45rem] font-semibold leading-tight tracking-tight text-[var(--fg)]">
                {tenant.name}<Badge tone={accountTone(tenant.status)} dot>{humanize(tenant.status)}</Badge>
              </h1>
              <p className="mt-1.5 flex flex-wrap gap-x-2 gap-y-1 text-[13px] text-[var(--fg-muted)]">
                <span className="num">#{tenant.id}</span><span aria-hidden>·</span>
                <a href={`mailto:${tenant.email}`} className="hover:underline">{tenant.email}</a><span aria-hidden>·</span>
                <span>{tenant.phone}</span><span aria-hidden>·</span>
                <span title={new Date(tenant.createdAt).toLocaleString()}>Joined {shortDate(tenant.createdAt)}</span>
              </p>
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            <a href={`mailto:${tenant.email}`} className={buttonGhost}><EnvelopeSimple className="h-4 w-4" />Email</a>
            <button onClick={() => actions.resendCredentials(tenant)} className={buttonGhost}><Key className="h-4 w-4" /><span className="hidden sm:inline">Resend password</span><span className="sm:hidden">Password</span></button>
            <span className="rounded-lg border border-[var(--border-strong)]"><ActionMenu ariaLabel="More tenant actions">{actions.menuItems(tenant)}</ActionMenu></span>
          </div>
        </div>
      </div>

      {actions.noticeBanner}
      {fleet.error && <ErrorNotice text={`Router data unavailable: ${fleet.error}`} />}

      <section className={`flex items-start gap-3 rounded-xl border px-4 py-3.5 ${verdict.box}`}>
        {verdict.tone === "green" ? <CheckCircle className="mt-px h-5 w-5 shrink-0" /> : <WarningCircle className="mt-px h-5 w-5 shrink-0" />}
        <div className="min-w-0">
          <p className="text-[14px] font-semibold">{verdict.title}</p>
          <p className="mt-0.5 text-[13px] opacity-90">{verdict.detail}</p>
        </div>
      </section>

      <Tabs label="Tenant sections" value={tab} onChange={setTab} tabs={TABS.map((t) => ({ key: t, label: t, count: t === "Routers" ? routers.length : undefined }))} />

      {tab === "Overview" && (
        <div className="min-w-0 space-y-5">
          <section className="panel-shadow grid grid-cols-2 overflow-hidden rounded-xl border border-[var(--border)] bg-[var(--surface)] lg:grid-cols-4">
            {[
              { label: "Routers online", value: fleet.error ? "—" : <>{online}<span className="text-[var(--fg-faint)]"> / {routers.length}</span></>, sub: routers.length ? `${down.length} offline or stale · ${degraded.length} degraded` : "No routers linked" },
              { label: "Customers online now", value: !reachable.length ? "0" : radius ? radius.live.toLocaleString() : "…", sub: radius ? `${radius.stale} open with no updates for 15 min` : reachable.length ? "Counting from RADIUS…" : "No reachable routers" },
              { label: "Auth failures (24h)", value: !reachable.length ? "0" : radius ? radius.rejects24h.toLocaleString() : "…", sub: "Rejected voucher logins" },
              { label: "Active vouchers", value: tenant.voucherCount.toLocaleString(), sub: "Unused or in use" },
            ].map((c, i) => (
              <div key={c.label} className={`border-[var(--border)] p-5 ${i % 2 === 0 ? "border-r" : ""} ${i < 2 ? "border-b lg:border-b-0" : ""} lg:border-r lg:last:border-r-0`}>
                <p className="text-[13px] font-medium text-[var(--fg-muted)]">{c.label}</p>
                <p className="mt-1.5 text-[1.6rem] font-semibold leading-tight tracking-tight text-[var(--fg)]">{c.value}</p>
                <p className="mt-1 text-xs text-[var(--fg-muted)]">{c.sub}</p>
              </div>
            ))}
          </section>
          {radius?.failed ? <p className="text-xs text-amber-700">RADIUS counts are partial: {radius.failed} router{radius.failed === 1 ? "" : "s"} didn&apos;t answer.</p> : null}

          <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
            <div className="min-w-0 space-y-5 lg:col-span-2">
              <ProblemsCard incidents={incidents} routers={routers} now={now} loading={overview.loading && !overview.data} />
              <Card title="Routers" description="Latest reported state for each location" action={routers.length > 0 && <button onClick={() => setTab("Routers")} className="text-xs font-medium text-[var(--accent-text)] hover:underline">Details</button>} bodyClassName="">
                {routers.length === 0 ? (
                  (fleet.loading ? <ListSkeleton rows={2} /> : <p className="px-5 py-10 text-center text-sm text-[var(--fg-muted)]">{"This business hasn't linked a MikroTik router yet."}</p>)
                ) : (
                  <ul className="divide-y divide-[var(--border-soft)]">
                    {routers.map((r) => (
                      <li key={r.id}>
                        <Link href={`/admin/network/routers/${r.id}`} className="flex items-center gap-3 px-5 py-3 transition-colors hover:bg-[var(--subtle)]">
                          <span className="shrink-0 sm:w-28" title={r.s.detail}><Badge tone={r.s.tone} dot>{r.s.label}</Badge></span>
                          <span className="min-w-0 flex-1">
                            <span className="block truncate text-[13.5px] font-medium text-[var(--fg)]">{r.name}</span>
                            <span className="block truncate text-xs text-[var(--fg-muted)]">{r.openSessions} open session{r.openSessions === 1 ? "" : "s"} · last report {relativeTime(r.lastSeen, now)}</span>
                          </span>
                          <CaretRight className="h-4 w-4 shrink-0 text-[var(--fg-faint)]" />
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </Card>
            </div>

            <div className="min-w-0 space-y-5">
              <Card title="Subscription" description="What this business pays AirPairy" bodyClassName="px-5 py-1" action={sub && <Badge tone={subscriptionTone(sub.displayStatus)} dot>{humanize(sub.displayStatus)}</Badge>}>
                {subs.error ? <p className="py-4 text-sm text-[var(--fg-muted)]">Unavailable.</p>
                  : !sub ? (subs.loading ? <ListSkeleton rows={2} avatar={false} inset={false} /> : <p className="py-4 text-sm text-[var(--fg-muted)]">No subscription set up. <Link href="/admin/subscriptions" className="text-[var(--accent-text)] hover:underline">Subscriptions</Link></p>)
                  : <dl className="divide-y divide-[var(--border-soft)]">
                      <Field label="Monthly fee"><span className="num">{ugx(sub.monthlyFee)}</span></Field>
                      <Field label="Next renewal">{shortDate(sub.nextRenewalDate)}</Field>
                      <Field label="Outstanding"><span className={`num ${sub.outstanding > 0 ? "text-amber-700" : ""}`}>{ugx(sub.outstanding)}</span></Field>
                    </dl>}
              </Card>
              <Card title="Customer collections" description="WiFi payments AirPairy holds for this business. Not platform revenue." bodyClassName="px-5 py-1">
                {wallets.error ? <p className="py-4 text-sm text-[var(--fg-muted)]">Unavailable.</p>
                  : !wallet ? (wallets.loading ? <ListSkeleton rows={2} avatar={false} inset={false} /> : <p className="py-4 text-sm text-[var(--fg-muted)]">No wallet yet. No customer payments collected.</p>)
                  : <dl className="divide-y divide-[var(--border-soft)]">
                      <Field label="Available to withdraw"><span className="num">{ugx(wallet.available)}</span></Field>
                      <Field label="Pending settlement"><span className="num">{ugx(wallet.pending)}</span></Field>
                      <Field label="Reserved"><span className="num">{ugx(wallet.reserved)}</span></Field>
                      <Field label="Collected all time"><span className="num">{ugx(wallet.totalCollected)}</span></Field>
                      <Field label="Withdrawn all time"><span className="num">{ugx(wallet.totalWithdrawn)}</span></Field>
                    </dl>}
              </Card>
            </div>
          </div>
          <p className="text-[11.5px] leading-relaxed text-[var(--fg-faint)]">This business&apos;s packages and individual customer transactions aren&apos;t exposed to the platform API, so they aren&apos;t shown here.</p>
        </div>
      )}

      {tab === "Routers" && <RoutersTab routers={routers} now={now} loading={fleet.loading} />}
      {tab === "Live sessions" && <SessionsTab routers={reachable} now={now} />}
      {tab === "Auth failures" && <AuthTab routers={reachable} />}
      {tab === "Billing" && <BillingTab tenantId={tenantId} sub={sub ?? null} subsLoading={subs.loading} wallet={wallet ?? null} />}
    </div>
  );
}

function initials(name: string) {
  const p = name.trim().split(/\s+/);
  return (p.length > 1 ? p[0][0] + p[1][0] : name.slice(0, 2)).toUpperCase();
}

type Routed = RouterRow & { s: RouterStateInfo };

function healthVerdict(total: number, online: number, down: Routed[], degraded: number, incidents: number, live: number | undefined, loading: boolean, now: number) {
  const liveText = live == null ? "" : ` · ${live} customer${live === 1 ? "" : "s"} online now`;
  if (loading && !total) return { tone: "slate", title: "Checking routers…", detail: "Loading the latest telemetry.", box: "border-[var(--border)] bg-[var(--surface)] text-[var(--fg-body)]" };
  if (!total) return { tone: "slate", title: "No router linked yet", detail: "Customers can't connect until this business links and provisions a MikroTik router.", box: "border-[var(--border)] bg-[var(--surface)] text-[var(--fg-body)]" };
  if (down.length) {
    const worst = down[0];
    return { tone: "red", title: `${down.length} of ${total} router${total === 1 ? "" : "s"} not reporting`, detail: `${worst.name} ${worst.lastSeen ? `last reported ${relativeTime(worst.lastSeen, now)}` : "has never reported"}${incidents ? ` · ${incidents} open incident${incidents === 1 ? "" : "s"}` : ""}${liveText}`, box: "border-red-200 bg-red-50 text-red-800" };
  }
  if (degraded || incidents) return { tone: "amber", title: degraded ? `${degraded} router${degraded === 1 ? "" : "s"} degraded` : `${incidents} open incident${incidents === 1 ? "" : "s"}`, detail: `${online} of ${total} online${liveText}`, box: "border-amber-200 bg-amber-50 text-amber-900" };
  return { tone: "green", title: total === 1 ? "Router online and healthy" : `All ${total} routers online`, detail: `No open incidents${liveText}`, box: "border-emerald-200 bg-emerald-50 text-emerald-800" };
}

function ProblemsCard({ incidents, routers, now, loading }: { incidents: Incident[]; routers: Routed[]; now: number; loading: boolean }) {
  const withIncident = new Set(incidents.map((i) => i.routerId));
  const extra = routers.filter((r) => !withIncident.has(r.id) && (r.s.state === "stale" || (r.s.state === "online" && !r.bindingsSynced)));
  const total = incidents.length + extra.length;
  return (
    <Card title="Needs attention" description="Open incidents and router issues for this business" bodyClassName="">
      {loading ? <ListSkeleton rows={2} avatar={false} />
        : total === 0 ? (
          <div className="flex items-center gap-3 px-5 py-5">
            <CheckCircle className="h-5 w-5 text-emerald-600" />
            <p className="text-sm text-[var(--fg-body)]">Nothing needs attention.</p>
          </div>
        ) : (
          <ul className="divide-y divide-[var(--border-soft)]">
            {incidents.map((i) => (
              <li key={`i${i.id}`}>
                <Link href={`/admin/network/routers/${i.routerId}`} className="flex items-start gap-3 px-5 py-3 hover:bg-[var(--subtle)]">
                  <span className="w-20 shrink-0 pt-0.5"><Badge tone={severityTone(i.severity)} dot>{humanize(i.severity)}</Badge></span>
                  <span className="min-w-0 flex-1"><span className="block text-[13.5px] font-medium text-[var(--fg)]">{i.title}</span><span className="block truncate text-xs text-[var(--fg-muted)]">{i.routerName}{i.detail ? ` · ${i.detail}` : ""}</span></span>
                  <span className="shrink-0 text-xs text-[var(--fg-faint)]">{relativeTime(i.openedAt, now)}</span>
                </Link>
              </li>
            ))}
            {extra.map((r) => (
              <li key={`r${r.id}`}>
                <Link href={`/admin/network/routers/${r.id}`} className="flex items-start gap-3 px-5 py-3 hover:bg-[var(--subtle)]">
                  <span className="w-20 shrink-0 pt-0.5"><Badge tone="amber" dot>{r.s.state === "stale" ? "Stale" : "Sync"}</Badge></span>
                  <span className="min-w-0 flex-1"><span className="block text-[13.5px] font-medium text-[var(--fg)]">{r.s.state === "stale" ? "Telemetry stopped arriving" : "IP bindings not synced"}</span><span className="block truncate text-xs text-[var(--fg-muted)]">{r.name}</span></span>
                  <span className="shrink-0 text-xs text-[var(--fg-faint)]">{relativeTime(r.lastSeen, now)}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
    </Card>
  );
}

function RoutersTab({ routers, now, loading }: { routers: Routed[]; now: number; loading: boolean }) {
  return (
    <Table rows={routers} loading={loading} rowKey={(r) => r.id} searchable={routers.length > 6} paginated={routers.length > 10}
      emptyIcon={<RouterIcon />} emptyTitle="No routers linked" emptyDescription="Routers appear here once this business links one."
      columns={[
        { label: "Router", render: (r) => <Link href={`/admin/network/routers/${r.id}`} className="group block"><span className="block font-medium text-[var(--fg)] group-hover:text-[var(--accent-text)] group-hover:underline">{r.name}</span><span className="block font-mono text-[11px] text-[var(--fg-faint)]">{r.wgIp || "No tunnel IP"}</span></Link> },
        { label: "State", render: (r) => <span title={r.s.detail}><Badge tone={r.s.tone} dot>{r.s.label}</Badge></span> },
        { label: "Services", render: (r) => { const s = servicesState([r.hotspotReady, r.radiusReady, r.portalReady]); return <StatusDot tone={s.tone}>{s.label}</StatusDot>; }, hideBelow: "md" },
        { label: "CPU", render: (r) => r.s.state === "online" || r.s.state === "degraded" ? <Meter value={r.cpuLoad} label="CPU" /> : <span className="text-xs text-[var(--fg-faint)]">—</span>, hideBelow: "lg" },
        { label: "Memory", render: (r) => r.s.state === "online" || r.s.state === "degraded" ? <Meter value={r.memoryUsage} label="Memory" /> : <span className="text-xs text-[var(--fg-faint)]">—</span>, hideBelow: "lg" },
        { label: "Open sessions", render: (r) => r.openSessions.toLocaleString(), align: "right", hideBelow: "sm" },
        { label: "Last report", render: (r) => <span className="text-xs text-[var(--fg-muted)]" title={r.lastSeen ? new Date(r.lastSeen).toLocaleString() : undefined}>{relativeTime(r.lastSeen, now)}</span>, align: "right" },
      ]} />
  );
}

function SessionsTab({ routers, now }: { routers: Routed[]; now: number }) {
  const [state, setState] = useState<"LIVE" | "OPEN">("LIVE");
  const [data, setData] = useState<{ rows: (SessionRow & { router: string })[]; truncated: boolean; failed: number } | null>(null);
  const key = routers.map((r) => r.id).join(",");
  useEffect(() => {
    if (!routers.length) return;
    let live = true;
    acrossRouters<SessionRow>(routers, "sessions", { openOnly: true, state: state === "LIVE" ? "LIVE" : "", size: 100 }).then(({ pages, failed }) => {
      if (!live) return;
      const rows = pages.flatMap((p) => (p.page?.content ?? []).map((s) => ({ ...s, router: p.router.name })));
      rows.sort((a, b) => new Date(b.startedAt || 0).getTime() - new Date(a.startedAt || 0).getTime());
      setData({ rows, truncated: pages.some((p) => (p.page?.totalElements ?? 0) > 100), failed });
    });
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- keyed on router ids
  }, [key, state]);

  if (!routers.length) return <Card><p className="py-8 text-center text-sm text-[var(--fg-muted)]">No router with a tunnel IP, so there are no RADIUS sessions to show.</p></Card>;
  return (
    <Card bodyClassName="">
      <div className="flex flex-col gap-2 border-b border-[var(--border)] px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
        <Segmented label="Sessions" value={state} onChange={(v) => { setData(null); setState(v); }} options={[{ key: "LIVE", label: "Live now" }, { key: "OPEN", label: "All open, incl. silent" }]} />
        <span className="text-xs text-[var(--fg-muted)]">{data ? `${data.rows.length} session${data.rows.length === 1 ? "" : "s"} across ${routers.length} router${routers.length === 1 ? "" : "s"}` : "Loading…"}</span>
      </div>
      {data?.failed ? <div className="px-4 pt-3"><ErrorNotice text={`${data.failed} router${data.failed === 1 ? "" : "s"} didn't return sessions; the list is incomplete.`} /></div> : null}
      <Table bare searchPlaceholder="Search voucher, IP or MAC…" rows={data?.rows ?? []} loading={!data} rowKey={(s) => `${s.router}-${s.id}`}
        emptyTitle={state === "LIVE" ? "Nobody is online right now" : "No open sessions"} emptyDescription="Sessions appear when customers sign in to the hotspot."
        columns={[
          { label: "Customer", render: (s) => <span><span className="block font-mono text-[13px] font-medium text-[var(--fg)]">{s.username}</span><span className="text-[11.5px] text-[var(--fg-muted)]">{s.packageName || "Package unknown"}</span></span> },
          { label: "State", render: (s) => { const st = sessionState(s.state); return <Badge tone={st.tone} dot>{st.label}</Badge>; } },
          { label: "Router", render: (s) => <span className="text-[13px]">{s.router}</span>, hideBelow: "md" },
          { label: "Device", render: (s) => <span className="font-mono text-xs">{s.framedIp || "—"}<br /><span className="text-[var(--fg-faint)]">{s.mac || "—"}</span></span>, hideBelow: "lg" },
          { label: "Online for", render: (s) => duration(s.sessionSeconds), align: "right", hideBelow: "sm" },
          { label: "Data used", render: (s) => <span className="text-xs">{bytes((s.downloadOctets ?? 0) + (s.uploadOctets ?? 0))}</span>, align: "right", hideBelow: "sm" },
          { label: "Time left", render: (s) => s.remainingSeconds == null ? "—" : duration(s.remainingSeconds), align: "right" },
          { label: "Last update", render: (s) => <span className="text-xs text-[var(--fg-muted)]">{relativeTime(s.lastUpdateAt, now)}</span>, align: "right", hideBelow: "lg" },
        ]} />
      {data?.truncated && <p className="border-t border-[var(--border)] px-4 py-2.5 text-xs text-amber-700">Showing the newest 100 sessions per router. Open a router for the full list.</p>}
    </Card>
  );
}

function AuthTab({ routers }: { routers: Routed[] }) {
  const [data, setData] = useState<{ rows: (AuthEvent & { router: string })[]; failed: number } | null>(null);
  const key = routers.map((r) => r.id).join(",");
  useEffect(() => {
    if (!routers.length) return;
    let live = true;
    acrossRouters<AuthEvent>(routers, "auth-events", { reply: "Access-Reject", size: 50 }).then(({ pages, failed }) => {
      if (!live) return;
      const rows = pages.flatMap((p) => (p.page?.content ?? []).map((e) => ({ ...e, router: p.router.name })));
      rows.sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime());
      setData({ rows, failed });
    });
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- keyed on router ids
  }, [key]);
  if (!routers.length) return <Card><p className="py-8 text-center text-sm text-[var(--fg-muted)]">No router with a tunnel IP, so there are no authentication logs.</p></Card>;
  return (
    <Card title="Rejected logins" description="Most recent 50 per router, newest first. Repeated rejects for one voucher usually mean it expired or was mistyped." bodyClassName="">
      {data?.failed ? <div className="px-4 pt-3"><ErrorNotice text={`${data.failed} router${data.failed === 1 ? "" : "s"} didn't return auth events.`} /></div> : null}
      <Table bare searchPlaceholder="Search voucher or MAC…" rows={data?.rows ?? []} loading={!data} rowKey={(e) => `${e.router}-${e.id}`}
        emptyIcon={<CheckCircle />} emptyTitle="No rejected logins" emptyDescription="Every recent sign-in attempt was accepted."
        columns={[
          { label: "Time", render: (e) => <span className="text-xs">{date(e.at)}</span> },
          { label: "Voucher / user", render: (e) => <span className="font-mono text-[13px]">{e.username}</span> },
          { label: "Router", render: (e) => <span className="text-[13px]">{e.router}</span>, hideBelow: "sm" },
          { label: "Client MAC", render: (e) => <span className="font-mono text-xs">{e.mac || "—"}</span>, hideBelow: "md" },
          { label: "Reason", render: (e) => <span className="text-xs text-[var(--fg-muted)]">{e.description}</span>, hideBelow: "lg" },
        ]} />
    </Card>
  );
}

function BillingTab({ tenantId, sub, subsLoading, wallet }: { tenantId: number; sub: Subscription | null; subsLoading: boolean; wallet: Wallet | null }) {
  const withdrawalsRes = useResource<WithdrawalRow>(`/api/admin/finance/withdrawals?tenantId=${tenantId}`);
  const billing = useApi<Billing>(sub ? `/api/admin/subscriptions/${sub.id}/billing` : null);
  if (!sub) {
    return <Card>{subsLoading ? <ListSkeleton rows={3} avatar={false} inset={false} /> : <><p className="py-8 text-center text-sm text-[var(--fg-muted)]">This business has no subscription. Create one from Subscriptions.</p><div className="pb-4 text-center"><CardLink href="/admin/subscriptions">Subscriptions</CardLink></div></>}</Card>;
  }
  return (
    <div className="min-w-0 space-y-5">
      <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
        <Card title="Subscription" description="Monthly platform fee this business pays AirPairy" action={<Badge tone={subscriptionTone(sub.displayStatus)} dot>{humanize(sub.displayStatus)}</Badge>} bodyClassName="px-5 py-1">
          <dl className="divide-y divide-[var(--border-soft)]">
            <Field label="Monthly fee"><span className="num">{ugx(sub.monthlyFee)}</span></Field>
            <Field label="Started">{shortDate(sub.startDate)}</Field>
            <Field label="Next renewal">{shortDate(sub.nextRenewalDate)}</Field>
            <Field label="Outstanding"><span className={`num ${sub.outstanding > 0 ? "text-amber-700" : ""}`}>{ugx(sub.outstanding)}</span></Field>
          </dl>
        </Card>
        <Card title="Customer collections" description="WiFi customer payments held for this business. Separate from the subscription." bodyClassName="px-5 py-1">
          {!wallet ? <p className="py-4 text-sm text-[var(--fg-muted)]">No wallet yet.</p> : (
            <dl className="divide-y divide-[var(--border-soft)]">
              <Field label="Available"><span className="num">{ugx(wallet.available)}</span></Field>
              <Field label="Pending"><span className="num">{ugx(wallet.pending)}</span></Field>
              <Field label="Collected all time"><span className="num">{ugx(wallet.totalCollected)}</span></Field>
              <Field label="Withdrawn all time"><span className="num">{ugx(wallet.totalWithdrawn)}</span></Field>
            </dl>
          )}
        </Card>
      </div>
      {billing.error && <ErrorNotice text={`Couldn't load billing history: ${billing.error}`} />}
      <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
        <Card title="Invoices" bodyClassName="">
          <Table bare searchable={false} paginated={(billing.data?.invoices.length ?? 0) > 10} rows={billing.data?.invoices ?? []} loading={billing.loading && !billing.data} rowKey={(i) => i.id}
            emptyTitle="No invoices yet"
            columns={[
              { label: "Period", render: (i) => <span className="text-xs">{shortDate(i.periodStart)} – {shortDate(i.periodEnd)}</span> },
              { label: "Amount", render: (i) => ugx(i.amountDue), align: "right" },
              { label: "Status", render: (i) => <Badge tone={i.status === "PAID" ? "green" : i.status === "UNPAID" ? "amber" : "slate"} dot>{humanize(i.status)}</Badge> },
            ]} />
        </Card>
        <Card title="Payments received" bodyClassName="">
          <Table bare searchable={false} paginated={(billing.data?.payments.length ?? 0) > 10} rows={billing.data?.payments ?? []} loading={billing.loading && !billing.data} rowKey={(p) => p.id}
            emptyTitle="No payments recorded"
            columns={[
              { label: "Date", render: (p) => <span className="text-xs">{shortDate(p.paidAt)}</span> },
              { label: "Amount", render: (p) => ugx(p.amount), align: "right" },
              { label: "Method", render: (p) => <span className="text-xs">{humanize(p.method)}{p.externalRef ? <span className="block font-mono text-[11px] text-[var(--fg-faint)]">{p.externalRef}</span> : null}</span>, hideBelow: "sm" },
            ]} />
        </Card>
      </div>
            <Card title="Withdrawal history" description="All mobile money payouts and withdrawal requests for this tenant." bodyClassName="">
        {withdrawalsRes.error && <div className="px-5 pt-3"><ErrorNotice text={`Couldn't load withdrawals: ${withdrawalsRes.error}`} /></div>}
        <Table bare searchable={false} paginated={(withdrawalsRes.data.length ?? 0) > 10} rows={withdrawalsRes.data ?? []} loading={withdrawalsRes.loading} rowKey={(w) => w.id}
          emptyTitle="No withdrawals recorded"
          emptyDescription="This tenant has not made any withdrawal requests yet."
          columns={[
            { label: "Date", render: (w) => <span className="text-xs text-[var(--fg-muted)]">{w.createdAt ? date(w.createdAt) : "—"}</span> },
            { label: "Phone", render: (w) => <span className="font-mono text-xs font-medium">{w.phone}</span> },
            { label: "Amount", render: (w) => <span className="num font-semibold">{ugx(w.amount)}</span>, align: "right" },
            { label: "Fee", render: (w) => <span className="num text-xs text-[var(--fg-muted)]">{ugx(w.fee)}</span>, align: "right", hideBelow: "sm" },
            { label: "Total debited", render: (w) => <span className="num text-xs font-medium">{ugx(w.totalDeduction)}</span>, align: "right", hideBelow: "md" },
            { label: "Status", render: (w) => (
              <span>
                <Badge tone={WITHDRAWAL_STATUS_TONE[w.status] || "slate"} dot>{humanize(w.status)}</Badge>
                {w.failureReason && <span className="mt-0.5 block text-[11px] text-red-500">{w.failureReason}</span>}
              </span>
            ) },
          ]} />
      </Card>
      <p className="text-xs text-[var(--fg-muted)]">To record a payment or change the fee, use <Link href="/admin/subscriptions" className="text-[var(--accent-text)] hover:underline">Subscriptions</Link>.</p>
    </div>
  );
}

function DetailSkeleton() {
  return (
    <div className="mx-auto max-w-7xl animate-pulse space-y-6 motion-reduce:animate-none" aria-busy="true">
      <div className="h-3 w-20 rounded bg-[var(--fill)]" />
      <div className="flex items-center gap-3.5"><div className="h-12 w-12 rounded-xl bg-[var(--fill)]" /><div className="space-y-2"><div className="h-5 w-56 rounded bg-[var(--border)]" /><div className="h-3 w-80 rounded bg-[var(--fill)]" /></div></div>
      <div className="h-16 rounded-xl border border-[var(--border)] bg-[var(--surface)]" />
      <div className="h-28 rounded-xl border border-[var(--border)] bg-[var(--surface)]" />
      <div className="grid grid-cols-1 gap-5 lg:grid-cols-3"><div className="h-72 rounded-xl border border-[var(--border)] bg-[var(--surface)] lg:col-span-2" /><div className="h-72 rounded-xl border border-[var(--border)] bg-[var(--surface)]" /></div>
    </div>
  );
}

