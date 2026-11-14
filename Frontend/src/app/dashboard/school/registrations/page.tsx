"use client";

import React, { useEffect, useState } from "react";
import api from "@/lib/api";
import { CheckCircle, Copy, NotePencil, XCircle } from "@/components/icons";
import {
  Card, ErrorNotice, PageContainer, PageHeader, Segmented, Table,
  button, buttonGhost, field, label, message, relativeTime, useApi, useNow,
} from "@/components/BillingUI";
import { useToast } from "@/components/ui/Toast";

interface Registration {
  id: string; fullName: string; registrationNumber: string; email: string; courseEnds: string | null;
  accessUntil: string | null; source: string | null; status: string; submittedAt: string;
  decidedAt: string | null; decidedBy: string | null; personId: string | null;
}
interface RegistrationDecision {
  approved: number; rejected: number;
  failed: { id: string; fullName: string | null; code: string; message: string }[];
  unsent: { id: string; name: string; identifier: string; pin: string }[];
}
interface Page<T> { content: T[]; totalElements: number; totalPages: number; page: number }

type Status = "PENDING" | "APPROVED" | "REJECTED";

/**
 * Student self-registrations the school's IT person normally approves. The owner sees the same
 * queue here and can approve or reject when IT is unavailable; decisions are recorded under
 * the owner's name, exactly as the IT dashboard records them under the IT person's.
 */
