"use client";

import { useParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import api from "@/lib/api";
import {
  Activity, ArrowClockwise, CellTower as RouterIcon, ClockCounterClockwise,
  Devices, ShieldCheck, ShieldWarning, Warning, WifiHigh, MagnifyingGlass,
} from "@/components/icons";
import {
  Badge, ErrorNotice, StatCard, Table, date, bytes, field, panel, buttonGhost,
} from "@/components/BillingUI";

interface RouterRow {
  id: number; tenantId: number; tenantName: string; name: string; status: string;
  wgIp: string | null; cpuLoad: number | null; memoryUsage: number | null;
  uptime: string | null; activeUsers: number | null;
  hotspotReady: boolean | null; radiusReady: boolean | null; portalReady: boolean | null;
  bindingsSynced: boolean; provisioningState: string;
  lastSeen: string | null; bindingsSyncedAt: string | null; hostsSeenAt: string | null;
  openSessions: number; openIncidents: number; createdAt: string;
}
interface Detail { router: RouterRow; radiusNasRegistered: string; lastAccountingAt: string | null; }
interface SessionRow {
  id: number; username: string; acctSessionId: string; framedIp: string | null;
  mac: string | null; nasIp: string | null; startedAt: string | null;
  lastUpdateAt: string | null; stoppedAt: string | null; sessionSeconds: number | null;
  uploadOctets: number | null; downloadOctets: number | null; terminateCause: string | null;
  state: string; packageId: number | null; packageName: string | null; remainingSeconds: number | null;
}
interface Paged<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number; }
interface AuthEvent { id: number; at: string; username: string; reply: string; mac: string | null; severity: string; description: string; }
interface Task { id: number; action: string; status: string; createdAt: string; appliedAt: string | null; }
interface Event { at: string; type: string; severity: string; title: string; detail: string | null; }
interface Incident { id: number; type: string; severity: string; title: string; detail: string | null; openedAt: string; resolvedAt: string | null; }
interface Check { key: string; label: string; status: string; source: string; detail: string | null; recommendation: string | null; }
interface UsagePoint { day: string; uploadBytes: number; downloadBytes: number; users: number; }
interface Live { reachable: boolean; source: string; probedAt: string; uptime?: string; cpuLoad?: number; memoryUsagePct?: number; boardName?: string; routerOsVersion?: string; hotspotActive?: number | null; wgHandshakeSecondsAgo?: number | null; error?: string | null; }
interface Host { [k: string]: unknown }

const TABS = ["Overview", "Sessions", "Accounting", "Auth events", "Devices", "Provisioning", "Events", "Diagnostics"] as const;
type Tab = typeof TABS[number];

const statusTone = (s: string) =>
  s?.toLowerCase() === "online" ? "green" : s?.toLowerCase() === "degraded" ? "amber" :
  s?.toLowerCase() === "offline" ? "red" : "slate";
const checkTone = (s: string) =>
  s === "HEALTHY" ? "green" : s === "WARNING" ? "amber" : s === "CRITICAL" ? "red" : "slate";
const sessTone = (s: string) => s === "LIVE" ? "green" : s === "OPEN_STALE" ? "amber" : "slate";
const flag = (v: boolean | null) => v === true ? <Badge tone="green">Ready</Badge> : v === false ? <Badge tone="red">Not ready</Badge> : <Badge tone="slate">Unknown</Badge>;
const dur = (s: number | null | undefined) => {
  if (s == null) return "—";
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
  return h > 0 ? `${h}h ${m}m` : m > 0 ? `${m}m ${s % 60}s` : `${s}s`;
};

function Pager({ page, totalPages, onPage }: { page: number; totalPages: number; onPage: (p: number) => void }) {
  if (totalPages <= 1) return null;
  return <div className="flex items-center justify-end gap-2 pt-2 text-xs text-[var(--fg-muted)]">
    <button disabled={page <= 0} onClick={() => onPage(page - 1)} className={buttonGhost + " !min-h-8 !px-3"}>Prev</button>
    <span>{page + 1} / {totalPages}</span>
    <button disabled={page >= totalPages - 1} onClick={() => onPage(page + 1)} className={buttonGhost + " !min-h-8 !px-3"}>Next</button>
  </div>;
}

export default function RouterDetailClient() {
  return <Suspense><RouterDetailInner /></Suspense>;
}

