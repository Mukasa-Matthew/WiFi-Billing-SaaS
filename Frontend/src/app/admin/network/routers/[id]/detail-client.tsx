"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import api from "@/lib/api";
import {
  ArrowClockwise, CellTower as RouterIcon, CheckCircle, MagnifyingGlass,
} from "@/components/icons";
import {
  BackLink, Badge, Card, ErrorNotice, Field, Pager, Segmented, StatusDot, Table, Tabs, buttonGhost,
  bytes, bytesTick, date, field, humanize, message, relativeTime, useNow,
} from "@/components/BillingUI";
import { BarChart, Meter } from "@/components/charts";
import { duration, routerState, servicesState, sessionState, severityTone } from "@/lib/routerState";

interface RouterRow {
  id: number; tenantId: number; tenantName: string; name: string; status: string;
  wgIp: string | null; cpuLoad: number | null; memoryUsage: number | null;
  uptime: string | null; activeUsers: number | null;
  hotspotReady: boolean | null; radiusReady: boolean | null; portalReady: boolean | null;
  bindingsSynced: boolean; provisioningState: string;
  lastSeen: string | null; bindingsSyncedAt: string | null; hostsSeenAt: string | null;
  openSessions: number; openIncidents: number; createdAt: string;
}
interface Detail { router: RouterRow; radiusNasRegistered: string; lastAccountingAt: string | null }
interface SessionRow {
  id: number; username: string; acctSessionId: string; framedIp: string | null;
  mac: string | null; nasIp: string | null; startedAt: string | null;
  lastUpdateAt: string | null; stoppedAt: string | null; sessionSeconds: number | null;
  uploadOctets: number | null; downloadOctets: number | null; terminateCause: string | null;
  state: string; packageId: number | null; packageName: string | null; remainingSeconds: number | null;
}
interface Paged<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
interface AuthEvent { id: number; at: string; username: string; reply: string; mac: string | null; severity: string; description: string }
interface Task { id: number; action: string; status: string; createdAt: string; appliedAt: string | null }
interface Event { at: string; type: string; severity: string; title: string; detail: string | null }
interface Incident { id: number; type: string; severity: string; title: string; detail: string | null; openedAt: string; resolvedAt: string | null }
interface Check { key: string; label: string; status: string; source: string; detail: string | null; recommendation: string | null }
interface UsagePoint { day: string; uploadBytes: number; downloadBytes: number; users: number }
interface Live { reachable: boolean; source: string; probedAt: string; uptime?: string; cpuLoad?: number; memoryUsagePct?: number; boardName?: string; routerOsVersion?: string; hotspotActive?: number | null; wgHandshakeSecondsAgo?: number | null; error?: string | null }
interface Host { [k: string]: unknown }

const TABS = ["Overview", "Sessions", "Accounting", "Auth events", "Devices", "Provisioning", "Events", "Diagnostics"] as const;
type Tab = typeof TABS[number];

const checkTone = (s: string) => s === "HEALTHY" ? "green" : s === "WARNING" ? "amber" : s === "CRITICAL" ? "red" : "slate";
const flagDot = (v: boolean | null) => v === true ? <StatusDot tone="green">Ready</StatusDot> : v === false ? <StatusDot tone="red">Not ready</StatusDot> : <StatusDot tone="slate">Not reported</StatusDot>;

function useDebounced<T>(value: T, ms = 300) {
  const [v, setV] = useState(value);
  useEffect(() => { const t = window.setTimeout(() => setV(value), ms); return () => window.clearTimeout(t); }, [value, ms]);
  return v;
}

/** Server-paged GET with stale-response protection; errors clear on the next success. */
function usePaged<T>(url: string, params: Record<string, unknown>) {
  const key = JSON.stringify(params);
  const [state, setState] = useState<{ data: Paged<T> | null; error: string; loading: boolean }>({ data: null, error: "", loading: true });
  const [nonce, setNonce] = useState(0);
  useEffect(() => {
    let live = true;
    const run = window.setTimeout(() => {
      setState((s) => ({ ...s, loading: true }));
      api.get<Paged<T>>(url, { params: JSON.parse(key) })
        .then((r) => { if (live) setState({ data: r.data, error: "", loading: false }); })
        .catch((e) => { if (live) setState((s) => ({ ...s, error: message(e), loading: false })); });
    }, 0);
    return () => { live = false; window.clearTimeout(run); };
  }, [url, key, nonce]);
  return { ...state, reload: () => setNonce((n) => n + 1) };
}

