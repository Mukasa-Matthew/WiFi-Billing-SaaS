"use client";

import React, { useState } from "react";
import api from "@/lib/api";
import { CheckCircle, GraduationCap, Ticket, Users, WarningCircle, XCircle } from "@/components/icons";
import {
  Badge, Card, ErrorNotice, PageContainer, PageHeader, Segmented, StatCard, Table,
  button, buttonGhost, bytes, field, hint, humanize, label, message, relativeTime, useApi, useNow,
} from "@/components/BillingUI";
import { DonutChart } from "@/components/charts";
import { useToast } from "@/components/ui/Toast";

interface Settings {
  staffEnabled?: boolean;
  staffRateLimit: string | null;
  staffDailyDataCapMb?: number | null;
  staffSiteMode?: "ALL" | "ALLOW_LIST" | "BLOCK_LIST";
  staffSiteRules?: string | null;
  studentFreeEnabled?: boolean;
  studentDailyFreeMb?: number | null;
  studentFreeRateLimit?: string | null;
  studentAllowedSites?: string | null;
  visitorsEnabled: boolean;
  visitorDailyCap: number;
  visitorRateLimit: string | null;
  visitorDataCapMb?: number | null;
  tetheringProtection?: "OFF" | "LOG" | "BLOCK";
}
interface SchoolSummary {
  name: string;
  active: boolean;
  students: number;
  staff: number;
  activePeople: number;
  visitorPassesToday: number;
  pendingStaffExceptions: number;
}
interface Overview { settings: Settings; school: SchoolSummary; dashboardUrl: string }
interface StaffException {
  id: string; fullName: string; identifiers: string; reason: string;
  requestedBy: string; requestedAt: string; status: string; decidedAt: string | null; decidedBy: string | null;
}
interface VisitorPass {
  id: string; fullName: string; phone: string; reason: string;
  issuedBy: string; issuedAt: string; expiresAt: string; status: string; deviceMac: string | null;
}
interface ActiveSession {
  radAcctId: number; username: string; framedIpAddress: string | null; callingStationId: string | null;
  nasIpAddress: string | null; acctSessionTime: number | null; acctInputOctets?: number | null;
  acctOutputOctets?: number | null; totalOctets: number | null; category?: string | null;
  categoryLabel?: string | null; packageName?: string | null; customerName?: string | null; rateLimit?: string | null;
}
interface CategoryDataItem {
  category: string; label: string; free: boolean; downBytes: number; upBytes: number; totalBytes: number; users: number;
}
interface DataUsage {
  period: string; downBytes: number; upBytes: number; users: number; previousBytes: number; categories?: CategoryDataItem[];
}
interface Page<T> { content: T[]; totalElements: number }

const PASS_TONE: Record<string, "green" | "blue" | "amber" | "slate"> = {
  IN_USE: "green", UNUSED: "blue", EXPIRED: "slate", REVOKED: "amber",
};
const CAT_TONE: Record<string, "green" | "blue" | "amber" | "slate"> = {
  STUDENT: "green", STAFF: "blue", VISITOR: "amber", PAID_CUSTOMER: "slate",
};
const CAT_COLOR: Record<string, string> = {
  STUDENT: "var(--status-good)",
  STAFF: "var(--chart-1)",
  VISITOR: "var(--status-warn)",
  PAID_CUSTOMER: "#8B5CF6",
};
const PERIOD_OPTIONS = [
  { key: "today", label: "Today" },
  { key: "week", label: "This week" },
  { key: "month", label: "This month" },
] as const;
type SchoolUsagePeriod = (typeof PERIOD_OPTIONS)[number]["key"];

const SPEED_PRESETS = [
  { label: "Unlimited", value: "" },
  { label: "1 Mbps", value: "1M/1M" },
  { label: "2 Mbps", value: "2M/2M" },
  { label: "5 Mbps", value: "5M/5M" },
  { label: "10 Mbps", value: "10M/10M" },
];

const STAFF_CAP_PRESETS = [
  { label: "Unlimited", value: "" },
  { label: "250 MB", value: "250" },
  { label: "500 MB", value: "500" },
  { label: "1 GB", value: "1024" },
  { label: "2 GB", value: "2048" },
  { label: "5 GB", value: "5120" },
];

const VISITOR_CAP_PRESETS = [
  { label: "Unlimited", value: "" },
  { label: "100 MB", value: "100" },
  { label: "250 MB", value: "250" },
  { label: "500 MB", value: "500" },
  { label: "1 GB", value: "1024" },
  { label: "2 GB", value: "2048" },
];

const STUDENT_FREE_MB_PRESETS = [
  { label: "20 MB", value: "20" },
  { label: "50 MB", value: "50" },
  { label: "100 MB", value: "100" },
  { label: "200 MB", value: "200" },
  { label: "500 MB", value: "500" },
  { label: "1 GB", value: "1024" },
];

const STUDY_SITE_PRESETS = [
  { token: "@wikipedia", label: "Wikipedia" },
  { token: "@scholar", label: "Google Scholar" },
  { token: "@khanacademy", label: "Khan Academy" },
  { token: "@chatgpt", label: "ChatGPT" },
];

const STAFF_APP_PRESETS = [
  { token: "@whatsapp", label: "WhatsApp" },
  { token: "@youtube", label: "YouTube" },
  { token: "@chatgpt", label: "ChatGPT" },
  { token: "@tiktok", label: "TikTok" },
  { token: "@facebook", label: "Facebook" },
  { token: "@instagram", label: "Instagram" },
  { token: "@twitter", label: "X / Twitter" },
  { token: "@netflix", label: "Netflix" },
];

function parseSiteTokens(raw: string): string[] {
  return raw
    .split(/[\s,;]+/)
    .map((t) => t.trim().toLowerCase())
    .filter(Boolean);
}

function toggleSiteToken(raw: string, token: string): string {
  const list = parseSiteTokens(raw);
  const lower = token.toLowerCase();
  const next = list.includes(lower) ? list.filter((t) => t !== lower) : [...list, lower];
  return next.join(", ");
}

