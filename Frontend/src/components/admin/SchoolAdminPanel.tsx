"use client";

import React, { useState } from "react";
import api from "@/lib/api";
import { GraduationCap, Key, MagnifyingGlass, UserPlus } from "@/components/icons";
import { Badge, Card, ErrorNotice, Field, Notice, button, buttonGhost, field, humanize, label, message, useApi } from "@/components/BillingUI";
import { useToast } from "@/components/ui/Toast";

interface ItAdmin { id: string; email: string; fullName: string; role: string; active: boolean; totpEnabled: boolean; twoStepMethod?: "APP" | "EMAIL" | null; ownerBlocked?: boolean; mustChangePassword: boolean; lastSignInAt: string | null }
interface School { name: string; code: string; active: boolean; students: number; staff: number; activePeople: number; visitorsEnabled: boolean; visitorDailyCap: number; visitorPassesToday: number; pendingStaffExceptions: number; itAdmins: ItAdmin[]; registrationEnabled?: boolean; registrationBlocked?: boolean; pendingRegistrations?: number }
interface Overview { school: School; dashboardUrl: string }
interface Credentials { emailSent: boolean; temporaryPassword: string | null }
interface Person { id: string; fullName: string; groupName: string; category: string; effectiveStatus: string; identifiers: { raw: string }[] }

/** Super-admin support for a school tenant: link it, manage its IT accounts, and look people up. */
export function SchoolAdminPanel({ tenantId }: { tenantId: number }) {
  const base = `/api/admin/tenants/${tenantId}/school`;
  const overview = useApi<Overview>(base);
  const toast = useToast();
  const [secret, setSecret] = useState<string | null>(null);
  const credentials = (who: string, result: Credentials) => {
    if (result.emailSent) toast("success", `${who}: login details were emailed.`);
    else setSecret(`${who}: email isn't configured. Temporary password (shown once): ${result.temporaryPassword}`);
  };
  const run = async (action: () => Promise<unknown>, done: string) => {
    try { await action(); toast("success", done); overview.load(); }
    catch (err) { toast("error", message(err)); }
  };

  if (overview.error && /not a school/i.test(overview.error)) return <LinkSchool base={base} onLinked={(c) => { credentials("School IT person", c); overview.load(); }} />;
  if (overview.error) return <ErrorNotice text={`Couldn't load the school: ${overview.error}`} />;
  const school = overview.data?.school;
  if (!school) return <Card><p className="py-8 text-center text-sm text-[var(--fg-muted)]">Loading school…</p></Card>;

  return (
    <div className="space-y-6">
      {secret && <Notice text={secret} />}
      <Card title={school.name} description={`School code ${school.code}. Students and staff are managed by the school's IT person.`}
        action={<Badge tone={school.active ? "green" : "amber"} dot>{school.active ? "Active" : "Paused"}</Badge>} bodyClassName="px-5 py-1">
        <Field label="Students">{school.students.toLocaleString()}</Field>
        <Field label="Staff">{school.staff.toLocaleString()}</Field>
        <Field label="Visitor passes">{school.visitorsEnabled ? `${school.visitorPassesToday} of ${school.visitorDailyCap} today` : "Switched off by the owner"}</Field>
        <Field label="Staff exceptions waiting">{school.pendingStaffExceptions}</Field>
        <Field label="Student self-registration">{school.registrationBlocked ? "Forced off by you" : school.registrationEnabled ? `On · ${school.pendingRegistrations ?? 0} waiting for IT approval` : "Off (the school's IT person can turn it on)"}</Field>
        <div className="flex flex-wrap justify-end gap-2 py-4">
          <button className={buttonGhost} onClick={() => run(() => api.patch(base, { registrationBlocked: !school.registrationBlocked }), school.registrationBlocked ? "The school's IT person can use self-registration again." : "Student self-registration is forced off for this school.")}>
            {school.registrationBlocked ? "Allow self-registration" : "Force self-registration off"}
          </button>
          <button className={buttonGhost} onClick={() => run(() => api.patch(base, { active: !school.active }), school.active ? "School paused: IT and student sign-in stopped." : "School reactivated.")}>
            {school.active ? "Pause school sign-in" : "Reactivate school"}
          </button>
        </div>
      </Card>

      <Card title="School IT accounts" description="People who manage students in the school dashboard" bodyClassName="">
        <ul className="divide-y divide-[var(--border)]">
          {school.itAdmins.map((admin) => (
            <li key={admin.id} className="flex flex-wrap items-center justify-between gap-3 px-5 py-3">
              <span className="min-w-0"><span className="block font-medium text-[var(--fg)]">{admin.fullName}</span>
                <span className="block text-xs text-[var(--fg-muted)]">{admin.email} · {humanize(admin.role)}{admin.mustChangePassword ? " · temporary password" : ""}{admin.twoStepMethod === "EMAIL" ? " · email codes" : admin.twoStepMethod === "APP" ? " · authenticator" : " · two-step off"}{admin.ownerBlocked ? " · blocked by owner" : ""}</span></span>
              <span className="flex flex-wrap gap-2">
                {!admin.active && <Badge tone="slate">Disabled</Badge>}
                <button className={buttonGhost} onClick={async () => { try { credentials(admin.fullName, (await api.post<Credentials>(`${base}/admins/${admin.id}/reset-password`)).data); overview.load(); } catch (err) { toast("error", message(err)); } }}><Key className="h-4 w-4" />Reset password</button>
                {(admin.twoStepMethod ?? (admin.totpEnabled ? "APP" : null)) && <button className={buttonGhost} onClick={() => run(() => api.post(`${base}/admins/${admin.id}/reset-totp`), `Two-step sign-in is off for ${admin.fullName}; they can turn it on again in Security.`)}>Reset two-step</button>}
                <button className={buttonGhost} onClick={() => run(() => api.patch(`${base}/admins/${admin.id}`, { active: !admin.active }), admin.active ? `${admin.fullName} disabled.` : `${admin.fullName} enabled.`)}>{admin.active ? "Disable" : "Enable"}</button>
              </span>
            </li>
          ))}
        </ul>
        <AddItAdmin base={base} onAdded={(who, c) => { credentials(who, c); overview.load(); }} />
      </Card>

      <PeopleLookup base={base} />
      {overview.data?.dashboardUrl && <p className="text-xs text-[var(--fg-muted)]">School dashboard: <a className="text-[var(--accent-text)] hover:underline" href={overview.data.dashboardUrl} target="_blank" rel="noopener noreferrer">{overview.data.dashboardUrl}</a></p>}
    </div>
  );
}