function SearchInput({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder: string }) {
  return (
    <div className="relative w-full sm:w-56">
      <MagnifyingGlass className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-[var(--fg-muted)]" />
      <input value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} aria-label={placeholder}
        className="w-full rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] py-1.5 pl-9 pr-3 text-xs text-[var(--fg)] outline-none focus:border-[var(--ring)]" />
    </div>
  );
}

export default function RouterDetailClient() {
  return <Suspense><RouterDetailInner /></Suspense>;
}

function RouterDetailInner() {
  const { id } = useParams<{ id: string }>();
  const routerId = Number(id);
  const now = useNow();
  const [tab, setTab] = useState<Tab>("Overview");
  const [detail, setDetail] = useState<Detail | null>(null);
  const [error, setError] = useState("");
  const [live, setLive] = useState<Live | null>(null);
  const [liveBusy, setLiveBusy] = useState(false);

  const load = useCallback(() => {
    api.get<Detail>(`/api/admin/noc/routers/${routerId}`)
      .then((r) => { setDetail(r.data); setError(""); })
      .catch((e) => setError(message(e) === "Router not found" ? "This router doesn't exist or was removed." : `Couldn't load this router: ${message(e)}`));
  }, [routerId]);
  useEffect(() => {
    const first = window.setTimeout(load, 0);
    const timer = window.setInterval(() => { if (document.visibilityState === "visible") load(); }, 60000);
    return () => { window.clearTimeout(first); window.clearInterval(timer); };
  }, [load]);

  // Read-only REST query over the WireGuard tunnel; it changes nothing on the router.
  const runProbe = () => {
    setLiveBusy(true);
    api.get<Live>(`/api/admin/noc/routers/${routerId}/live`)
      .then((r) => setLive(r.data))
      .catch((e) => setLive({ reachable: false, source: "UNAVAILABLE", probedAt: "", error: message(e) }))
      .finally(() => setLiveBusy(false));
  };

  const r = detail?.router;
  const s = r ? routerState(r, now) : null;
  const telemetryCurrent = s?.state === "online" || s?.state === "degraded";

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <div>
        <BackLink href="/admin/routers">Router fleet</BackLink>
        <div className="mt-3 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div className="flex min-w-0 items-start gap-3.5">
            <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-[var(--fill)] text-[var(--fg-muted)]"><RouterIcon className="h-5 w-5" /></span>
            <div className="min-w-0">
              <h1 className="flex flex-wrap items-center gap-2.5 text-[1.45rem] font-semibold leading-tight tracking-tight text-[var(--fg)]">
                {r?.name ?? (error ? "Router" : "Loading…")}
                {s && <span title={s.detail}><Badge tone={s.tone} dot>{s.label}</Badge></span>}
              </h1>
              {r && (
                <p className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-[13px] text-[var(--fg-muted)]">
                  <Link href={`/admin/tenants/${r.tenantId}`} className="font-medium text-[var(--fg-body)] hover:underline">{r.tenantName}</Link>
                  <span aria-hidden>·</span><span className="font-mono text-xs">{r.wgIp || "No tunnel IP"}</span>
                  <span aria-hidden>·</span><span>{r.provisioningState}</span>
                </p>
              )}
            </div>
          </div>
          <button onClick={runProbe} disabled={liveBusy || !r} className={buttonGhost} title="Reads system resources over the tunnel. Makes no changes.">
            <ArrowClockwise className={`h-4 w-4 ${liveBusy ? "animate-spin motion-reduce:animate-none" : ""}`} />{liveBusy ? "Probing…" : "Run live probe"}
          </button>
        </div>
      </div>

      <ErrorNotice text={error} />

      {r && s && (
        <section className="panel-shadow grid grid-cols-2 overflow-hidden rounded-xl border border-[var(--border)] bg-[var(--surface)] lg:grid-cols-5">
          {[
            { label: "Connectivity", value: s.label, sub: r.lastSeen ? `Last report ${relativeTime(r.lastSeen, now)}` : "Never reported", title: r.lastSeen ? new Date(r.lastSeen).toLocaleString() : undefined },
            { label: "Open sessions", value: r.openSessions.toLocaleString(), sub: telemetryCurrent && r.activeUsers != null ? `${r.activeUsers} hotspot users reported` : "RADIUS accounting" },
            { label: "CPU", node: telemetryCurrent ? <Meter value={r.cpuLoad} label="CPU load" /> : <span className="text-sm text-[var(--fg-faint)]">Out of date</span>, sub: telemetryCurrent ? "From last report" : `Last known ${r.cpuLoad ?? "—"}%` },
            { label: "Memory", node: telemetryCurrent ? <Meter value={r.memoryUsage} label="Memory usage" /> : <span className="text-sm text-[var(--fg-faint)]">Out of date</span>, sub: telemetryCurrent ? `Uptime ${r.uptime || "—"}` : `Last known ${r.memoryUsage ?? "—"}%` },
            { label: "Last accounting", value: relativeTime(detail.lastAccountingAt, now), sub: `NAS ${detail.radiusNasRegistered.toLowerCase()}`, title: detail.lastAccountingAt ? new Date(detail.lastAccountingAt).toLocaleString() : undefined },
          ].map((c, i) => (
            <div key={c.label} title={c.title} className={`border-[var(--border)] p-4 ${i % 2 === 0 ? "border-r" : ""} ${i < 4 ? "border-b lg:border-b-0" : "col-span-2 lg:col-span-1"} lg:border-r lg:last:border-r-0`}>
              <p className="text-xs font-medium text-[var(--fg-muted)]">{c.label}</p>
              <div className="mt-1.5 flex h-7 items-center text-lg font-semibold text-[var(--fg)]">{c.node ?? c.value}</div>
              <p className="mt-0.5 truncate text-[11.5px] text-[var(--fg-muted)]">{c.sub}</p>
            </div>
          ))}
        </section>
      )}

      {live && (
        <section className={`flex flex-wrap items-center gap-x-5 gap-y-2 rounded-xl border px-4 py-3 text-[13px] ${live.reachable ? "border-emerald-200 bg-emerald-50 text-emerald-800" : "border-red-200 bg-red-50 text-red-700"}`}>
          <span className="font-semibold">{live.reachable ? "Router answered the live probe" : "Live probe failed"}</span>
          {live.routerOsVersion && <span>RouterOS {live.routerOsVersion}</span>}
          {live.boardName && <span>{live.boardName}</span>}
          {live.cpuLoad != null && <span>CPU {live.cpuLoad}%</span>}
          {live.memoryUsagePct != null && <span>RAM {live.memoryUsagePct}%</span>}
          {live.uptime && <span>Up {live.uptime}</span>}
          {live.hotspotActive != null && <span>{live.hotspotActive} hotspot users</span>}
          {live.wgHandshakeSecondsAgo != null && <span>Tunnel handshake {live.wgHandshakeSecondsAgo}s ago</span>}
          {live.error && <span>{live.error}</span>}
          {live.probedAt && <span className="ml-auto text-xs opacity-80">{date(live.probedAt)}</span>}
        </section>
      )}

      <Tabs label="Router sections" tabs={TABS} value={tab} onChange={setTab} />

      {tab === "Overview" && detail && <OverviewTab detail={detail} now={now} />}
      {tab === "Sessions" && <SessionsTab routerId={routerId} now={now} />}
      {tab === "Accounting" && <AccountingTab routerId={routerId} />}
      {tab === "Auth events" && <AuthTab routerId={routerId} />}
      {tab === "Devices" && <DevicesTab routerId={routerId} now={now} />}
      {tab === "Provisioning" && detail && <ProvisioningTab routerId={routerId} detail={detail} />}
      {tab === "Events" && <EventsTab routerId={routerId} />}
      {tab === "Diagnostics" && <DiagnosticsTab routerId={routerId} />}
    </div>
  );
}