function formatCapMb(mb: number | null | undefined) {
  if (!mb || mb <= 0) return "Unlimited data";
  return mb >= 1024 && mb % 1024 === 0 ? `${mb / 1024} GB` : `${mb.toLocaleString()} MB`;
}

export default function SchoolPage() {
  const toast = useToast();
  const overview = useApi<Overview>("/api/school");
  const exceptions = useApi<StaffException[]>("/api/school/staff-exceptions?status=PENDING");
  const passes = useApi<Page<VisitorPass>>("/api/school/visitor-passes");
  const liveSessions = useApi<ActiveSession[]>("/api/sessions/active", 15000);
  const [usagePeriod, setUsagePeriod] = useState<SchoolUsagePeriod>("today");
  const usage = useApi<DataUsage>(`/api/dashboard/data-usage?period=${usagePeriod}`, 30000, { keepPrevious: true });
  const [disconnecting, setDisconnecting] = useState<string | null>(null);
  const now = useNow();

  const school = overview.data?.school;
  const settings = overview.data?.settings;
  const connected = liveSessions.data ?? [];
  const connectedStudents = connected.filter((s) => s.category === "STUDENT").length;
  const connectedStaff = connected.filter((s) => s.category === "STAFF").length;
  const connectedVisitors = connected.filter((s) => s.category === "VISITOR").length;
  const connectedCustomers = connected.filter((s) => !s.category || s.category === "PAID_CUSTOMER").length;

  const kickSession = async (username: string) => {
    setDisconnecting(username);
    try {
      await api.post("/api/sessions/disconnect", { username });
      toast("success", `Disconnected session ${username}.`);
      liveSessions.load();
    } catch (err) {
      toast("error", message(err));
    } finally {
      setDisconnecting(null);
    }
  };

  const staffEnabled = settings?.staffEnabled !== false;
  const staffSummary = !staffEnabled
    ? "Staff WiFi paused"
    : `${settings?.staffRateLimit || "Unlimited speed"} · ${formatCapMb(settings?.staffDailyDataCapMb)}/day`;
  const visitorSummary = !settings?.visitorsEnabled
    ? "Visitors switched off"
    : `${settings.visitorRateLimit || "Unlimited speed"} · ${formatCapMb(settings.visitorDataCapMb)}/pass`;

  return (
    <PageContainer>
      <PageHeader
        title="School"
        description="Monitor student, staff and visitor bandwidth, and control speed limits and data caps for free school groups."
      />
      {overview.error && <ErrorNotice text={`Couldn't load the school: ${overview.error}`} />}
      {school && !school.active && (
        <ErrorNotice text="This school is paused by AirPairy. Students and staff can't sign in until it is reactivated." />
      )}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        <StatCard
          label="Connected devices now"
          value={liveSessions.loading && !liveSessions.data ? "—" : connected.length.toLocaleString()}
          icon={<CheckCircle />}
          tone="green"
          loading={liveSessions.loading && !liveSessions.data}
          hint={`${connectedStudents} student · ${connectedStaff} staff · ${connectedVisitors} visitor${connectedCustomers ? ` · ${connectedCustomers} walk-in` : ""}`}
        />
        <StatCard
          label="Students"
          value={school?.students.toLocaleString() ?? "—"}
          icon={<GraduationCap />}
          loading={overview.loading}
          hint={
            settings?.studentFreeEnabled && (settings?.studentDailyFreeMb ?? 0) > 0
              ? `${formatCapMb(settings.studentDailyFreeMb)}/day free study data + paid packages`
              : "Pay for packages as usual"
          }
        />
        <StatCard
          label="Staff with free access"
          value={school?.staff.toLocaleString() ?? "—"}
          icon={<Users />}
          tone={staffEnabled ? "default" : "amber"}
          loading={overview.loading}
          hint={staffSummary}
        />
        <StatCard
          label="Visitor passes today"
          value={school ? `${school.visitorPassesToday}${settings?.visitorsEnabled ? ` / ${settings.visitorDailyCap}` : ""}` : "—"}
          icon={<Ticket />}
          loading={overview.loading}
          hint={visitorSummary}
        />
        <StatCard
          label="Waiting for you"
          value={school?.pendingStaffExceptions.toLocaleString() ?? "—"}
          icon={<CheckCircle />}
          loading={overview.loading}
          hint="Staff exception requests"
        />
      </div>

      {/* School Bandwidth & Group Usage Monitor */}
      <SchoolBandwidthMonitor
        usage={usage.data}
        loading={usage.loading}
        period={usagePeriod}
        onPeriod={setUsagePeriod}
        settings={settings}
        connected={connected}
      />

      {/* Self-service Free Access & Bandwidth Controls + Staff Exception Approvals */}
      <div className="grid gap-6 lg:grid-cols-3 lg:items-start">
        <div className="lg:col-span-2">
          {overview.data && (
            <SettingsCard
              key={JSON.stringify(overview.data.settings)}
              settings={overview.data.settings}
              onSaved={() => {
                overview.load();
                passes.load();
                liveSessions.load();
                usage.load();
              }}
            />
          )}
        </div>
        <Card
          title="Staff exception requests"
          description="Former students can only get free staff access if you approve. You see only what you need to decide."
          bodyClassName=""
        >
          {exceptions.error ? (
            <p className="p-5 text-sm text-red-600">{exceptions.error}</p>
          ) : !exceptions.data?.length ? (
            <p className="p-5 text-sm text-[var(--fg-muted)]">
              {exceptions.loading ? "Loading…" : "Nothing waiting for your decision."}
            </p>
          ) : (
            <ul className="divide-y divide-[var(--border)]">
              {exceptions.data.map((request) => (
                <ExceptionRow
                  key={request.id}
                  request={request}
                  now={now}
                  onDecided={() => {
                    exceptions.load();
                    overview.load();
                  }}
                />
              ))}
            </ul>
          )}
        </Card>
      </div>

      {/* Live Connected Devices with Speed, Data Usage, Cap Progress & Disconnect Action */}
      <Card
        title="Live connected devices"
        description="Every device currently connected on the school network, how much data it has used, its speed/data limit, and a one-click disconnect."
        bodyClassName=""
      >
        <Table
          rows={connected}
          loading={liveSessions.loading && !liveSessions.data}
          rowKey={(s) => String(s.radAcctId || s.username)}
          searchPlaceholder="Search voucher, category, MAC or IP…"
          emptyIcon={<Users />}
          emptyTitle="No devices connected right now"
          emptyDescription="Devices appear here as students, staff, visitors or customers sign in."
          columns={[
            {
              label: "Category",
              render: (s) => (
                <Badge tone={CAT_TONE[s.category || "PAID_CUSTOMER"] ?? "slate"} dot>
                  {s.categoryLabel || "Paid Customer"}
                </Badge>
              ),
            },
            {
              label: "Voucher / Plan",
              render: (s) => (
                <span className="block">
                  <span className="font-mono text-xs font-semibold text-[var(--fg)]">{s.username}</span>
                  <span className="block text-xs text-[var(--fg-muted)]">{s.packageName || "WiFi access"}</span>
                </span>
              ),
            },
            {
              label: "Data used",
              render: (s) => {
                const used = Number(s.totalOctets || 0);
                const capMb =
                  s.category === "STAFF"
                    ? settings?.staffDailyDataCapMb
                    : s.category === "VISITOR"
                    ? settings?.visitorDataCapMb
                    : null;
                const capBytes = capMb && capMb > 0 ? capMb * 1024 * 1024 : null;
                const pct = capBytes ? Math.min(100, Math.round((used / capBytes) * 100)) : null;
                return (
                  <span className="block min-w-[8rem]">
                    <span className="num block text-xs font-semibold text-[var(--fg)]">
                      {bytes(used)}
                      {capBytes ? <span className="font-normal text-[var(--fg-muted)]"> / {formatCapMb(capMb)}</span> : null}
                    </span>
                    <span className="num block text-[11px] text-[var(--fg-muted)]">
                      ↓ {bytes(s.acctOutputOctets || 0)} · ↑ {bytes(s.acctInputOctets || 0)}
                    </span>
                    {pct != null && (
                      <span className="mt-1 block h-1.5 w-28 overflow-hidden rounded-full bg-[var(--fill)]">
                        <span
                          className="block h-full rounded-full"
                          style={{
                            width: `${pct}%`,
                            background: pct >= 90 ? "var(--status-bad)" : pct >= 70 ? "var(--status-warn)" : "var(--chart-1)",
                          }}
                        />
                      </span>
                    )}
                  </span>
                );
              },
            },
            {
              label: "Speed limit",
              hideBelow: "sm",
              render: (s) => (
                <span className="font-mono text-xs text-[var(--fg-body)]">
                  {s.rateLimit || "Unlimited"}
                </span>
              ),
            },
            {
              label: "Device MAC & IP",
              hideBelow: "md",
              render: (s) => (
                <span className="block font-mono text-xs">
                  <span className="block text-[var(--fg)]">{s.callingStationId || "—"}</span>
                  <span className="block text-[var(--fg-muted)]">{s.framedIpAddress || "—"}</span>
                </span>
              ),
            },
            {
              label: "Action",
              align: "right",
              render: (s) => (
                <button
                  type="button"
                  disabled={disconnecting === s.username}
                  onClick={() => kickSession(s.username)}
                  className={`${buttonGhost} !px-2.5 !py-1 text-xs`}
                >
                  {disconnecting === s.username ? "Disconnecting…" : "Disconnect"}
                </button>
              ),
            },
          ]}
        />
      </Card>

      <Card
        title="Visitor passes"
        description="Every pass the school's IT staff have issued. Codes are never shown here."
        bodyClassName=""
      >
        <Table
          rows={passes.data?.content ?? []}
          loading={passes.loading}
          rowKey={(p) => p.id}
          searchPlaceholder="Search visitor, phone or reason…"
          emptyIcon={<Ticket />}
          emptyTitle="No visitor passes yet"
          emptyDescription={
            overview.data?.settings.visitorsEnabled
              ? "Passes appear here as the school issues them."
              : "Switch visitors on to let the school issue passes."
          }
          columns={[
            {
              label: "Visitor",
              render: (p) => (
                <span className="block min-w-0">
                  <span className="block font-medium text-[var(--fg)]">{p.fullName}</span>
                  <span className="block text-xs text-[var(--fg-muted)]">
                    {p.phone} · {p.reason}
                  </span>
                </span>
              ),
            },
            {
              label: "Issued by",
              hideBelow: "md",
              render: (p) => (
                <span className="text-xs">
                  {p.issuedBy}
                  <span className="block text-[var(--fg-muted)]">{relativeTime(p.issuedAt, now)}</span>
                </span>
              ),
            },
            {
              label: "Valid until",
              hideBelow: "sm",
              render: (p) => <span className="text-xs">{new Date(p.expiresAt).toLocaleString()}</span>,
            },
            {
              label: "Status",
              render: (p) => (
                <Badge tone={PASS_TONE[p.status] ?? "slate"} dot>
                  {humanize(p.status === "REVOKED" ? "CANCELLED" : p.status)}
                </Badge>
              ),
              align: "right",
            },
          ]}
        />
      </Card>

      {overview.data?.dashboardUrl && (
        <p className="text-xs text-[var(--fg-muted)]">
          The school&apos;s IT person manages students at{" "}
          <a
            className="font-medium text-[var(--accent-text)] hover:underline"
            href={overview.data.dashboardUrl}
            target="_blank"
            rel="noopener noreferrer"
          >
            {overview.data.dashboardUrl}
          </a>
          .
        </p>
      )}
    </PageContainer>
  );
}

