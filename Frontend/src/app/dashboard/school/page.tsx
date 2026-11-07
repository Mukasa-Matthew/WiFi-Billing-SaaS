"use client";

import React, { useState } from "react";
import api from "@/lib/api";
import { CheckCircle, GraduationCap, Ticket, Users, XCircle } from "@/components/icons";
import {
  Badge, Card, ErrorNotice, PageContainer, PageHeader, StatCard, Table, button, buttonGhost, field, hint, humanize, label, message, relativeTime, useApi, useNow,
} from "@/components/BillingUI";
import { useToast } from "@/components/ui/Toast";

interface Settings { visitorsEnabled: boolean; visitorDailyCap: number; staffRateLimit: string | null; visitorRateLimit: string | null }
interface SchoolSummary { name: string; active: boolean; students: number; staff: number; activePeople: number; visitorPassesToday: number; pendingStaffExceptions: number }
interface Overview { settings: Settings; school: SchoolSummary; dashboardUrl: string }
interface StaffException { id: string; fullName: string; identifiers: string; reason: string; requestedBy: string; requestedAt: string; status: string; decidedAt: string | null; decidedBy: string | null }
interface VisitorPass { id: string; fullName: string; phone: string; reason: string; issuedBy: string; issuedAt: string; expiresAt: string; status: string; deviceMac: string | null }
interface ActiveSession { radAcctId: number; username: string; framedIpAddress: string | null; callingStationId: string | null; nasIpAddress: string | null; acctSessionTime: number | null; totalOctets: number | null; category?: string | null; categoryLabel?: string | null; packageName?: string | null; customerName?: string | null }
interface Page<T> { content: T[]; totalElements: number }

const PASS_TONE: Record<string, "green" | "blue" | "amber" | "slate"> = { IN_USE: "green", UNUSED: "blue", EXPIRED: "slate", REVOKED: "amber" };
const CAT_TONE: Record<string, "green" | "blue" | "amber" | "slate"> = { STUDENT: "green", STAFF: "blue", VISITOR: "amber", PAID_CUSTOMER: "slate" };