export default function SchoolRegistrationsPage() {
  const toast = useToast();
  const [status, setStatus] = useState<Status>("PENDING");
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [q, setQ] = useState("");
  useEffect(() => {
    const t = setTimeout(() => { setQ(search.trim()); setPage(0); }, 300);
    return () => clearTimeout(t);
  }, [search]);
  const registrations = useApi<Page<Registration>>(`/api/school/registrations?status=${status}&page=${page}&q=${encodeURIComponent(q)}`);
  const overview = useApi<{ school?: { registrationPrefixes?: string[] } }>("/api/school");
  const storedPrefixes = overview.data?.school?.registrationPrefixes ?? [];
  const [prefixInput, setPrefixInput] = useState<string | null>(null);
  const [prefixBusy, setPrefixBusy] = useState(false);
  const prefixValue = prefixInput ?? storedPrefixes.join(", ");
  const savePrefixes = async () => {
    setPrefixBusy(true);
    try {
      await api.put("/api/school/settings", { registrationPrefixes: prefixValue.split(",").map((p) => p.trim()).filter(Boolean) });
      toast("success", "Registration number prefixes saved.");
      setPrefixInput(null);
      overview.load();
    } catch (err) {
      toast("error", message(err));
    } finally {
      setPrefixBusy(false);
    }
  };
  const [busy, setBusy] = useState<string | null>(null);
  const [unsent, setUnsent] = useState<{ id: string; name: string; identifier: string; pin: string }[]>([]);
  const [editing, setEditing] = useState<Registration | null>(null);
  const [editName, setEditName] = useState("");
  const [editRegNumber, setEditRegNumber] = useState("");
  const [editCourseEnds, setEditCourseEnds] = useState("");
  const [editBusy, setEditBusy] = useState(false);
  const now = useNow();

  const startEdit = (reg: Registration) => {
    setEditing(reg);
    setEditName(reg.fullName);
    setEditRegNumber(reg.registrationNumber);
    setEditCourseEnds(reg.courseEnds || "");
  };
  const saveEdit = async () => {
    if (!editing) return;
    const changes: { fullName?: string; registrationNumber?: string; accessUntil?: string } = {};
    if (editName.trim() && editName.trim() !== editing.fullName) changes.fullName = editName.trim();
    if (editRegNumber.trim() && editRegNumber.trim() !== editing.registrationNumber) changes.registrationNumber = editRegNumber.trim();
    if (editCourseEnds && editCourseEnds !== editing.courseEnds) {
      const [y, m] = editCourseEnds.split("-").map(Number);
      changes.accessUntil = new Date(Date.UTC(y, m, 0)).toISOString().slice(0, 10); // last day of that month
    }
    if (!changes.fullName && !changes.registrationNumber && !changes.accessUntil) { setEditing(null); return; }
    setEditBusy(true);
    try {
      await api.patch(`/api/school/registrations/${editing.id}`, changes);
      toast("success", "Registration updated.");
      setEditing(null);
      registrations.load();
    } catch (err) {
      toast("error", message(err));
    } finally {
      setEditBusy(false);
    }
  };

  const decide = async (reg: Registration, approve: boolean, reason?: string) => {
    setBusy(reg.id);
    try {
      const { data } = await api.post<RegistrationDecision>("/api/school/registrations/decision", { ids: [reg.id], approve, ...(reason ? { reason } : {}) });
      const decided = approve ? data.approved : data.rejected;
      if (decided > 0) toast("success", approve ? `${reg.fullName} approved — their PIN is on its way by email.` : `${reg.fullName} rejected.`);
      for (const f of data.failed ?? []) toast("error", `${f.fullName || "That registration"}: ${f.message}`);
      if (data.unsent?.length) setUnsent((prev) => [...prev, ...data.unsent]);
      setRejecting(null);
      registrations.load();
    } catch (err) {
      toast("error", message(err));
    } finally {
      setBusy(null);
    }
  };
  const [rejecting, setRejecting] = useState<Registration | null>(null);
  const [rejectReason, setRejectReason] = useState("");
  const startReject = (reg: Registration) => { setRejecting(reg); setRejectReason(""); };

  const [resending, setResending] = useState<Registration | null>(null);
  const [resendEmail, setResendEmail] = useState("");
  const resend = async (reg: Registration) => {
    setBusy(reg.id);
    try {
      const corrected = resendEmail.trim() !== reg.email ? resendEmail.trim() : "";
      const { data } = await api.post<{ email: string; delivered: boolean; slip: { id: string; name: string; identifier: string; pin: string } | null }>(
        `/api/school/registrations/${reg.id}/resend`, corrected ? { email: corrected } : {});
      if (data.delivered) {
        toast("success", `A fresh PIN was emailed to ${data.email}.`);
      } else {
        if (data.slip) setUnsent((prev) => [...prev, data.slip!]);
        toast("error", `Couldn't deliver to ${data.email} — the new PIN is listed at the top to hand out.`);
      }
      setResending(null);
      registrations.load();
    } catch (err) {
      toast("error", message(err));
    } finally {
      setBusy(null);
    }
  };

  const data = registrations.data;
  const switchStatus = (v: Status) => { setStatus(v); setPage(0); };

  return (
    <PageContainer>
      <PageHeader
        title="School registrations"
        description="Students who signed themselves up on the school WiFi. Normally the IT office approves these — you can stand in when they are busy."
        action={
          <Segmented
            label="Registration status"
            value={status}
            onChange={(v) => switchStatus(v as Status)}
            options={[
              { key: "PENDING", label: "Waiting", count: status === "PENDING" ? data?.totalElements : undefined },
              { key: "APPROVED", label: "Approved" },
              { key: "REJECTED", label: "Rejected" },
            ]}
          />
        }
      />
      {registrations.error && <ErrorNotice text={`Couldn't load registrations: ${registrations.error}`} />}

      {unsent.length > 0 && (
        <Card title="PINs to hand out" description="These students were approved but their PIN email could not be delivered — give them the PIN yourself." bodyClassName="">
          <ul className="divide-y divide-[var(--border-soft)]">
            {unsent.map((u) => (
              <li key={u.id} className="flex items-center justify-between gap-3 px-5 py-3">
                <span className="min-w-0 truncate text-sm text-[var(--fg)]">{u.name} <span className="text-xs text-[var(--fg-muted)]">({u.identifier})</span></span>
                <span className="flex shrink-0 items-center gap-2">
                  <code className="rounded bg-[var(--surface-2)] px-2 py-1 font-mono text-sm text-[var(--fg)]">{u.pin}</code>
                  <button type="button" className={buttonGhost} onClick={() => { navigator.clipboard?.writeText(u.pin); toast("success", "Copied."); }}>
                    <Copy className="h-4 w-4" />
                    Copy
                  </button>
                </span>
              </li>
            ))}
          </ul>
        </Card>
      )}

      <Card
        title="Registration number prefixes"
        description="Students' registration numbers must start with one of these (from their admission letters). Numbers with a different prefix are turned away at the form with a clear message. Leave empty to accept every number."
      >
        <div className="flex flex-wrap items-center gap-2">
          <input
            aria-label="Registration number prefixes"
            placeholder="SNAHP, CPH, MER, OPM, DOT"
            className={`${field} font-mono`}
            value={prefixValue}
            disabled={prefixBusy}
            onChange={(e) => setPrefixInput(e.target.value)}
          />
          <button type="button" className={button} disabled={prefixBusy || prefixValue === storedPrefixes.join(", ")} onClick={savePrefixes}>
            {prefixBusy ? "Saving…" : "Save prefixes"}
          </button>
        </div>
      </Card>

      <Card
        title={status === "PENDING" ? "Waiting for a decision" : status === "APPROVED" ? "Approved" : "Rejected"}
        description={
          status === "PENDING"
            ? "Approving creates the student's account and emails them their one-time PIN. Rejecting asks for a reason, which is emailed to them."
            : status === "APPROVED"
              ? "Students let in, with who approved them."
              : "Registrations that were turned down, with who decided."
        }
        bodyClassName=""
      >
        <div className="border-b border-[var(--border-soft)] px-5 py-3">
          <input
            aria-label="Search registrations"
            placeholder="Search name, registration number or email…"
            className={`${field} max-w-sm`}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
        <Table
          rows={data?.content ?? []}
          loading={registrations.loading && !data}
          rowKey={(r) => r.id}
          paginated={false}
          searchable={false}
          emptyIcon={<CheckCircle />}
          emptyTitle={status === "PENDING" ? "Nothing waiting for a decision" : `No ${status.toLowerCase()} registrations`}
          emptyDescription={status === "PENDING" ? "New student sign-ups appear here as they come in." : undefined}
          columns={[
            {
              label: "Student",
              render: (r) => editing?.id === r.id ? (
                <div className="min-w-0">
                  <input className={field} value={editName} onChange={(e) => setEditName(e.target.value)} maxLength={200} placeholder="Full name" />
                  <div className="mt-1 truncate text-xs text-[var(--fg-muted)]">{r.email}</div>
                </div>
              ) : resending?.id === r.id ? (
                <div className="min-w-0">
                  <div className="truncate font-medium text-[var(--fg)]">{r.fullName}</div>
                  <input className={`${field} mt-1`} type="email" value={resendEmail} onChange={(e) => setResendEmail(e.target.value)} maxLength={254} placeholder="Correct email address" autoFocus />
                </div>
              ) : (
                <div className="min-w-0">
                  <div className="truncate font-medium text-[var(--fg)]">{r.fullName}</div>
                  <div className="truncate text-xs text-[var(--fg-muted)]">{r.email}</div>
                </div>
              ),
            },
            {
              label: "Registration no.",
              render: (r) => editing?.id === r.id
                ? <input className={`${field} font-mono`} value={editRegNumber} onChange={(e) => setEditRegNumber(e.target.value)} maxLength={128} />
                : rejecting?.id === r.id
                  ? <input className={field} value={rejectReason} onChange={(e) => setRejectReason(e.target.value)} maxLength={500} placeholder="Reason, e.g. not a student here" autoFocus />
                  : <span className="font-mono text-xs">{r.registrationNumber}</span>,
            },
            {
              label: "Course ends",
              render: (r) => editing?.id === r.id
                ? <input className={field} type="month" value={editCourseEnds} onChange={(e) => setEditCourseEnds(e.target.value)} />
                : <span className="text-xs">{r.courseEnds || "—"}</span>,
            },
            { label: "Submitted", render: (r) => <span className="text-xs text-[var(--fg-muted)]">{relativeTime(r.submittedAt, now)}</span> },
            ...(status === "PENDING" ? [] : [{
              label: "Decided",
              render: (r: Registration) => (
                <div className="text-xs">
                  <div>{r.decidedAt ? relativeTime(r.decidedAt, now) : "—"}</div>
                  {r.decidedBy && <div className="text-[var(--fg-muted)]">by {r.decidedBy}</div>}
                </div>
              ),
            }]),
            ...(status === "PENDING" ? [{
              label: "",
              render: (r: Registration) => editing?.id === r.id ? (
                <div className="flex items-center justify-end gap-2">
                  <button type="button" disabled={editBusy} onClick={saveEdit} className={button}>{editBusy ? "Saving…" : "Save"}</button>
                  <button type="button" className={buttonGhost} onClick={() => setEditing(null)}>Cancel</button>
                </div>
              ) : rejecting?.id === r.id ? (
                <div className="flex items-center justify-end gap-2">
                  <button type="button" disabled={busy === r.id || !rejectReason.trim()} onClick={() => decide(r, false, rejectReason.trim())} className={button}>{busy === r.id ? "Rejecting…" : "Reject"}</button>
                  <button type="button" className={buttonGhost} onClick={() => setRejecting(null)}>Cancel</button>
                </div>
              ) : (
                <div className="flex items-center justify-end gap-2">
                  <button type="button" disabled={busy === r.id} onClick={() => startEdit(r)} className={buttonGhost}>
                    <NotePencil className="h-4 w-4" />
                    Edit
                  </button>
                  <button type="button" disabled={busy === r.id} onClick={() => decide(r, true)} className={buttonGhost}>
                    <CheckCircle className="h-4 w-4" />
                    Approve
                  </button>
                  <button type="button" disabled={busy === r.id} onClick={() => startReject(r)} className={buttonGhost}>
                    <XCircle className="h-4 w-4" />
                    Reject
                  </button>
                </div>
              ),
            }] : []),
            ...(status === "APPROVED" ? [{
              label: "",
              render: (r: Registration) => resending?.id === r.id ? (
                <div className="flex items-center justify-end gap-2">
                  <button type="button" disabled={busy === r.id || !resendEmail.trim()} onClick={() => resend(r)} className={button}>{busy === r.id ? "Sending…" : "Send"}</button>
                  <button type="button" className={buttonGhost} onClick={() => setResending(null)}>Cancel</button>
                </div>
              ) : (
                <div className="flex items-center justify-end gap-2">
                  <button type="button" disabled={busy === r.id} onClick={() => { setResending(r); setResendEmail(r.email); }} className={buttonGhost}>
                    <NotePencil className="h-4 w-4" />
                    Fix email / resend
                  </button>
                </div>
              ),
            }] : []),
          ]}
        />
        {data && data.totalPages > 1 && (
          <div className="flex items-center justify-between border-t border-[var(--border-soft)] px-5 py-3 text-xs text-[var(--fg-muted)]">
            <span>Page {data.page + 1} of {data.totalPages} · {data.totalElements.toLocaleString()} registrations</span>
            <div className="flex gap-2">
              <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)} className={buttonGhost}>Previous</button>
              <button type="button" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)} className={buttonGhost}>Next</button>
            </div>
          </div>
        )}
      </Card>
    </PageContainer>
  );
}