function SchoolBandwidthMonitor({
  usage,
  loading,
  period,
  onPeriod,
  settings,
  connected,
}: {
  usage: DataUsage | null | undefined;
  loading: boolean;
  period: SchoolUsagePeriod;
  onPeriod: (p: SchoolUsagePeriod) => void;
  settings: Settings | undefined;
  connected: ActiveSession[];
}) {
  const categories = usage?.categories ?? [
    { category: "STUDENT", label: "Paid Students", free: false, downBytes: 0, upBytes: 0, totalBytes: 0, users: 0 },
    { category: "STAFF", label: "School Staff", free: true, downBytes: 0, upBytes: 0, totalBytes: 0, users: 0 },
    { category: "VISITOR", label: "Visitors", free: true, downBytes: 0, upBytes: 0, totalBytes: 0, users: 0 },
    { category: "PAID_CUSTOMER", label: "Paid Customers", free: false, downBytes: 0, upBytes: 0, totalBytes: 0, users: 0 },
  ];

  const totalBytes = categories.reduce((s, c) => s + Number(c.totalBytes || 0), 0);
  const freeBytes = categories.filter((c) => c.free).reduce((s, c) => s + Number(c.totalBytes || 0), 0);
  const paidBytes = totalBytes - freeBytes;
  const freeShare = totalBytes > 0 ? Math.round((freeBytes / totalBytes) * 100) : 0;
  const periodLabel = PERIOD_OPTIONS.find((o) => o.key === period)?.label ?? "Today";

  const activeCount = (cat: string) =>
    connected.filter((s) => (s.category || "PAID_CUSTOMER") === cat).length;

  const policyFor = (cat: string) => {
    if (cat === "STAFF") {
      if (settings?.staffEnabled === false) return "Paused";
      return `${settings?.staffRateLimit || "No speed limit"} · ${formatCapMb(settings?.staffDailyDataCapMb)}/day`;
    }
    if (cat === "VISITOR") {
      if (!settings?.visitorsEnabled) return "Switched off";
      return `${settings?.visitorRateLimit || "No speed limit"} · ${formatCapMb(settings?.visitorDataCapMb)}/pass`;
    }
    return "Paid package rules";
  };

  return (
    <Card
      title="Bandwidth & data usage by school group"
      description={`Compare how much internet Paid Students, Free Staff, Free Visitors, and Walk-in Customers consume (${periodLabel.toLowerCase()}).`}
      action={<Segmented label="School bandwidth period" value={period} onChange={onPeriod} options={[...PERIOD_OPTIONS]} />}
    >
      <div className={`space-y-5 transition-opacity ${loading && usage ? "opacity-60" : ""}`}>
        {freeBytes > 0 && freeShare >= 35 && (
          <div className="flex items-start gap-2.5 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-900">
            <WarningCircle className="mt-0.5 h-4 w-4 shrink-0" />
            <div>
              <p className="font-semibold">
                Free groups (Staff &amp; Visitors) consumed {bytes(freeBytes)} ({freeShare}% of total school bandwidth) {periodLabel.toLowerCase()}.
              </p>
              <p className="mt-0.5 opacity-90">
                If free usage is slowing down paid students or eating up your ISP link, lower the Staff/Visitor speed limit or set a daily data cap below.
              </p>
            </div>
          </div>
        )}

        <div className="grid gap-6 lg:grid-cols-12 lg:items-center">
          <div className="lg:col-span-5">
            <DonutChart
              label={`School bandwidth by group, ${periodLabel}`}
              centerLabel={periodLabel}
              centerValue={bytes(totalBytes)}
              format={(v) => bytes(v)}
              segments={categories.map((c) => ({
                key: c.category,
                label: c.label,
                value: Number(c.totalBytes || 0),
                color: CAT_COLOR[c.category],
                badge: c.free ? "Free" : "Paid",
                sublabel: `${c.users} user${c.users === 1 ? "" : "s"} · ${activeCount(c.category)} online now`,
              }))}
            />
          </div>

          <div className="space-y-3 lg:col-span-7">
            <div className="grid grid-cols-3 gap-3 rounded-lg border border-[var(--border)] bg-[var(--subtle)] p-3 text-center sm:text-left">
              <div>
                <p className="text-[11px] text-[var(--fg-muted)]">Total school data ({periodLabel.toLowerCase()})</p>
                <p className="num mt-0.5 text-sm font-semibold text-[var(--fg)]">{bytes(totalBytes)}</p>
              </div>
              <div>
                <p className="text-[11px] text-[var(--fg-muted)]">Paid usage (Students &amp; Buyers)</p>
                <p className="num mt-0.5 text-sm font-semibold text-emerald-700">{bytes(paidBytes)}</p>
              </div>
              <div>
                <p className="text-[11px] text-[var(--fg-muted)]">Free usage (Staff &amp; Visitors)</p>
                <p className="num mt-0.5 text-sm font-semibold text-[var(--accent-text)]">
                  {bytes(freeBytes)} <span className="text-xs font-normal text-[var(--fg-muted)]">({freeShare}%)</span>
                </p>
              </div>
            </div>

            <div className="overflow-x-auto rounded-lg border border-[var(--border)]">
              <table className="w-full text-left text-xs">
                <thead className="border-b border-[var(--border)] bg-[var(--subtle)] text-[var(--fg-muted)]">
                  <tr>
                    <th scope="col" className="px-3 py-2 font-medium">Group</th>
                    <th scope="col" className="px-2 py-2 text-right font-medium">Online</th>
                    <th scope="col" className="px-2 py-2 text-right font-medium">Download / Upload</th>
                    <th scope="col" className="hidden px-2 py-2 text-right font-medium sm:table-cell">Avg / user</th>
                    <th scope="col" className="px-3 py-2 text-right font-medium">Total &amp; Policy</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--border-soft)]">
                  {categories.map((c) => {
                    const avg = c.users > 0 ? Math.round(c.totalBytes / c.users) : 0;
                    const pct = totalBytes > 0 ? Math.round((c.totalBytes / totalBytes) * 100) : 0;
                    return (
                      <tr key={c.category} className="hover:bg-[var(--subtle)]">
                        <td className="px-3 py-2.5">
                          <span className="flex items-center gap-2 font-medium text-[var(--fg)]">
                            <span className="h-2.5 w-2.5 shrink-0 rounded-[3px]" style={{ background: CAT_COLOR[c.category] }} />
                            {c.label}
                          </span>
                          <span className="mt-0.5 block pl-4 text-[11px] text-[var(--fg-muted)]">
                            {c.free ? "Free school access" : "Paid access"} · {c.users} active in period
                          </span>
                        </td>
                        <td className="num px-2 py-2.5 text-right font-medium text-[var(--fg)]">
                          {activeCount(c.category)}
                        </td>
                        <td className="num px-2 py-2.5 text-right text-[var(--fg-muted)]">
                          ↓ {bytes(c.downBytes)}
                          <span className="block">↑ {bytes(c.upBytes)}</span>
                        </td>
                        <td className="num hidden px-2 py-2.5 text-right text-[var(--fg-body)] sm:table-cell">
                          {c.users > 0 ? bytes(avg) : "—"}
                        </td>
                        <td className="px-3 py-2.5 text-right">
                          <span className="num block font-semibold text-[var(--fg)]">
                            {bytes(c.totalBytes)} <span className="font-normal text-[var(--fg-muted)]">({pct}%)</span>
                          </span>
                          <span className="block text-[11px] text-[var(--fg-muted)]">{policyFor(c.category)}</span>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </Card>
  );
}

function SettingsCard({ settings, onSaved }: { settings: Settings; onSaved: () => void }) {
  const toast = useToast();
  const [studentFreeEnabled, setStudentFreeEnabled] = useState(Boolean(settings.studentFreeEnabled));
  const [studentFreeMb, setStudentFreeMb] = useState(
    settings.studentDailyFreeMb && settings.studentDailyFreeMb > 0 ? String(settings.studentDailyFreeMb) : "100"
  );
  const [studentFreeRate, setStudentFreeRate] = useState(settings.studentFreeRateLimit ?? "2M/2M");
  const [studentAllowedSites, setStudentAllowedSites] = useState(settings.studentAllowedSites ?? "");

  const [staffEnabled, setStaffEnabled] = useState(settings.staffEnabled !== false);
  const [staffRate, setStaffRate] = useState(settings.staffRateLimit ?? "");
  const [staffCapMb, setStaffCapMb] = useState(
    settings.staffDailyDataCapMb && settings.staffDailyDataCapMb > 0 ? String(settings.staffDailyDataCapMb) : ""
  );
  const [staffSiteMode, setStaffSiteMode] = useState<"ALL" | "ALLOW_LIST" | "BLOCK_LIST">(
    settings.staffSiteMode === "ALLOW_LIST" || settings.staffSiteMode === "BLOCK_LIST"
      ? settings.staffSiteMode
      : "ALL"
  );
  const [staffSiteRules, setStaffSiteRules] = useState(settings.staffSiteRules ?? "");

  const [visitors, setVisitors] = useState(settings.visitorsEnabled);
  const [visitorDailyPasses, setVisitorDailyPasses] = useState(String(settings.visitorDailyCap || 20));
  const [visitorRate, setVisitorRate] = useState(settings.visitorRateLimit ?? "");
  const [visitorCapMb, setVisitorCapMb] = useState(
    settings.visitorDataCapMb && settings.visitorDataCapMb > 0 ? String(settings.visitorDataCapMb) : ""
  );
  const [tethering, setTethering] = useState<"OFF" | "LOG" | "BLOCK">(
    settings.tetheringProtection === "OFF" || settings.tetheringProtection === "LOG"
      ? settings.tetheringProtection
      : "BLOCK"
  );
  const [applyNow, setApplyNow] = useState(true);
  const [busy, setBusy] = useState(false);

  const studentSiteTokens = parseSiteTokens(studentAllowedSites);
  const staffSiteTokens = parseSiteTokens(staffSiteRules);

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      await api.put("/api/school/settings", {
        studentFreeEnabled,
        studentDailyFreeMb: studentFreeEnabled && studentFreeMb.trim() ? Number(studentFreeMb) : 0,
        studentFreeRateLimit: studentFreeRate.trim(),
        studentAllowedSites: studentAllowedSites.trim(),
        staffEnabled,
        staffRateLimit: staffRate.trim(),
        staffDailyDataCapMb: staffCapMb.trim() ? Number(staffCapMb) : 0,
        staffSiteMode,
        staffSiteRules: staffSiteRules.trim(),
        visitorsEnabled: visitors,
        visitorDailyCap: Number(visitorDailyPasses) || 0,
        visitorRateLimit: visitorRate.trim(),
        visitorDataCapMb: visitorCapMb.trim() ? Number(visitorCapMb) : 0,
        tetheringProtection: tethering,
        applyImmediately: applyNow,
      });
      toast(
        "success",
        applyNow
          ? "Saved and applied to active school sessions and router filters."
          : "Saved. New rules will apply as students, staff and visitors sign in."
      );
      onSaved();
    } catch (err) {
      toast("error", message(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card
      title="Free access, website filtering & bandwidth controls"
      description="Control daily free study MBs & allowed websites for students, staff free WiFi & allowed/blocked apps, visitor passes, and hotspot sharing."
    >
      <form onSubmit={save} className="space-y-6">
        {/* 1. Student Daily Free Study Data + Allowed Study Websites */}
        <div className="space-y-4 rounded-xl border border-[var(--border)] bg-[var(--subtle)] p-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h3 className="text-sm font-semibold text-[var(--fg)]">
                1. Student Daily Free Study Data (Restricted Websites)
              </h3>
              <p className="text-xs text-[var(--fg-muted)]">
                When a student signs in without a paid package, let them choose between buying a full-internet package or using daily free MBs restricted to approved study websites.
              </p>
            </div>
            <label className="inline-flex cursor-pointer items-center gap-2 text-xs font-medium text-[var(--fg)]">
              <input
                type="checkbox"
                className="h-4 w-4"
                checked={studentFreeEnabled}
                onChange={(e) => setStudentFreeEnabled(e.target.checked)}
              />
              {studentFreeEnabled ? "Daily free study MBs enabled" : "Paid packages only"}
            </label>
          </div>

          {studentFreeEnabled ? (
            <div className="space-y-4">
              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <label htmlFor="student-free-mb" className={label}>
                    Free daily study data per student (MB / day)
                  </label>
                  <input
                    id="student-free-mb"
                    type="number"
                    min={1}
                    max={100000}
                    required
                    value={studentFreeMb}
                    onChange={(e) => setStudentFreeMb(e.target.value)}
                    placeholder="e.g. 100"
                    className={field}
                  />
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {STUDENT_FREE_MB_PRESETS.map((p) => (
                      <button
                        key={p.label}
                        type="button"
                        onClick={() => setStudentFreeMb(p.value)}
                        className={`rounded-md border px-2 py-1 text-[11px] font-medium transition-colors ${
                          studentFreeMb.trim() === p.value
                            ? "border-[var(--primary)] bg-[var(--primary-soft)] text-[var(--accent-text)]"
                            : "border-[var(--border)] bg-[var(--surface)] text-[var(--fg-muted)] hover:text-[var(--fg)]"
                        }`}
                      >
                        {p.label}
                      </button>
                    ))}
                  </div>
                  <p className={hint}>Resets every midnight. Buying any paid package upgrades the student to full internet immediately.</p>
                </div>

                <div>
                  <label htmlFor="student-free-rate" className={label}>
                    Free study data speed limit
                  </label>
                  <input
                    id="student-free-rate"
                    maxLength={30}
                    value={studentFreeRate}
                    onChange={(e) => setStudentFreeRate(e.target.value)}
                    placeholder="e.g. 2M/2M"
                    className={field}
                  />
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {SPEED_PRESETS.map((p) => (
                      <button
                        key={p.label}
                        type="button"
                        onClick={() => setStudentFreeRate(p.value)}
                        className={`rounded-md border px-2 py-1 text-[11px] font-medium transition-colors ${
                          studentFreeRate.trim().toUpperCase() === p.value
                            ? "border-[var(--primary)] bg-[var(--primary-soft)] text-[var(--accent-text)]"
                            : "border-[var(--border)] bg-[var(--surface)] text-[var(--fg-muted)] hover:text-[var(--fg)]"
                        }`}
                      >
                        {p.label}
                      </button>
                    ))}
                  </div>
                  <p className={hint}>Speed limit while a student is using their free daily study data.</p>
                </div>
              </div>

              <div>
                <label htmlFor="student-allowed-sites" className={label}>
                  Allowed websites on Student Free Data (Strict Whitelist)
                </label>
                <input
                  id="student-allowed-sites"
                  maxLength={2000}
                  value={studentAllowedSites}
                  onChange={(e) => setStudentAllowedSites(e.target.value)}
                  placeholder="e.g. elearning.school.ac.ug, portal.school.ac.ug, @wikipedia, @scholar"
                  className={field}
                />
                <div className="mt-2 flex flex-wrap items-center gap-1.5">
                  <span className="text-[11px] text-[var(--fg-muted)]">Quick toggle study platforms:</span>
                  {STUDY_SITE_PRESETS.map((p) => {
                    const active = studentSiteTokens.includes(p.token);
                    return (
                      <button
                        key={p.token}
                        type="button"
                        onClick={() => setStudentAllowedSites(toggleSiteToken(studentAllowedSites, p.token))}
                        className={`rounded-md border px-2 py-1 text-[11px] font-medium transition-colors ${
                          active
                            ? "border-[var(--primary)] bg-[var(--primary-soft)] text-[var(--accent-text)]"
                            : "border-[var(--border)] bg-[var(--surface)] text-[var(--fg-muted)] hover:text-[var(--fg)]"
                        }`}
                      >
                        {active ? "✓ " : "+ "}
                        {p.label}
                      </button>
                    );
                  })}
                </div>
                <p className={hint}>
                  When listed, students on free daily data can ONLY open these domains (plus the payment portal to buy a full package). All other sites and apps are blocked on the router.
                </p>
              </div>
            </div>
          ) : (
            <p className="text-xs text-[var(--fg-muted)]">
              Students go straight to buying a paid package after verifying their Registration Number &amp; PIN.
            </p>
          )}
        </div>

        {/* 2. Staff Controls Section + Website & App Access Policy */}
        <div className="space-y-4 rounded-xl border border-[var(--border)] bg-[var(--subtle)] p-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h3 className="text-sm font-semibold text-[var(--fg)]">2. School Staff Free WiFi &amp; App Filtering</h3>
              <p className="text-xs text-[var(--fg-muted)]">
                Staff sign in once with their Staff ID and PIN and reconnect automatically for their active period. Control their speed, daily MB cap, and which apps/websites they can access.
              </p>
            </div>
            <label className="inline-flex cursor-pointer items-center gap-2 text-xs font-medium text-[var(--fg)]">
              <input
                type="checkbox"
                className="h-4 w-4"
                checked={staffEnabled}
                onChange={(e) => setStaffEnabled(e.target.checked)}
              />
              {staffEnabled ? "Staff WiFi enabled" : "Paused"}
            </label>
          </div>

          {staffEnabled ? (
            <div className="space-y-4">
              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <label htmlFor="staff-rate" className={label}>
                    Staff speed limit (Download / Upload)
                  </label>
                  <input
                    id="staff-rate"
                    maxLength={30}
                    value={staffRate}
                    onChange={(e) => setStaffRate(e.target.value)}
                    placeholder="Unlimited (or e.g. 5M/5M)"
                    className={field}
                  />
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {SPEED_PRESETS.map((p) => (
                      <button
                        key={p.label}
                        type="button"
                        onClick={() => setStaffRate(p.value)}
                        className={`rounded-md border px-2 py-1 text-[11px] font-medium transition-colors ${
                          staffRate.trim().toUpperCase() === p.value
                            ? "border-[var(--primary)] bg-[var(--primary-soft)] text-[var(--accent-text)]"
                            : "border-[var(--border)] bg-[var(--surface)] text-[var(--fg-muted)] hover:text-[var(--fg)]"
                        }`}
                      >
                        {p.label}
                      </button>
                    ))}
                  </div>
                  <p className={hint}>Prevents one staff laptop from hogging the whole school link.</p>
                </div>

                <div>
                  <label htmlFor="staff-data-cap" className={label}>
                    Staff daily data cap (MB per staff member / day)
                  </label>
                  <input
                    id="staff-data-cap"
                    type="number"
                    min={0}
                    max={1000000}
                    value={staffCapMb}
                    onChange={(e) => setStaffCapMb(e.target.value)}
                    placeholder="Unlimited (leave empty)"
                    className={field}
                  />
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {STAFF_CAP_PRESETS.map((p) => (
                      <button
                        key={p.label}
                        type="button"
                        onClick={() => setStaffCapMb(p.value)}
                        className={`rounded-md border px-2 py-1 text-[11px] font-medium transition-colors ${
                          staffCapMb.trim() === p.value
                            ? "border-[var(--primary)] bg-[var(--primary-soft)] text-[var(--accent-text)]"
                            : "border-[var(--border)] bg-[var(--surface)] text-[var(--fg-muted)] hover:text-[var(--fg)]"
                        }`}
                      >
                        {p.label}
                      </button>
                    ))}
                  </div>
                  <p className={hint}>Once a staff member reaches this MB limit today, their free session pauses until tomorrow.</p>
                </div>
              </div>

              <div className="space-y-3 border-t border-[var(--border)] pt-3">
                <div>
                  <span className={label}>Staff website &amp; app access policy</span>
                  <div className="mt-1.5 grid gap-2 sm:grid-cols-3">
                    {[
                      {
                        key: "ALL" as const,
                        title: "Allow All Websites",
                        desc: "Staff can browse any website or app within their speed & daily MB cap.",
                      },
                      {
                        key: "ALLOW_LIST" as const,
                        title: "Allow ONLY Selected Apps & Sites",
                        desc: "Staff can ONLY access the apps and domains you select below; everything else is blocked.",
                      },
                      {
                        key: "BLOCK_LIST" as const,
                        title: "Block Selected Apps & Sites",
                        desc: "Staff can use the full internet EXCEPT the high-bandwidth apps or sites you select below.",
                      },
                    ].map((opt) => (
                      <button
                        key={opt.key}
                        type="button"
                        onClick={() => setStaffSiteMode(opt.key)}
                        className={`rounded-lg border p-2.5 text-left transition-colors ${
                          staffSiteMode === opt.key
                            ? "border-[var(--primary)] bg-[var(--primary-soft)]"
                            : "border-[var(--border)] bg-[var(--surface)] hover:border-[var(--fg-muted)]"
                        }`}
                      >
                        <span className="block text-xs font-semibold text-[var(--fg)]">{opt.title}</span>
                        <span className="mt-0.5 block text-[11px] text-[var(--fg-muted)]">{opt.desc}</span>
                      </button>
                    ))}
                  </div>
                </div>

                {staffSiteMode !== "ALL" && (
                  <div>
                    <label htmlFor="staff-site-rules" className={label}>
                      {staffSiteMode === "ALLOW_LIST"
                        ? "Apps & domains staff are ALLOWED to use"
                        : "Apps & domains BLOCKED on staff WiFi"}
                    </label>
                    <div className="mb-2 flex flex-wrap gap-1.5">
                      {STAFF_APP_PRESETS.map((app) => {
                        const active = staffSiteTokens.includes(app.token);
                        return (
                          <button
                            key={app.token}
                            type="button"
                            onClick={() => setStaffSiteRules(toggleSiteToken(staffSiteRules, app.token))}
                            className={`rounded-md border px-2.5 py-1 text-xs font-medium transition-colors ${
                              active
                                ? "border-[var(--primary)] bg-[var(--primary-soft)] text-[var(--accent-text)]"
                                : "border-[var(--border)] bg-[var(--surface)] text-[var(--fg-muted)] hover:text-[var(--fg)]"
                            }`}
                          >
                            {active ? "✓ " : "+ "}
                            {app.label}
                          </button>
                        );
                      })}
                    </div>
                    <input
                      id="staff-site-rules"
                      maxLength={2000}
                      value={staffSiteRules}
                      onChange={(e) => setStaffSiteRules(e.target.value)}
                      placeholder="Click apps above or add custom domains e.g. @whatsapp, @youtube, @chatgpt, elearning.school.ac.ug"
                      className={field}
                    />
                    <p className={hint}>
                      {staffSiteMode === "ALLOW_LIST"
                        ? "Only selected apps (@whatsapp, @youtube, @chatgpt, @tiktok, etc.) and custom domains listed above will work on Staff WiFi."
                        : "Selected apps and custom domains listed above will be blocked on Staff WiFi by the MikroTik router."}
                    </p>
                  </div>
                )}
              </div>
            </div>
          ) : (
            <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900">
              Free staff WiFi is paused. Saving will disconnect any active free staff sessions until you re-enable it.
            </p>
          )}
        </div>

        {/* 3. Visitor Pass Controls Section */}
        <div className="space-y-4 rounded-xl border border-[var(--border)] bg-[var(--subtle)] p-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h3 className="text-sm font-semibold text-[var(--fg)]">3. School Visitor Passes</h3>
              <p className="text-xs text-[var(--fg-muted)]">
                Short-term passes issued by the school&apos;s IT officer for guests.
              </p>
            </div>
            <label className="inline-flex cursor-pointer items-center gap-2 text-xs font-medium text-[var(--fg)]">
              <input
                type="checkbox"
                className="h-4 w-4"
                checked={visitors}
                onChange={(e) => setVisitors(e.target.checked)}
              />
              {visitors ? "Visitor passes enabled" : "Switched off"}
            </label>
          </div>

          {visitors ? (
            <div className="grid gap-4 sm:grid-cols-3">
              <div>
                <label htmlFor="visitor-cap" className={label}>
                  Max passes per day
                </label>
                <input
                  id="visitor-cap"
                  type="number"
                  min={1}
                  max={1000}
                  required
                  value={visitorDailyPasses}
                  onChange={(e) => setVisitorDailyPasses(e.target.value)}
                  className={field}
                />
                <p className={hint}>How many visitor passes IT can issue each day.</p>
              </div>

              <div>
                <label htmlFor="visitor-rate" className={label}>
                  Visitor speed limit
                </label>
                <input
                  id="visitor-rate"
                  maxLength={30}
                  value={visitorRate}
                  onChange={(e) => setVisitorRate(e.target.value)}
                  placeholder="Unlimited (or e.g. 2M/2M)"
                  className={field}
                />
                <div className="mt-2 flex flex-wrap gap-1.5">
                  {SPEED_PRESETS.map((p) => (
                    <button
                      key={p.label}
                      type="button"
                      onClick={() => setVisitorRate(p.value)}
                      className={`rounded-md border px-2 py-1 text-[11px] font-medium transition-colors ${
                        visitorRate.trim().toUpperCase() === p.value
                          ? "border-[var(--primary)] bg-[var(--primary-soft)] text-[var(--accent-text)]"
                          : "border-[var(--border)] bg-[var(--surface)] text-[var(--fg-muted)] hover:text-[var(--fg)]"
                      }`}
                    >
                      {p.label}
                    </button>
                  ))}
                </div>
                <p className={hint}>Speed cap for each connected visitor.</p>
              </div>

              <div>
                <label htmlFor="visitor-data-cap" className={label}>
                  Visitor data cap (MB per pass)
                </label>
                <input
                  id="visitor-data-cap"
                  type="number"
                  min={0}
                  max={1000000}
                  value={visitorCapMb}
                  onChange={(e) => setVisitorCapMb(e.target.value)}
                  placeholder="Unlimited (leave empty)"
                  className={field}
                />
                <div className="mt-2 flex flex-wrap gap-1.5">
                  {VISITOR_CAP_PRESETS.map((p) => (
                    <button
                      key={p.label}
                      type="button"
                      onClick={() => setVisitorCapMb(p.value)}
                      className={`rounded-md border px-2 py-1 text-[11px] font-medium transition-colors ${
                        visitorCapMb.trim() === p.value
                          ? "border-[var(--primary)] bg-[var(--primary-soft)] text-[var(--accent-text)]"
                          : "border-[var(--border)] bg-[var(--surface)] text-[var(--fg-muted)] hover:text-[var(--fg)]"
                      }`}
                    >
                      {p.label}
                    </button>
                  ))}
                </div>
                <p className={hint}>Total MB a visitor pass may use before expiring.</p>
              </div>
            </div>
          ) : (
            <p className="text-xs text-[var(--fg-muted)]">
              When switched off, the school&apos;s IT staff cannot create visitor passes and the &ldquo;I&apos;m a visitor&rdquo; button is hidden on the captive portal.
            </p>
          )}
        </div>

        {/* 4. Anti-Hotspot / Anti-Tethering Protection Section */}
        <div className="space-y-3 rounded-xl border border-[var(--border)] bg-[var(--subtle)] p-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h3 className="text-sm font-semibold text-[var(--fg)]">
                4. Block Hotspot &amp; Tethering Sharing
              </h3>
              <p className="text-xs text-[var(--fg-muted)]">
                Prevent a connected phone or laptop from turning on Mobile Hotspot or USB/WiFi sharing to give free internet to other devices.
              </p>
            </div>
            <Badge tone={tethering === "BLOCK" ? "green" : tethering === "LOG" ? "amber" : "slate"} dot>
              {tethering === "BLOCK" ? "Hotspot sharing blocked" : tethering === "LOG" ? "Detect & log only" : "Sharing allowed"}
            </Badge>
          </div>
          <div className="grid gap-2.5 sm:grid-cols-3">
            {[
              {
                key: "BLOCK" as const,
                title: "Block Hotspot Sharing (Recommended)",
                desc: "Locks packet TTL=1 to devices and drops tethered Android/iOS/Windows hotspot traffic.",
              },
              {
                key: "LOG" as const,
                title: "Detect & Log Only",
                desc: "Logs hotspot sharing attempts on the router without dropping packets.",
              },
              {
                key: "OFF" as const,
                title: "Allow Hotspot Sharing",
                desc: "No tethering filter or TTL lock on the school router.",
              },
            ].map((opt) => (
              <button
                key={opt.key}
                type="button"
                onClick={() => setTethering(opt.key)}
                className={`rounded-lg border p-3 text-left transition-colors ${
                  tethering === opt.key
                    ? "border-[var(--primary)] bg-[var(--primary-soft)]"
                    : "border-[var(--border)] bg-[var(--surface)] hover:border-[var(--fg-muted)]"
                }`}
              >
                <span className="block text-xs font-semibold text-[var(--fg)]">{opt.title}</span>
                <span className="mt-1 block text-[11px] text-[var(--fg-muted)]">{opt.desc}</span>
              </button>
            ))}
          </div>
        </div>

        <div className="flex flex-col gap-3 border-t border-[var(--border)] pt-4 sm:flex-row sm:items-center sm:justify-between">
          <label className="flex cursor-pointer items-center gap-2.5 text-xs text-[var(--fg-body)]">
            <input
              type="checkbox"
              className="h-4 w-4"
              checked={applyNow}
              onChange={(e) => setApplyNow(e.target.checked)}
            />
            <span>Apply new limits &amp; website filters immediately to connected devices and routers</span>
          </label>
          <button type="submit" disabled={busy} className={button}>
            {busy ? "Saving…" : "Save & apply controls"}
          </button>
        </div>
      </form>
    </Card>
  );
}

function ExceptionRow({ request, now, onDecided }: { request: StaffException; now: number; onDecided: () => void }) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const decide = async (approve: boolean) => {
    setBusy(true);
    try {
      await api.post(`/api/school/staff-exceptions/${request.id}/decision`, { approve });
      toast(
        "success",
        approve ? `${request.fullName} can now be given free staff access.` : `Request for ${request.fullName} rejected.`
      );
      onDecided();
    } catch (err) {
      toast("error", message(err));
    } finally {
      setBusy(false);
    }
  };
  return (
    <li className="space-y-2 px-5 py-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="font-medium text-[var(--fg)]">{request.fullName}</span>
        <span className="text-xs text-[var(--fg-muted)]">
          {request.requestedBy} · {relativeTime(request.requestedAt, now)}
        </span>
      </div>
      <p className="text-xs text-[var(--fg-muted)]">ID numbers: {request.identifiers}</p>
      <p className="rounded-lg bg-[var(--subtle)] p-2.5 text-xs text-[var(--fg)]">&ldquo;{request.reason}&rdquo;</p>
      <div className="flex gap-2">
        <button type="button" disabled={busy} onClick={() => decide(true)} className={button}>
          <CheckCircle className="h-4 w-4" />
          Approve
        </button>
        <button type="button" disabled={busy} onClick={() => decide(false)} className={buttonGhost}>
          <XCircle className="h-4 w-4" />
          Reject
        </button>
      </div>
    </li>
  );
}