function RouterDetailInner() {
  const { id } = useParams<{ id: string }>();
  const routerId = Number(id);
  const [tab, setTab] = useState<Tab>("Overview");
  const [detail, setDetail] = useState<Detail | null>(null);
  const [error, setError] = useState("");
  const [live, setLive] = useState<Live | null>(null);
  const [liveBusy, setLiveBusy] = useState(false);

  const load = useCallback(() => {
    api.get<Detail>(`/api/admin/noc/routers/${routerId}`).then((r) => setDetail(r.data))
      .catch(() => setError("Could not load this router."));
  }, [routerId]);
  useEffect(load, [load]);

  const runProbe = () => {
    setLiveBusy(true);
    api.get<Live>(`/api/admin/noc/routers/${routerId}/live`)
      .then((r) => setLive(r.data)).catch(() => setLive({ reachable: false, source: "UNAVAILABLE", probedAt: "", error: "Probe request failed" }))
      .finally(() => setLiveBusy(false));
  };

  const r = detail?.router;
  return <div className="mx-auto max-w-7xl space-y-6">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div>
        <a href="/admin/network" className="text-xs font-semibold text-blue-600 hover:underline">← Network operations</a>
        <h1 className="mt-1 flex items-center gap-2 text-xl font-bold text-[var(--fg)]">
          <RouterIcon size={22} /> {r?.name ?? "Router"}
          {r && <Badge tone={statusTone(r.status)}>{r.status}</Badge>}
        </h1>
        <p className="mt-0.5 text-xs text-[var(--fg-muted)]">
          {r?.tenantName} · tunnel {r?.wgIp || "unassigned"} · {r?.provisioningState}
        </p>
      </div>
      <button onClick={runProbe} disabled={liveBusy} className={buttonGhost}>
        <ArrowClockwise size={14} className={liveBusy ? "animate-spin" : ""} />
        {liveBusy ? "Probing…" : "Run live probe"}
      </button>
    </div>
    <ErrorNotice text={error} />

    {live && (
      <div className={`${panel} flex flex-wrap items-center gap-x-6 gap-y-2 p-4 text-xs`}>
        <Badge tone={live.reachable ? "green" : "red"}>{live.reachable ? "REST reachable" : "REST unreachable"}</Badge>
        {live.routerOsVersion && <span>RouterOS {live.routerOsVersion}</span>}
        {live.boardName && <span>{live.boardName}</span>}
        {live.cpuLoad != null && <span>CPU {live.cpuLoad}%</span>}
        {live.memoryUsagePct != null && <span>RAM {live.memoryUsagePct}%</span>}
        {live.hotspotActive != null && <span>{live.hotspotActive} hotspot user(s)</span>}
        {live.wgHandshakeSecondsAgo != null && <span>WG handshake {live.wgHandshakeSecondsAgo}s ago</span>}
        {live.error && <span className="text-[var(--fg-muted)]">{live.error}</span>}
        <span className="ml-auto text-[var(--fg-faint)]">probed {date(live.probedAt)}</span>
      </div>
    )}

    <nav className="flex gap-1 overflow-x-auto border-b border-[var(--border)] pb-px" aria-label="Router sections">
      {TABS.map((t) => (
        <button key={t} onClick={() => setTab(t)}
          className={`whitespace-nowrap rounded-t-lg px-3.5 py-2 text-xs font-semibold transition ${tab === t ? "bg-[var(--surface)] text-blue-600 shadow-[inset_0_-2px_0_0_currentColor]" : "text-[var(--fg-muted)] hover:text-[var(--fg)]"}`}>
          {t}
        </button>
      ))}
    </nav>

    {tab === "Overview" && r && <OverviewTab detail={detail!} />}
    {tab === "Sessions" && <SessionsTab routerId={routerId} />}
    {tab === "Accounting" && <AccountingTab routerId={routerId} />}
    {tab === "Auth events" && <AuthTab routerId={routerId} />}
    {tab === "Devices" && <DevicesTab routerId={routerId} />}
    {tab === "Provisioning" && <ProvisioningTab routerId={routerId} detail={detail!} />}
    {tab === "Events" && <EventsTab routerId={routerId} />}
    {tab === "Diagnostics" && <DiagnosticsTab routerId={routerId} />}
  </div>;
}