/* ---------------- Overview ---------------- */
function OverviewTab({ detail, now }: { detail: Detail; now: number }) {
  const r = detail.router;
  const [usage, setUsage] = useState<UsagePoint[] | null>(null);
  const [usageErr, setUsageErr] = useState("");
  const [incidents, setIncidents] = useState<Incident[] | null>(null);
  const [incErr, setIncErr] = useState("");
  useEffect(() => {
    api.get<UsagePoint[]>(`/api/admin/noc/routers/${r.id}/usage`, { params: { days: 14 } })
      .then((x) => setUsage(x.data)).catch((e) => setUsageErr(message(e)));
    api.get<Incident[]>(`/api/admin/noc/routers/${r.id}/incidents`)
      .then((x) => setIncidents(x.data)).catch((e) => setIncErr(message(e)));
  }, [r.id]);
  const open = (incidents ?? []).filter((i) => !i.resolvedAt);
  const recentResolved = (incidents ?? []).filter((i) => i.resolvedAt).slice(0, 3);
  const svc = servicesState([r.hotspotReady, r.radiusReady, r.portalReady]);

  return (
    <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
      <div className="min-w-0 space-y-5 lg:col-span-2">
        <Card title="Open incidents" description="Evaluated every minute from stored telemetry and task results" bodyClassName="">
          {incErr ? <p className="px-5 py-8 text-sm text-[var(--fg-muted)]">Incidents unavailable: {incErr}</p>
            : !incidents ? <p className="px-5 py-8 text-sm text-[var(--fg-muted)]">Loading…</p>
            : open.length === 0 ? (
              <div className="flex items-center gap-3 px-5 py-5">
                <CheckCircle className="h-5 w-5 text-emerald-600" />
                <div><p className="text-sm font-medium text-[var(--fg-body)]">No open incidents</p>
                  {recentResolved.length > 0 && <p className="text-xs text-[var(--fg-muted)]">Last resolved: {recentResolved[0].title}, {relativeTime(recentResolved[0].resolvedAt, now)}</p>}</div>
              </div>
            ) : (
              <ul className="divide-y divide-[var(--border-soft)]">
                {open.map((i) => (
                  <li key={i.id} className="flex items-start gap-3 px-5 py-3.5">
                    <span className="w-20 shrink-0 pt-0.5"><Badge tone={severityTone(i.severity)} dot>{humanize(i.severity)}</Badge></span>
                    <span className="min-w-0 flex-1"><span className="block text-[13.5px] font-medium text-[var(--fg)]">{i.title}</span>{i.detail && <span className="block text-xs text-[var(--fg-muted)]">{i.detail}</span>}</span>
                    <span className="shrink-0 text-xs text-[var(--fg-faint)]" title={new Date(i.openedAt).toLocaleString()}>{relativeTime(i.openedAt, now)}</span>
                  </li>
                ))}
              </ul>
            )}
        </Card>
        <Card title="Daily data usage" description="Download + upload from RADIUS accounting, last 14 days with data">
          {usageErr ? <p className="py-10 text-center text-sm text-[var(--fg-muted)]">Usage unavailable: {usageErr}</p>
            : !usage ? <p className="py-10 text-center text-sm text-[var(--fg-muted)]">Loading…</p>
            : usage.length === 0 ? <p className="py-10 text-center text-sm text-[var(--fg-muted)]">No accounting data in the last 14 days.</p>
            : <BarChart height={220} label="Daily data usage" format={(v) => bytes(v)} tickFormat={bytesTick}
                data={usage.map((u) => {
                  const d = new Date(u.day);
                  return { key: u.day, label: d.toLocaleDateString(undefined, { day: "numeric", month: "short" }), value: u.uploadBytes + u.downloadBytes,
                    detail: `${d.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" })} · ${u.users} user${u.users === 1 ? "" : "s"} · ↓ ${bytes(u.downloadBytes)} ↑ ${bytes(u.uploadBytes)}` };
                })} />}
        </Card>
      </div>
      <div className="min-w-0 space-y-5">
        <Card title="Service readiness" action={<Badge tone={svc.tone} dot>{svc.label}</Badge>} bodyClassName="px-5 py-1">
          <dl className="divide-y divide-[var(--border-soft)]">
            <Field label="Hotspot service">{flagDot(r.hotspotReady)}</Field>
            <Field label="RADIUS client">{flagDot(r.radiusReady)}</Field>
            <Field label="Captive portal files">{flagDot(r.portalReady)}</Field>
            <Field label="FreeRADIUS NAS entry"><StatusDot tone={detail.radiusNasRegistered === "Registered" ? "green" : "amber"}>{detail.radiusNasRegistered}</StatusDot></Field>
            <Field label="IP bindings">{r.bindingsSynced ? <StatusDot tone="green">Synced</StatusDot> : <StatusDot tone="amber">Pending sync</StatusDot>}</Field>
          </dl>
        </Card>
        <Card title="Identity" bodyClassName="px-5 py-1">
          <dl className="divide-y divide-[var(--border-soft)]">
            <Field label="Router ID" mono>#{r.id}</Field>
            <Field label="Tunnel IP" mono>{r.wgIp || "Unassigned"}</Field>
            <Field label="Registered">{date(r.createdAt)}</Field>
            <Field label="Host list pushed">{relativeTime(r.hostsSeenAt, now)}</Field>
          </dl>
          <p className="pb-4 pt-1 text-[11.5px] leading-relaxed text-[var(--fg-faint)]">Board model and RouterOS version aren&apos;t in telemetry; run a live probe to read them. Tokens, keys and RADIUS secrets are never shown.</p>
        </Card>
      </div>
    </div>
  );
}