export default function SchoolPage() {
  const overview = useApi<Overview>("/api/school");
  const exceptions = useApi<StaffException[]>("/api/school/staff-exceptions?status=PENDING");
  const passes = useApi<Page<VisitorPass>>("/api/school/visitor-passes");
  const liveSessions = useApi<ActiveSession[]>("/api/sessions/active", 15000);
  const now = useNow();
  const school = overview.data?.school;
  const connected = liveSessions.data ?? [];
  const connectedStudents = connected.filter((s) => s.category === "STUDENT").length;
  const connectedStaff = connected.filter((s) => s.category === "STAFF").length;
  const connectedVisitors = connected.filter((s) => s.category === "VISITOR").length;
  const connectedCustomers = connected.filter((s) => !s.category || s.category === "PAID_CUSTOMER").length;

  return (
    <PageContainer>
      <PageHeader title="School" description="Free access for staff and visitors, and who may get it. Students and their records are managed by the school's IT person." />
      {overview.error && <ErrorNotice text={`Couldn't load the school: ${overview.error}`} />}
      {school && !school.active && <ErrorNotice text="This school is paused by AirPairy. Students and staff can't sign in until it is reactivated." />}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        <StatCard label="Connected devices now" value={liveSessions.loading && !liveSessions.data ? "—" : connected.length.toLocaleString()} icon={<CheckCircle />} tone="green" loading={liveSessions.loading && !liveSessions.data} hint={`${connectedStudents} student · ${connectedStaff} staff · ${connectedVisitors} visitor${connectedCustomers ? ` · ${connectedCustomers} walk-in` : ""}`} />
        <StatCard label="Students" value={school?.students.toLocaleString() ?? "—"} icon={<GraduationCap />} loading={overview.loading} hint="Registered by the school" />
        <StatCard label="Staff with free access" value={school?.staff.toLocaleString() ?? "—"} icon={<Users />} loading={overview.loading} hint="Renewed yearly by the school" />
        <StatCard label="Visitor passes today" value={school ? `${school.visitorPassesToday}${overview.data?.settings.visitorsEnabled ? ` / ${overview.data.settings.visitorDailyCap}` : ""}` : "—"} icon={<Ticket />} loading={overview.loading} hint={overview.data?.settings.visitorsEnabled ? "Of today's limit" : "Visitors are switched off"} />
        <StatCard label="Waiting for you" value={school?.pendingStaffExceptions.toLocaleString() ?? "—"} icon={<CheckCircle />} loading={overview.loading} hint="Staff exception requests" />
      </div>
      <Card title="Live connected devices" description="Every device currently connected on the school network and where it belongs (Student, Staff, Visitor Pass, or Paid Customer)." bodyClassName="">
        <Table rows={connected} loading={liveSessions.loading && !liveSessions.data} rowKey={(s) => String(s.radAcctId || s.username)} searchPlaceholder="Search voucher, category, MAC or IP…"
          emptyIcon={<Users />} emptyTitle="No devices connected right now" emptyDescription="Devices appear here as students, staff, visitors or customers sign in."
          columns={[
            { label: "Category", render: (s) => <Badge tone={CAT_TONE[s.category || "PAID_CUSTOMER"] ?? "slate"} dot>{s.categoryLabel || "Paid Customer"}</Badge> },
            { label: "Voucher / Plan", render: (s) => <span className="block"><span className="font-mono text-xs font-semibold text-[var(--fg)]">{s.username}</span><span className="block text-xs text-[var(--fg-muted)]">{s.packageName || "WiFi access"}</span></span> },
            { label: "Device MAC", render: (s) => <span className="font-mono text-xs">{s.callingStationId || "—"}</span> },
            { label: "IP Address", hideBelow: "sm", render: (s) => <span className="font-mono text-xs text-[var(--fg-muted)]">{s.framedIpAddress || "—"}</span> },
          ]} />
      </Card>
      <div className="grid gap-6 lg:grid-cols-2">
        {overview.data && <SettingsCard key={JSON.stringify(overview.data.settings)} settings={overview.data.settings} onSaved={() => { overview.load(); passes.load(); }} />}
        <Card title="Staff exception requests" description="Former students can only get free staff access if you approve. You see only what you need to decide." bodyClassName="">
          {exceptions.error ? <p className="p-5 text-sm text-red-600">{exceptions.error}</p>
            : !exceptions.data?.length ? <p className="p-5 text-sm text-[var(--fg-muted)]">{exceptions.loading ? "Loading…" : "Nothing waiting for your decision."}</p>
            : <ul className="divide-y divide-[var(--border)]">{exceptions.data.map((request) => <ExceptionRow key={request.id} request={request} now={now} onDecided={() => { exceptions.load(); overview.load(); }} />)}</ul>}
        </Card>
      </div>
      <Card title="Visitor passes" description="Every pass the school's IT staff have issued. Codes are never shown here." bodyClassName="">
        <Table rows={passes.data?.content ?? []} loading={passes.loading} rowKey={(p) => p.id} searchPlaceholder="Search visitor, phone or reason…"
          emptyIcon={<Ticket />} emptyTitle="No visitor passes yet" emptyDescription={overview.data?.settings.visitorsEnabled ? "Passes appear here as the school issues them." : "Switch visitors on to let the school issue passes."}
          columns={[
            { label: "Visitor", render: (p) => <span className="block min-w-0"><span className="block font-medium text-[var(--fg)]">{p.fullName}</span><span className="block text-xs text-[var(--fg-muted)]">{p.phone} · {p.reason}</span></span> },
            { label: "Issued by", hideBelow: "md", render: (p) => <span className="text-xs">{p.issuedBy}<span className="block text-[var(--fg-muted)]">{relativeTime(p.issuedAt, now)}</span></span> },
            { label: "Valid until", hideBelow: "sm", render: (p) => <span className="text-xs">{new Date(p.expiresAt).toLocaleString()}</span> },
            { label: "Status", render: (p) => <Badge tone={PASS_TONE[p.status] ?? "slate"} dot>{humanize(p.status === "REVOKED" ? "CANCELLED" : p.status)}</Badge>, align: "right" },
          ]} />
      </Card>
      {overview.data?.dashboardUrl && <p className="text-xs text-[var(--fg-muted)]">The school&apos;s IT person manages students at <a className="font-medium text-[var(--accent-text)] hover:underline" href={overview.data.dashboardUrl} target="_blank" rel="noopener noreferrer">{overview.data.dashboardUrl}</a>.</p>}
    </PageContainer>
  );
}