function LinkSchool({ base, onLinked }: { base: string; onLinked: (c: Credentials) => void }) {
  const toast = useToast();
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const submit = async (e: React.FormEvent) => {
    e.preventDefault(); setBusy(true);
    try { onLinked((await api.post<Credentials>(base, { itAdminName: name.trim(), itAdminEmail: email.trim() })).data); }
    catch (err) { toast("error", message(err)); }
    finally { setBusy(false); }
  };
  return (
    <Card title="Not a school" description="Turn this business into a school: its portal gains student and staff sign-in, and its IT person gets the school dashboard.">
      <form onSubmit={submit} className="grid gap-4 sm:grid-cols-2">
        <div><label htmlFor="link-it-name" className={label}>School IT person&apos;s name</label><input id="link-it-name" required maxLength={200} value={name} onChange={(e) => setName(e.target.value)} className={field} /></div>
        <div><label htmlFor="link-it-email" className={label}>School IT person&apos;s email</label><input id="link-it-email" type="email" required maxLength={254} value={email} onChange={(e) => setEmail(e.target.value)} className={field} /></div>
        <div className="sm:col-span-2 flex justify-end"><button type="submit" disabled={busy} className={button}><GraduationCap className="h-4 w-4" />{busy ? "Linking…" : "Make this a school"}</button></div>
      </form>
    </Card>
  );
}