/* ---------------- Overview ---------------- */
function OverviewTab({ detail }: { detail: Detail }) {
  const r = detail.router;
  return <div className="space-y-6">
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      <StatCard label="Status" value={r.status || "unknown"} icon={<WifiHigh size={20} />} tone={statusTone(r.status) === "green" ? "green" : "default"} hintText={`Last seen ${date(r.lastSeen)}`} />
      <StatCard label="Active users" value={r.activeUsers ?? "—"} icon={<Devices size={20} />} hintText={`${r.openSessions} open accounting session(s)`} />
      <StatCard label="CPU / RAM" value={`${r.cpuLoad ?? "—"}% / ${r.memoryUsage ?? "—"}%`} icon={<Activity size={20} />} tone={(r.cpuLoad ?? 0) >= 90 || (r.memoryUsage ?? 0) >= 90 ? "amber" : "default"} hintText={`Uptime ${r.uptime || "unknown"}`} />
      <StatCard label="Open incidents" value={r.openIncidents} icon={r.openIncidents > 0 ? <ShieldWarning size={20} /> : <ShieldCheck size={20} />} tone={r.openIncidents > 0 ? "amber" : "green"} hintText="Evaluated every minute" />
    </div>

    <div className="grid gap-4 lg:grid-cols-2">
      <section className={`${panel} p-5`}>
        <h3 className="mb-3 text-sm font-semibold">Service readiness</h3>
        <dl className="space-y-2.5 text-sm">
          {[
            ["Router reachable (telemetry)", r.status?.toLowerCase() === "online"],
            ["Hotspot service", r.hotspotReady],
            ["RADIUS service", r.radiusReady],
            ["Captive portal files", r.portalReady],
          ].map(([label, v]) => (
            <div key={label as string} className="flex items-center justify-between">
              <dt className="text-[var(--fg-muted)]">{label}</dt><dd>{flag(v as boolean | null)}</dd>
            </div>
          ))}
          <div className="flex items-center justify-between">
            <dt className="text-[var(--fg-muted)]">FreeRADIUS NAS registration</dt>
            <dd><Badge tone={detail.radiusNasRegistered === "Registered" ? "green" : "amber"}>{detail.radiusNasRegistered}</Badge></dd>
          </div>
          <div className="flex items-center justify-between">
            <dt className="text-[var(--fg-muted)]">Last accounting update</dt>
            <dd className="text-xs">{date(detail.lastAccountingAt)}</dd>
          </div>
        </dl>
      </section>
      <section className={`${panel} p-5`}>
        <h3 className="mb-3 text-sm font-semibold">Identity & provisioning</h3>
        <dl className="space-y-2.5 text-sm">
          {[
            ["Router ID", `#${r.id}`],
            ["WireGuard tunnel IP", r.wgIp || "Unassigned"],
            ["Provisioning state", r.provisioningState],
            ["Bindings sync", r.bindingsSynced ? "Synced" : "Pending"],
            ["Last bindings sync", date(r.bindingsSyncedAt)],
            ["Host list last pushed", date(r.hostsSeenAt)],
            ["Registered", date(r.createdAt)],
          ].map(([k, v]) => (
            <div key={k as string} className="flex items-center justify-between">
              <dt className="text-[var(--fg-muted)]">{k}</dt><dd className="font-medium">{v}</dd>
            </div>
          ))}
        </dl>
        <p className="mt-3 text-[11px] leading-4 text-[var(--fg-faint)]">
          Board model, RouterOS version and serial are not part of the telemetry payload; use
          “Run live probe” for an actual router query. Provisioning tokens and RADIUS secrets are never shown.
        </p>
      </section>
    </div>
  </div>;
}