/* ---------------- Sessions ---------------- */
function SessionsTab({ routerId, now }: { routerId: number; now: number }) {
  const [username, setUsername] = useState("");
  const q = useDebounced(username);
  const [state, setState] = useState<"OPEN" | "LIVE" | "OPEN_STALE" | "ALL">("OPEN");
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = usePaged<SessionRow>(`/api/admin/noc/routers/${routerId}/sessions`, {
    openOnly: state !== "ALL", state: state === "OPEN" || state === "ALL" ? "" : state, username: q, page, size: 25,
  });

  return (
    <Card bodyClassName="">
      <div className="flex flex-col gap-3 border-b border-[var(--border)] px-4 py-3 sm:flex-row sm:items-center">
        <Segmented label="Session state" value={state} onChange={(v) => { setState(v); setPage(0); }} options={[
          { key: "OPEN", label: "Open now" }, { key: "LIVE", label: "Live" }, { key: "OPEN_STALE", label: "No recent updates" }, { key: "ALL", label: "All, incl. ended" },
        ]} />
        <SearchInput value={username} onChange={(v) => { setUsername(v); setPage(0); }} placeholder="Voucher or username" />
        <button onClick={reload} className="text-xs font-medium text-[var(--accent-text)] hover:underline sm:ml-auto">Refresh</button>
      </div>
      {error && <div className="px-4 pt-3"><ErrorNotice text={`Couldn't load sessions: ${error}`} /></div>}
      <Table bare searchable={false} paginated={false} rows={data?.content ?? []} loading={loading && !data} rowKey={(s) => s.id}
        emptyTitle={state === "ALL" ? "No sessions recorded" : "No open sessions"}
        emptyDescription={state === "ALL" ? "This router has no RADIUS accounting records yet." : "Nobody is connected through this router right now."}
        columns={[
          { label: "Customer", render: (s) => <span><span className="block font-mono text-[13px] font-medium text-[var(--fg)]">{s.username}</span><span className="text-[11.5px] text-[var(--fg-muted)]">{s.packageName || "Package unknown"}</span></span> },
          { label: "State", render: (s) => { const st = sessionState(s.state); return <Badge tone={st.tone} dot>{st.label}</Badge>; } },
          { label: "Device", render: (s) => <span className="font-mono text-xs">{s.framedIp || "—"}<br /><span className="text-[var(--fg-faint)]">{s.mac || "—"}</span></span>, hideBelow: "md" },
          { label: "Started", render: (s) => <span className="text-xs" title={date(s.startedAt)}>{relativeTime(s.startedAt, now)}</span> },
          { label: "Duration", render: (s) => duration(s.sessionSeconds), align: "right", hideBelow: "sm" },
          { label: "Download / upload", render: (s) => <span className="text-xs">{bytes(s.downloadOctets ?? 0)} / {bytes(s.uploadOctets ?? 0)}</span>, align: "right", hideBelow: "md" },
          { label: "Time left", render: (s) => s.stoppedAt ? <span className="text-xs text-[var(--fg-faint)]">{s.terminateCause || "Ended"}</span> : <span className="text-xs">{s.remainingSeconds == null ? "—" : duration(s.remainingSeconds)}</span>, align: "right" },
          { label: "Last update", render: (s) => <span className="text-xs text-[var(--fg-muted)]" title={date(s.lastUpdateAt)}>{relativeTime(s.lastUpdateAt, now)}</span>, align: "right", hideBelow: "lg" },
        ]} />
      <Pager page={page} totalPages={data?.totalPages ?? 0} totalElements={data?.totalElements} onPage={setPage} busy={loading} />
      <p className="border-t border-[var(--border)] px-4 py-2.5 text-[11.5px] text-[var(--fg-faint)]">From RADIUS accounting. &ldquo;No recent updates&rdquo; means the session has no Stop record but no interim update for 15 minutes, so it may already be gone.</p>
    </Card>
  );
}