function SettingsCard({ settings, onSaved }: { settings: Settings; onSaved: () => void }) {
  const toast = useToast();
  const [visitors, setVisitors] = useState(settings.visitorsEnabled);
  const [cap, setCap] = useState(String(settings.visitorDailyCap || 20));
  const [staffRate, setStaffRate] = useState(settings.staffRateLimit ?? "");
  const [visitorRate, setVisitorRate] = useState(settings.visitorRateLimit ?? "");
  const [busy, setBusy] = useState(false);
  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      await api.put("/api/school/settings", { visitorsEnabled: visitors, visitorDailyCap: Number(cap), staffRateLimit: staffRate.trim(), visitorRateLimit: visitorRate.trim() });
      toast("success", visitors ? `Saved. The school can issue up to ${cap} visitor passes a day.` : "Saved. Visitor passes are switched off.");
      onSaved();
    } catch (err) { toast("error", message(err)); }
    finally { setBusy(false); }
  };
  return (
    <Card title="Free access settings" description="Staff and visitors use the internet for free. Students buy packages as usual.">
      <form onSubmit={save} className="space-y-4">
        <label className="flex items-start gap-3 rounded-lg border border-[var(--border)] p-3">
          <input type="checkbox" className="mt-0.5 h-4 w-4" checked={visitors} onChange={(e) => setVisitors(e.target.checked)} />
          <span><span className="block text-sm font-medium text-[var(--fg)]">Allow visitor passes</span>
            <span className="block text-xs text-[var(--fg-muted)]">When off, the school&apos;s IT staff can&apos;t create visitor passes and the &ldquo;I&apos;m a visitor&rdquo; button disappears from the WiFi page.</span></span>
        </label>
        {visitors && <div><label htmlFor="visitor-cap" className={label}>Visitor passes per day</label>
          <input id="visitor-cap" type="number" min={1} max={1000} required value={cap} onChange={(e) => setCap(e.target.value)} className={field} />
          <p className={hint}>Each pass lasts at most 2 days and works on one device.</p></div>}
        <div className="grid gap-4 sm:grid-cols-2">
          <div><label htmlFor="staff-rate" className={label}>Staff speed limit</label>
            <input id="staff-rate" maxLength={30} value={staffRate} onChange={(e) => setStaffRate(e.target.value)} placeholder="e.g. 5M/5M" className={field} />
            <p className={hint}>Empty means no limit.</p></div>
          <div><label htmlFor="visitor-rate" className={label}>Visitor speed limit</label>
            <input id="visitor-rate" maxLength={30} value={visitorRate} onChange={(e) => setVisitorRate(e.target.value)} placeholder="e.g. 2M/2M" className={field} />
            <p className={hint}>Applies to new sign-ins.</p></div>
        </div>
        <div className="flex justify-end border-t border-[var(--border)] pt-4"><button type="submit" disabled={busy} className={button}>{busy ? "Saving…" : "Save settings"}</button></div>
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
      toast("success", approve ? `${request.fullName} can now be given free staff access.` : `Request for ${request.fullName} rejected.`);
      onDecided();
    } catch (err) { toast("error", message(err)); }
    finally { setBusy(false); }
  };
  return (
    <li className="space-y-2 px-5 py-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="font-medium text-[var(--fg)]">{request.fullName}</span>
        <span className="text-xs text-[var(--fg-muted)]">{request.requestedBy} · {relativeTime(request.requestedAt, now)}</span>
      </div>
      <p className="text-xs text-[var(--fg-muted)]">ID numbers: {request.identifiers}</p>
      <p className="rounded-lg bg-[var(--subtle)] p-2.5 text-xs text-[var(--fg)]">&ldquo;{request.reason}&rdquo;</p>
      <div className="flex gap-2">
        <button type="button" disabled={busy} onClick={() => decide(true)} className={button}><CheckCircle className="h-4 w-4" />Approve</button>
        <button type="button" disabled={busy} onClick={() => decide(false)} className={buttonGhost}><XCircle className="h-4 w-4" />Reject</button>
      </div>
    </li>
  );
}