/* ---------------- Sessions ---------------- */
function SessionsTab({ routerId }: { routerId: number }) {
  const [rows, setRows] = useState<Paged<SessionRow> | null>(null);
  const [err, setErr] = useState("");
  const [username, setUsername] = useState("");
  const [state, setState] = useState("OPEN");
  const [page, setPage] = useState(0);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    setBusy(true);
    api.get<Paged<SessionRow>>(`/api/admin/noc/routers/${routerId}/sessions`, {
      params: { openOnly: state === "OPEN" || state === "LIVE" || state === "OPEN_STALE",
                state: state === "OPEN" ? "" : state, username, page, size: 25 },
    }).then((r) => setRows(r.data)).catch(() => setErr("Could not load sessions."))
      .finally(() => setBusy(false));
  }, [routerId, state, username, page]);
  useEffect(load, [load]);

  return <section className={`${panel} p-5 space-y-3`}>
    <div className="flex flex-wrap items-center gap-3">
      <div className="relative">
        <MagnifyingGlass size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-[var(--fg-muted)]" />
        <input value={username} onChange={(e) => { setUsername(e.target.value); setPage(0); }}
          placeholder="Filter voucher / username" className={`${field} !mt-0 w-52 pl-8`} />
      </div>
      <select value={state} onChange={(e) => { setState(e.target.value); setPage(0); }} className={`${field} !mt-0 w-44`} aria-label="Session state">
        <option value="OPEN">Open (live + stale)</option>
        <option value="LIVE">Live only</option>
        <option value="OPEN_STALE">Stale open only</option>
        <option value="ALL">All sessions</option>
      </select>
      <button onClick={load} className={buttonGhost + " !min-h-9"}><ArrowClockwise size={13} className={busy ? "animate-spin" : ""} /> Refresh</button>
      <span className="ml-auto text-[11px] text-[var(--fg-faint)]">RADIUS accounting · stale = no interim update for 15 min</span>
    </div>
    <ErrorNotice text={err} />
    <Table rows={rows?.content ?? []} loading={!rows}
      emptyTitle="No sessions" emptyDescription="No RADIUS accounting records match for this router."
      columns={[
        { label: "User", render: (s) => <span><span className="block font-mono font-semibold">{s.username}</span><span className="text-[11px] text-[var(--fg-faint)]">{s.packageName || "No package"}</span></span> },
        { label: "State", render: (s) => <Badge tone={sessTone(s.state)}>{s.state === "OPEN_STALE" ? "Open·stale" : s.state}</Badge> },
        { label: "IP / MAC", render: (s) => <span className="font-mono text-xs">{s.framedIp || "—"}<br /><span className="text-[var(--fg-faint)]">{s.mac || "—"}</span></span> },
        { label: "Started", render: (s) => date(s.startedAt) },
        { label: "Duration", render: (s) => dur(s.sessionSeconds) },
        { label: "↓ / ↑", render: (s) => <span className="text-xs">{bytes(s.downloadOctets ?? 0)} / {bytes(s.uploadOctets ?? 0)}</span> },
        { label: "Remaining", render: (s) => <span className="text-xs">{s.remainingSeconds == null ? "—" : dur(s.remainingSeconds)}</span> },
        { label: "Last update", render: (s) => date(s.lastUpdateAt) },
        { label: "Ended", render: (s) => <span className="text-xs">{s.stoppedAt ? <>{date(s.stoppedAt)}<br /><span className="text-[var(--fg-faint)]">{s.terminateCause || ""}</span></> : "—"}</span> },
      ]} />
    <Pager page={page} totalPages={rows?.totalPages ?? 0} onPage={setPage} />
  </section>;
}