/* ---------------- Accounting ---------------- */
function AccountingTab({ routerId }: { routerId: number }) {
  const [username, setUsername] = useState("");
  const q = useDebounced(username);
  const [cause, setCause] = useState("");
  const [page, setPage] = useState(0);
  const { data, error, loading } = usePaged<SessionRow>(`/api/admin/noc/routers/${routerId}/accounting`, { username: q, cause, page, size: 25 });
  const causes = useMemo(() => [...new Set((data?.content ?? []).map((s) => s.terminateCause).filter((c): c is string => !!c))], [data]);

  return (
    <Card bodyClassName="">
      <div className="flex flex-col gap-3 border-b border-[var(--border)] px-4 py-3 sm:flex-row sm:items-center">
        <SearchInput value={username} onChange={(v) => { setUsername(v); setPage(0); }} placeholder="Username" />
        <select value={cause} onChange={(e) => { setCause(e.target.value); setPage(0); }} className={`${field} !mt-0 !min-h-8 w-full !py-1 text-xs sm:w-56`} aria-label="Termination cause">
          <option value="">All termination causes</option>
          {[...new Set([...causes, ...(cause ? [cause] : [])])].map((c) => <option key={c} value={c}>{c}</option>)}
        </select>
      </div>
      {error && <div className="px-4 pt-3"><ErrorNotice text={`Couldn't load accounting: ${error}`} /></div>}
      <Table bare searchable={false} paginated={false} rows={data?.content ?? []} loading={loading && !data} rowKey={(s) => s.id}
        emptyTitle="No accounting records" emptyDescription="RADIUS has no accounting rows for this router with these filters."
        columns={[
          { label: "Session", render: (s) => <span className="font-mono text-xs"><span className="text-[13px] font-medium text-[var(--fg)]">{s.username}</span><br /><span className="text-[var(--fg-faint)]">{s.acctSessionId}</span></span> },
          { label: "Start → stop", render: (s) => <span className="text-xs">{date(s.startedAt)}<br />→ {s.stoppedAt ? date(s.stoppedAt) : <span className="font-medium text-amber-700">still open</span>}</span> },
          { label: "Duration", render: (s) => duration(s.sessionSeconds), align: "right" },
          { label: "Download", render: (s) => bytes(s.downloadOctets ?? 0), align: "right", hideBelow: "sm" },
          { label: "Upload", render: (s) => bytes(s.uploadOctets ?? 0), align: "right", hideBelow: "sm" },
          { label: "Cause", render: (s) => <span className="text-xs">{s.terminateCause || "—"}</span>, hideBelow: "md" },
          { label: "NAS", render: (s) => <span className="font-mono text-[11px]">{s.nasIp}</span>, hideBelow: "lg" },
        ]} />
      <Pager page={page} totalPages={data?.totalPages ?? 0} totalElements={data?.totalElements} onPage={setPage} busy={loading} />
    </Card>
  );
}