function AddItAdmin({ base, onAdded }: { base: string; onAdded: (who: string, c: Credentials) => void }) {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [role, setRole] = useState("ADMIN");
  const [busy, setBusy] = useState(false);
  if (!open) return <div className="border-t border-[var(--border)] px-5 py-3"><button className={buttonGhost} onClick={() => setOpen(true)}><UserPlus className="h-4 w-4" />Add IT account</button></div>;
  const submit = async (e: React.FormEvent) => {
    e.preventDefault(); setBusy(true);
    try { onAdded(name.trim(), (await api.post<Credentials>(`${base}/admins`, { fullName: name.trim(), email: email.trim(), role })).data); setOpen(false); setName(""); setEmail(""); }
    catch (err) { toast("error", message(err)); }
    finally { setBusy(false); }
  };
  return (
    <form onSubmit={submit} className="grid gap-3 border-t border-[var(--border)] px-5 py-4 sm:grid-cols-3">
      <div><label htmlFor="it-name" className={label}>Name</label><input id="it-name" required maxLength={200} value={name} onChange={(e) => setName(e.target.value)} className={field} /></div>
      <div><label htmlFor="it-email" className={label}>Email</label><input id="it-email" type="email" required maxLength={254} value={email} onChange={(e) => setEmail(e.target.value)} className={field} /></div>
      <div><label htmlFor="it-role" className={label}>Role</label><select id="it-role" value={role} onChange={(e) => setRole(e.target.value)} className={field}><option value="ADMIN">Admin (full control)</option><option value="HELPDESK">Helpdesk (PINs and unlocks)</option></select></div>
      <div className="sm:col-span-3 flex justify-end gap-2"><button type="button" className={buttonGhost} onClick={() => setOpen(false)}>Cancel</button><button type="submit" disabled={busy} className={button}>{busy ? "Adding…" : "Add account"}</button></div>
    </form>
  );
}

function PeopleLookup({ base }: { base: string }) {
  const toast = useToast();
  const [query, setQuery] = useState("");
  const [submitted, setSubmitted] = useState<string | null>(null);
  const people = useApi<{ content: Person[]; totalElements: number }>(submitted === null ? null : `${base}/people?query=${encodeURIComponent(submitted)}`);
  return (
    <Card title="Find a student or staff member" description="For support calls: look someone up by name or ID number and unlock their sign-in." bodyClassName="">
      <form className="flex gap-2 px-5 py-4" onSubmit={(e) => { e.preventDefault(); setSubmitted(query.trim()); }}>
        <input aria-label="Name or ID number" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Name or ID number" maxLength={128} className={`${field} !mt-0`} />
        <button type="submit" className={button}><MagnifyingGlass className="h-4 w-4" />Search</button>
      </form>
      {people.error && <p className="px-5 pb-4 text-sm text-red-600">{people.error}</p>}
      {people.data && (people.data.content.length === 0 ? <p className="px-5 pb-4 text-sm text-[var(--fg-muted)]">Nobody matches.</p> :
        <ul className="divide-y divide-[var(--border)] border-t border-[var(--border)]">
          {people.data.content.map((person) => (
            <li key={person.id} className="flex flex-wrap items-center justify-between gap-3 px-5 py-3">
              <span><span className="block font-medium text-[var(--fg)]">{person.fullName}</span>
                <span className="block text-xs text-[var(--fg-muted)]">{person.identifiers.map((i) => i.raw).join(", ")} · {person.groupName} · {humanize(person.category)}</span></span>
              <span className="flex items-center gap-2"><Badge tone={person.effectiveStatus === "ACTIVE" ? "green" : person.effectiveStatus === "LOCKED" ? "amber" : "slate"} dot>{humanize(person.effectiveStatus)}</Badge>
                {person.effectiveStatus === "LOCKED" && <button className={buttonGhost} onClick={async () => { try { await api.post(`${base}/people/${encodeURIComponent(person.id)}/unlock`); toast("success", `${person.fullName} can sign in again.`); people.load(); } catch (err) { toast("error", message(err)); } }}>Unlock</button>}</span>
            </li>
          ))}
        </ul>)}
    </Card>
  );
}