/* ---------------- Accounting ---------------- */
function AccountingTab({ routerId }: { routerId: number }) {
  const [rows, setRows] = useState<Paged<SessionRow> | null>(null);
  const [usage, setUsage] = useState<UsagePoint[]>([]);
  const [err, setErr] = useState("");
  const [username, setUsername] = useState("");
  const [cause, setCause] = useState("");
  const [page, setPage] = useState(0);

  const load = useCallback(() => {
    api.get<Paged<SessionRow>>(`/api/admin/noc/routers/${routerId}/accounting`, {
      params: { username, cause, page, size: 25 },
    }).then((r) => setRows(r.data)).catch(() => setErr("Could not load accounting."));
    api.get<UsagePoint[]>(`/api/admin/noc/routers/${routerId}/usage`, { params: { days: 14 } })
      .then((r) => setUsage(r.data)).catch(() => {});
  }, [routerId, username, cause, page]);
  useEffect(load, [load]);

  const causes = useMemo(() => [...new Set((rows?.content ?? []).map((s) => s.terminateCause).filter(Boolean))], [rows]);
  const maxBytes = Math.max(1, ...usage.map((u) => u.uploadBytes + u.downloadBytes));

  return <section className="space-y-4">
    {usage.length > 0 && (
      <div className={`${panel} p-5`}>
        <h3 className="mb-3 text-sm font-semibold">Daily usage (last {usage.length} day(s) with data)</h3>
        <div className="flex h-28 items-end gap-1.5" role="img" aria-label="Daily data usage chart">
          {usage.map((u) => (
            <div key={u.day} className="group flex flex-1 flex-col items-center gap-1">
              <div className="w-full rounded-t bg-blue-500/80 transition group-hover:bg-blue-400"
                style={{ height: `${Math.max(2, ((u.uploadBytes + u.downloadBytes) / maxBytes) * 100)}%` }}
                title={`${u.day.slice(0, 10)} — ${bytes(u.uploadBytes + u.downloadBytes)} · ${u.users} user(s)`} />
              <span className="text-[9px] text-[var(--fg-faint)]">{u.day.slice(5, 10)}</span>
            </div>
          ))}
        </div>
      </div>
    )}
    <div className={`${panel} space-y-3 p-5`}>
      <div className="flex flex-wrap items-center gap-3">
        <input value={username} onChange={(e) => { setUsername(e.target.value); setPage(0); }}
          placeholder="Filter username" className={`${field} !mt-0 w-52`} />
        <select value={cause} onChange={(e) => { setCause(e.target.value); setPage(0); }} className={`${field} !mt-0 w-56`} aria-label="Termination cause">
          <option value="">All termination causes</option>
          {causes.map((c) => <option key={c} value={c!}>{c}</option>)}
        </select>
      </div>
      <ErrorNotice text={err} />
      <Table rows={rows?.content ?? []} loading={!rows}
        emptyTitle="No accounting records" emptyDescription="RADIUS has no accounting rows for this router in the selected range."
        columns={[
          { label: "Session", render: (s) => <span className="font-mono text-xs">{s.username}<br /><span className="text-[var(--fg-faint)]">{s.acctSessionId}</span></span> },
          { label: "Start → Stop", render: (s) => <span className="text-xs">{date(s.startedAt)}<br />→ {s.stoppedAt ? date(s.stoppedAt) : <em className="text-amber-500">open</em>}</span> },
          { label: "Duration", render: (s) => dur(s.sessionSeconds) },
          { label: "Download", render: (s) => bytes(s.downloadOctets ?? 0) },
          { label: "Upload", render: (s) => bytes(s.uploadOctets ?? 0) },
          { label: "Cause", render: (s) => <span className="text-xs">{s.terminateCause || "—"}</span> },
          { label: "NAS", render: (s) => <span className="font-mono text-[11px]">{s.nasIp}</span> },
        ]} />
      <Pager page={page} totalPages={rows?.totalPages ?? 0} onPage={setPage} />
    </div>
  </section>;
}

/* ---------------- Auth events ---------------- */
function AuthTab({ routerId }: { routerId: number }) {
  const [rows, setRows] = useState<Paged<AuthEvent> | null>(null);
  const [reply, setReply] = useState("");
  const [username, setUsername] = useState("");
  const [page, setPage] = useState(0);
  const [err, setErr] = useState("");

  const load = useCallback(() => {
    api.get<Paged<AuthEvent>>(`/api/admin/noc/routers/${routerId}/auth-events`, {
      params: { reply, username, page, size: 25 },
    }).then((r) => setRows(r.data)).catch(() => setErr("Could not load auth events."));
  }, [routerId, reply, username, page]);
  useEffect(load, [load]);

  return <section className={`${panel} space-y-3 p-5`}>
    <div className="flex flex-wrap items-center gap-3">
      <input value={username} onChange={(e) => { setUsername(e.target.value); setPage(0); }}
        placeholder="Filter username" className={`${field} !mt-0 w-52`} />
      <select value={reply} onChange={(e) => { setReply(e.target.value); setPage(0); }} className={`${field} !mt-0 w-48`} aria-label="Reply type">
        <option value="">All events</option>
        <option value="Access-Accept">Accepted</option>
        <option value="Access-Reject">Rejected</option>
      </select>
      <span className="ml-auto text-[11px] text-[var(--fg-faint)]">FreeRADIUS radpostauth · credentials are never displayed</span>
    </div>
    <ErrorNotice text={err} />
    <Table rows={rows?.content ?? []} loading={!rows}
      emptyTitle="No auth events" emptyDescription="No authentication records for this tenant's vouchers."
      columns={[
        { label: "Time", render: (e) => date(e.at) },
        { label: "User", render: (e) => <span className="font-mono text-xs">{e.username}</span> },
        { label: "Result", render: (e) => <Badge tone={e.reply === "Access-Accept" ? "green" : e.reply === "Access-Reject" ? "red" : "slate"}>{e.reply}</Badge> },
        { label: "Client MAC", render: (e) => <span className="font-mono text-xs">{e.mac || "—"}</span> },
        { label: "Detail", render: (e) => <span className="text-xs text-[var(--fg-muted)]">{e.description}</span> },
      ]} />
    <Pager page={page} totalPages={rows?.totalPages ?? 0} onPage={setPage} />
  </section>;
}