/* ---------------- Auth events ---------------- */
function AuthTab({ routerId }: { routerId: number }) {
  const [reply, setReply] = useState<"" | "Access-Accept" | "Access-Reject">("");
  const [username, setUsername] = useState("");
  const q = useDebounced(username);
  const [page, setPage] = useState(0);
  const { data, error, loading } = usePaged<AuthEvent>(`/api/admin/noc/routers/${routerId}/auth-events`, { reply, username: q, page, size: 25 });

  return (
    <Card bodyClassName="">
      <div className="flex flex-col gap-3 border-b border-[var(--border)] px-4 py-3 sm:flex-row sm:items-center">
        <Segmented label="Result" value={reply} onChange={(v) => { setReply(v); setPage(0); }} options={[
          { key: "", label: "All" }, { key: "Access-Accept", label: "Accepted" }, { key: "Access-Reject", label: "Rejected" },
        ]} />
        <SearchInput value={username} onChange={(v) => { setUsername(v); setPage(0); }} placeholder="Username" />
      </div>
      {error && <div className="px-4 pt-3"><ErrorNotice text={`Couldn't load auth events: ${error}`} /></div>}
      <Table bare searchable={false} paginated={false} rows={data?.content ?? []} loading={loading && !data} rowKey={(e) => e.id}
        emptyTitle="No authentication events" emptyDescription="No login attempts match these filters."
        columns={[
          { label: "Time", render: (e) => <span className="text-xs">{date(e.at)}</span> },
          { label: "User", render: (e) => <span className="font-mono text-[13px]">{e.username}</span> },
          { label: "Result", render: (e) => <Badge tone={e.reply === "Access-Accept" ? "green" : e.reply === "Access-Reject" ? "red" : "slate"} dot>{e.reply === "Access-Accept" ? "Accepted" : e.reply === "Access-Reject" ? "Rejected" : e.reply}</Badge> },
          { label: "Client MAC", render: (e) => <span className="font-mono text-xs">{e.mac || "—"}</span>, hideBelow: "md" },
          { label: "Detail", render: (e) => <span className="text-xs text-[var(--fg-muted)]">{e.description}</span>, hideBelow: "sm" },
        ]} />
      <Pager page={page} totalPages={data?.totalPages ?? 0} totalElements={data?.totalElements} onPage={setPage} busy={loading} />
      <p className="border-t border-[var(--border)] px-4 py-2.5 text-[11.5px] text-[var(--fg-faint)]">FreeRADIUS post-auth log for this tenant&apos;s vouchers. Passwords are never stored or shown.</p>
    </Card>
  );
}