/* ---------------- Devices (hotspot hosts) ---------------- */
function DevicesTab({ routerId }: { routerId: number }) {
  const [data, setData] = useState<{ hosts: Host[]; seenAt: string } | null>(null);
  const [err, setErr] = useState("");
  useEffect(() => {
    api.get<{ hosts: Host[]; seenAt: string }>(`/api/admin/noc/routers/${routerId}/hosts`)
      .then((r) => setData(r.data)).catch(() => setErr("Could not load hotspot hosts."));
  }, [routerId]);
  const hosts = data?.hosts ?? [];
  return <section className={`${panel} space-y-3 p-5`}>
    <div className="flex items-center justify-between">
      <h3 className="text-sm font-semibold">Hotspot host list</h3>
      <span className="text-[11px] text-[var(--fg-faint)]">pushed by router {data?.seenAt ? `at ${date(data.seenAt)}` : "— not yet reported"}</span>
    </div>
    <ErrorNotice text={err} />
    <Table rows={hosts} loading={!data}
      emptyTitle="No hosts reported" emptyDescription="The router has not pushed a hotspot host list yet."
      columns={[
        { label: "MAC", render: (h) => <span className="font-mono text-xs">{String(h["mac-address"] ?? h.mac ?? "—")}</span> },
        { label: "IP", render: (h) => <span className="font-mono text-xs">{String(h["address"] ?? h.ip ?? "—")}</span> },
        { label: "User / authenticated", render: (h) => <span className="text-xs">{String(h["user"] ?? h["authorized"] ?? "—")}</span> },
        { label: "Uptime / idle", render: (h) => <span className="text-xs">{String(h["uptime"] ?? "—")}</span> },
      ]} />
    <p className="text-[11px] text-[var(--fg-faint)]">A device appearing here is present on the hotspot network; only rows with a user are authenticated customers.</p>
  </section>;
}

/* ---------------- Provisioning ---------------- */
function ProvisioningTab({ routerId, detail }: { routerId: number; detail: Detail }) {
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [err, setErr] = useState("");
  useEffect(() => {
    api.get<Task[]>(`/api/admin/noc/routers/${routerId}/tasks`)
      .then((r) => setTasks(r.data)).catch(() => setErr("Could not load task history."));
  }, [routerId]);
  const r = detail.router;
  return <section className="space-y-4">
    <div className={`${panel} p-5`}>
      <h3 className="mb-3 text-sm font-semibold">Provisioning status</h3>
      <dl className="grid gap-2.5 text-sm sm:grid-cols-2">
        {[
          ["State", r.provisioningState],
          ["Tunnel IP", r.wgIp || "Unassigned"],
          ["WG public key", "Registered (hidden)"],
          ["Bindings", r.bindingsSynced ? `Synced ${date(r.bindingsSyncedAt)}` : "Pending sync"],
          ["Portal/host push", date(r.hostsSeenAt)],
        ].map(([k, v]) => (
          <div key={k}><dt className="text-xs text-[var(--fg-muted)]">{k}</dt><dd className="mt-0.5 font-medium">{v}</dd></div>
        ))}
      </dl>
      <p className="mt-3 text-[11px] text-[var(--fg-faint)]">Provisioning version is embedded in the generated script, not reported back by the router; tokens and secrets are never displayed.</p>
    </div>
    <div className={`${panel} p-5`}>
      <h3 className="mb-3 text-sm font-semibold">Task history</h3>
      <ErrorNotice text={err} />
      <Table rows={tasks ?? []} loading={!tasks}
        emptyTitle="No tasks" emptyDescription="No management tasks recorded for this router."
        columns={[
          { label: "Action", render: (t) => <span className="font-medium">{t.action}</span> },
          { label: "Status", render: (t) => <Badge tone={t.status === "APPLIED" ? "green" : t.status === "FAILED" ? "red" : "amber"}>{t.status}</Badge> },
          { label: "Queued", render: (t) => date(t.createdAt) },
          { label: "Applied", render: (t) => date(t.appliedAt) },
        ]} />
    </div>
  </section>;
}

/* ---------------- Events timeline ---------------- */
function EventsTab({ routerId }: { routerId: number }) {
  const [events, setEvents] = useState<Event[] | null>(null);
  const [filter, setFilter] = useState("ALL");
  const [err, setErr] = useState("");
  useEffect(() => {
    api.get<Event[]>(`/api/admin/noc/routers/${routerId}/events`)
      .then((r) => setEvents(r.data)).catch(() => setErr("Could not load events."));
  }, [routerId]);
  const types = useMemo(() => ["ALL", ...new Set((events ?? []).map((e) => e.type))], [events]);
  const rows = (events ?? []).filter((e) => filter === "ALL" || e.type === filter);
  return <section className={`${panel} p-5`}>
    <div className="mb-4 flex flex-wrap items-center gap-2">
      <ClockCounterClockwise size={16} className="text-[var(--fg-muted)]" />
      <h3 className="text-sm font-semibold">Event timeline</h3>
      <div className="ml-auto flex gap-1.5">
        {types.map((t) => (
          <button key={t} onClick={() => setFilter(t)}
            className={`rounded-full px-2.5 py-1 text-[11px] font-semibold ${filter === t ? "bg-blue-600 text-white" : "bg-[var(--subtle)] text-[var(--fg-muted)]"}`}>{t}</button>
        ))}
      </div>
    </div>
    <ErrorNotice text={err} />
    {!events && <p className="text-sm text-[var(--fg-muted)]">Loading…</p>}
    {events && rows.length === 0 && <p className="text-sm text-[var(--fg-muted)]">No events recorded yet.</p>}
    <ol className="relative space-y-3 border-l border-[var(--border)] pl-5">
      {rows.map((e, i) => (
        <li key={i} className="relative">
          <span className={`absolute -left-[26px] top-1 h-2.5 w-2.5 rounded-full ${e.severity === "CRITICAL" ? "bg-red-500" : e.severity === "WARNING" ? "bg-amber-500" : "bg-blue-500"}`} />
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-medium text-[var(--fg)]">{e.title}</span>
            <Badge tone={e.severity === "CRITICAL" ? "red" : e.severity === "WARNING" ? "amber" : "slate"}>{e.type}</Badge>
          </div>
          {e.detail && <p className="mt-0.5 text-xs text-[var(--fg-muted)]">{e.detail}</p>}
          <p className="mt-0.5 text-[11px] text-[var(--fg-faint)]">{date(e.at)}</p>
        </li>
      ))}
    </ol>
  </section>;
}

/* ---------------- Diagnostics ---------------- */
function DiagnosticsTab({ routerId }: { routerId: number }) {
  const [diag, setDiag] = useState<{ checks: Check[] } | null>(null);
  const [err, setErr] = useState("");
  useEffect(() => {
    api.get<{ checks: Check[] }>(`/api/admin/noc/routers/${routerId}/diagnostics`)
      .then((r) => setDiag(r.data)).catch(() => setErr("Could not run diagnostics."));
  }, [routerId]);
  return <section className={`${panel} p-5`}>
    <h3 className="mb-1 text-sm font-semibold">Consolidated diagnostics</h3>
    <p className="mb-4 text-[11px] text-[var(--fg-faint)]">LIVE checks query the router now over the WireGuard tunnel; INFERRED checks are derived from stored telemetry and RADIUS data. No end-to-end customer-internet test is claimed.</p>
    <ErrorNotice text={err} />
    {!diag && <p className="text-sm text-[var(--fg-muted)]">Running checks…</p>}
    <div className="grid gap-3 lg:grid-cols-2">
      {(diag?.checks ?? []).map((c) => (
        <div key={c.key} className="rounded-lg border border-[var(--border)] p-4">
          <div className="flex items-center justify-between gap-2">
            <span className="text-sm font-semibold">{c.label}</span>
            <span className="flex items-center gap-1.5">
              <Badge tone="slate">{c.source}</Badge>
              <Badge tone={checkTone(c.status)}>{c.status}</Badge>
            </span>
          </div>
          {c.detail && <p className="mt-1.5 text-xs text-[var(--fg-muted)]">{c.detail}</p>}
          {c.recommendation && (
            <p className="mt-2 rounded bg-amber-500/10 px-2.5 py-1.5 text-[11px] text-amber-600 dark:text-amber-400">
              → {c.recommendation}
            </p>
          )}
        </div>
      ))}
    </div>
  </section>;
}