/* ---------------- Devices (hotspot hosts) ---------------- */
function DevicesTab({ routerId, now }: { routerId: number; now: number }) {
  const [data, setData] = useState<{ hosts: Host[]; seenAt: string } | null>(null);
  const [err, setErr] = useState("");
  useEffect(() => {
    api.get<{ hosts: Host[]; seenAt: string }>(`/api/admin/noc/routers/${routerId}/hosts`)
      .then((r) => setData(r.data)).catch((e) => setErr(message(e)));
  }, [routerId]);
  const str = (v: unknown) => (v == null || v === "" ? "—" : String(v));
  return (
    <Card title="Hotspot host list" description={data?.seenAt ? `Pushed by the router ${relativeTime(data.seenAt, now)}` : "Not reported yet"} bodyClassName="">
      {err && <div className="px-4 pt-3"><ErrorNotice text={`Couldn't load hosts: ${err}`} /></div>}
      <Table bare rows={data?.hosts ?? []} loading={!data && !err} searchPlaceholder="Search MAC, IP or user…"
        emptyTitle="No hosts reported" emptyDescription="The router hasn't pushed a hotspot host list yet."
        columns={[
          { label: "MAC", render: (h) => <span className="font-mono text-xs">{str(h["mac-address"] ?? h.mac)}</span> },
          { label: "IP", render: (h) => <span className="font-mono text-xs">{str(h["address"] ?? h.ip)}</span> },
          { label: "User", render: (h) => { const u = h["user"]; return u ? <span className="font-mono text-xs font-medium text-[var(--fg)]">{String(u)}</span> : <span className="text-xs text-[var(--fg-faint)]">Not signed in</span>; } },
          { label: "Uptime", render: (h) => <span className="text-xs">{str(h["uptime"])}</span>, hideBelow: "sm" },
        ]} />
      <p className="border-t border-[var(--border)] px-4 py-2.5 text-[11.5px] text-[var(--fg-faint)]">Every device on the hotspot network appears here; only rows with a user are signed-in customers.</p>
    </Card>
  );
}

/* ---------------- Provisioning ---------------- */
function ProvisioningTab({ routerId, detail }: { routerId: number; detail: Detail }) {
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [err, setErr] = useState("");
  useEffect(() => {
    api.get<Task[]>(`/api/admin/noc/routers/${routerId}/tasks`)
      .then((r) => setTasks(r.data)).catch((e) => setErr(message(e)));
  }, [routerId]);
  const r = detail.router;
  return (
    <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
      <Card title="Provisioning" bodyClassName="px-5 py-1">
        <dl className="divide-y divide-[var(--border-soft)]">
          <Field label="State">{r.provisioningState}</Field>
          <Field label="Tunnel IP" mono>{r.wgIp || "Unassigned"}</Field>
          <Field label="WireGuard key">Registered (hidden)</Field>
          <Field label="Bindings">{r.bindingsSynced ? `Synced ${date(r.bindingsSyncedAt)}` : "Pending sync"}</Field>
          <Field label="Host list push">{date(r.hostsSeenAt)}</Field>
        </dl>
        <p className="pb-4 pt-1 text-[11.5px] leading-relaxed text-[var(--fg-faint)]">The provisioning script version isn&apos;t reported back by the router.</p>
      </Card>
      <Card className="lg:col-span-2" title="Task history" description="Management tasks queued for this router" bodyClassName="">
        {err && <div className="px-4 pt-3"><ErrorNotice text={`Couldn't load task history: ${err}`} /></div>}
        <Table bare searchable={false} paginated={(tasks?.length ?? 0) > 10} rows={tasks ?? []} loading={!tasks && !err} rowKey={(t) => t.id}
          emptyTitle="No tasks" emptyDescription="No management tasks have been queued for this router."
          columns={[
            { label: "Action", render: (t) => <span className="font-mono text-xs font-medium text-[var(--fg)]">{t.action}</span> },
            { label: "Status", render: (t) => <Badge tone={t.status === "APPLIED" ? "green" : t.status === "FAILED" ? "red" : "amber"} dot>{humanize(t.status)}</Badge> },
            { label: "Queued", render: (t) => <span className="text-xs">{date(t.createdAt)}</span> },
            { label: "Applied", render: (t) => <span className="text-xs">{date(t.appliedAt)}</span>, hideBelow: "sm" },
          ]} />
      </Card>
    </div>
  );
}

/* ---------------- Events timeline ---------------- */
function EventsTab({ routerId }: { routerId: number }) {
  const [events, setEvents] = useState<Event[] | null>(null);
  const [filter, setFilter] = useState("ALL");
  const [err, setErr] = useState("");
  useEffect(() => {
    api.get<Event[]>(`/api/admin/noc/routers/${routerId}/events`)
      .then((r) => setEvents(r.data)).catch((e) => setErr(message(e)));
  }, [routerId]);
  const types = useMemo(() => ["ALL", ...new Set((events ?? []).map((e) => e.type))], [events]);
  const rows = (events ?? []).filter((e) => filter === "ALL" || e.type === filter);
  return (
    <Card title="Event timeline" description="Sessions, tasks and incidents for this router, newest first"
      action={types.length > 2 && <Segmented label="Event type" value={filter} onChange={setFilter} options={types.map((t) => ({ key: t, label: t === "ALL" ? "All" : humanize(t) }))} />}>
      {err && <ErrorNotice text={`Couldn't load events: ${err}`} />}
      {!events && !err && <p className="text-sm text-[var(--fg-muted)]">Loading…</p>}
      {events && rows.length === 0 && <p className="py-8 text-center text-sm text-[var(--fg-muted)]">No events recorded yet.</p>}
      <ol className="relative ml-1 space-y-4 border-l border-[var(--border)] pl-5">
        {rows.map((e, i) => (
          <li key={i} className="relative">
            <span className={`absolute -left-[25.5px] top-1.5 h-2.5 w-2.5 rounded-full ring-4 ring-[var(--surface)] ${e.severity === "CRITICAL" ? "bg-[var(--status-bad)]" : e.severity === "WARNING" ? "bg-[var(--status-warn)]" : "bg-[var(--chart-1)]"}`} aria-hidden />
            <div className="flex flex-wrap items-baseline justify-between gap-x-3">
              <span className="text-[13.5px] font-medium text-[var(--fg)]">{e.title}</span>
              <span className="text-xs text-[var(--fg-faint)]">{date(e.at)}</span>
            </div>
            <p className="mt-0.5 text-xs text-[var(--fg-muted)]"><span className="font-medium">{humanize(e.type)}</span>{e.detail ? ` · ${e.detail}` : ""}</p>
          </li>
        ))}
      </ol>
    </Card>
  );
}

/* ---------------- Diagnostics ---------------- */
function DiagnosticsTab({ routerId }: { routerId: number }) {
  const [diag, setDiag] = useState<{ checks: Check[] } | null>(null);
  const [err, setErr] = useState("");
  useEffect(() => {
    api.get<{ checks: Check[] }>(`/api/admin/noc/routers/${routerId}/diagnostics`)
      .then((r) => setDiag(r.data)).catch((e) => setErr(message(e)));
  }, [routerId]);
  return (
    <Card title="Diagnostics" description="Live checks query the router now (read-only); inferred checks come from stored telemetry and RADIUS data. No end-to-end customer internet test is claimed.">
      {err && <ErrorNotice text={`Couldn't run diagnostics: ${err}`} />}
      {!diag && !err && <p className="text-sm text-[var(--fg-muted)]">Running checks…</p>}
      <div className="grid gap-3 lg:grid-cols-2">
        {(diag?.checks ?? []).map((c) => (
          <div key={c.key} className="rounded-lg border border-[var(--border)] p-4">
            <div className="flex items-start justify-between gap-3">
              <span className="text-[13.5px] font-medium text-[var(--fg)]">{c.label}</span>
              <span className="flex shrink-0 items-center gap-1.5">
                <span className="text-[11px] font-medium text-[var(--fg-faint)]">{c.source === "LIVE" ? "Live" : "Inferred"}</span>
                <Badge tone={checkTone(c.status)} dot>{humanize(c.status)}</Badge>
              </span>
            </div>
            {c.detail && <p className="mt-1.5 text-xs text-[var(--fg-muted)]">{c.detail}</p>}
            {c.recommendation && <p className="mt-2.5 rounded-md bg-amber-50 px-2.5 py-1.5 text-[11.5px] text-amber-800">{c.recommendation}</p>}
          </div>
        ))}
      </div>
    </Card>
  );
}
