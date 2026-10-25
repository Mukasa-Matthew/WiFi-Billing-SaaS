"use client";

import Link from "next/link";
import React, { useMemo, useState } from "react";
import api from "@/lib/api";
import { Dialog } from "@/components/ui/Dialog";
import { ActionMenu } from "@/components/ui/ActionMenu";
import { useToast } from "@/components/ui/Toast";
import { accountTone, subscriptionTone, useTenantActions } from "@/components/admin/useTenantActions";
import { Building2, UserPlus, WarningCircle } from "@/components/icons";
import { summarizeRouters } from "@/lib/routerState";
import {
  Badge, ErrorNotice, PageHeader, Segmented, StatusDot, Table, button, buttonGhost, field, humanize, label,
  message, relativeTime, ugx, useNow, useResource,
} from "@/components/BillingUI";

interface TenantSummary {
  id: number; name: string; email: string; phone: string; role: string; status: string;
  routerCount: number; voucherCount: number; createdAt: string;
}
interface FleetRouter { tenantId: number; status: string; lastSeen: string | null }
interface Subscription { id: number; tenantId: number; monthlyFee: number; nextRenewalDate: string; status: string; displayStatus: string; outstanding: number }

type StatusFilter = "ALL" | "ACTIVE" | "SUSPENDED" | "DISABLED";

export default function AdminTenantsPage() {
  const tenants = useResource<TenantSummary>("/api/admin/tenants");
  const network = useResource<FleetRouter>("/api/admin/noc/routers");
  const subs = useResource<Subscription>("/api/admin/subscriptions");
  const actions = useTenantActions(tenants.load);
  const now = useNow();
  const [filter, setFilter] = useState<StatusFilter>("ALL");
  const [creating, setCreating] = useState(false);

  const netBy = useMemo(() => {
    const groups = new Map<number, FleetRouter[]>();
    network.data.forEach((r) => groups.set(r.tenantId, [...(groups.get(r.tenantId) ?? []), r]));
    return new Map([...groups].map(([id, rs]) => [id, summarizeRouters(rs, now)]));
  }, [network.data, now]);
  const subBy = useMemo(() => new Map(subs.data.map((s) => [s.tenantId, s])), [subs.data]);
  const sorted = useMemo(() => [...tenants.data].sort((a, b) => a.name.localeCompare(b.name)), [tenants.data]);
  const isDisabled = (t: TenantSummary) => t.status !== "ACTIVE" && t.status !== "SUSPENDED";
  const rows = sorted.filter((t) => filter === "ALL" || (filter === "DISABLED" ? isDisabled(t) : t.status === filter));
  const count = (f: StatusFilter) => f === "ALL" ? tenants.data.length : tenants.data.filter((t) => f === "DISABLED" ? isDisabled(t) : t.status === f).length;

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      {actions.dialog}
      <PageHeader
        title="Tenants"
        description="Every hotspot business on the platform: account status, subscription standing and router health."
        action={<button onClick={() => setCreating(true)} className={button}><UserPlus className="h-4 w-4" />Register operator</button>}
      />
      {actions.noticeBanner}
      {tenants.error && <ErrorNotice text={`Couldn't load tenants: ${tenants.error}`} />}
      {!tenants.error && (network.error || subs.error) && <ErrorNotice text={`Some columns are unavailable: ${[network.error && "router health", subs.error && "subscriptions"].filter(Boolean).join(" and ")} failed to load.`} />}

      <Table
        rows={rows} loading={tenants.loading} rowKey={(t) => t.id}
        searchPlaceholder="Search name, email or phone…"
        emptyIcon={<Building2 />}
        emptyTitle={tenants.data.length ? "No tenants match" : "No tenants yet"}
        emptyDescription={tenants.data.length ? "Try another status or search." : "Register the first hotspot operator to get started."}
        toolbar={<Segmented label="Account status" value={filter} onChange={setFilter} options={(["ALL", "ACTIVE", "SUSPENDED", "DISABLED"] as StatusFilter[]).map((k) => ({ key: k, label: k === "ALL" ? "All" : humanize(k), count: tenants.loading ? undefined : count(k) }))} />}
        columns={[
          { label: "Business", render: (t) => (
            <Link href={`/admin/tenants/${t.id}`} className="group block min-w-0">
              <span className="block font-medium text-[var(--fg)] group-hover:text-[var(--accent-text)] group-hover:underline">{t.name}</span>
              <span className="block text-xs text-[var(--fg-muted)]">{t.email}</span>
            </Link>) },
          { label: "Account", render: (t) => <Badge tone={accountTone(t.status)} dot>{humanize(t.status)}</Badge> },
          { label: "Subscription", hideBelow: "lg", render: (t) => {
            const s = subBy.get(t.id);
            if (subs.error) return <span className="text-xs text-[var(--fg-faint)]">—</span>;
            if (!s) return <span className="text-xs text-[var(--fg-faint)]">{subs.loading ? "…" : "None"}</span>;
            return <span className="flex flex-col items-start gap-0.5"><Badge tone={subscriptionTone(s.displayStatus)}>{humanize(s.displayStatus)}</Badge><span className="num text-[11.5px] text-[var(--fg-muted)]">{ugx(s.monthlyFee)}/mo{s.outstanding > 0 ? ` · ${ugx(s.outstanding)} due` : ""}</span></span>;
          } },
          { label: "Routers", render: (t) => {
            if (!t.routerCount) return <span className="text-xs text-[var(--fg-faint)]">None linked</span>;
            const n = netBy.get(t.id);
            if (!n) return <span className="num text-[13px]">{network.loading ? "…" : t.routerCount}</span>;
            return <span className="flex flex-col gap-0.5"><StatusDot tone={n.tone}><span className="num">{n.label}</span></StatusDot>{n.tone !== "green" && n.detail && <span className="pl-4 text-[11.5px] text-[var(--fg-muted)]">{n.detail}</span>}</span>;
          } },
          { label: "Active vouchers", render: (t) => t.voucherCount.toLocaleString(), align: "right", hideBelow: "md" },
          { label: "Joined", render: (t) => <span className="text-xs text-[var(--fg-muted)]" title={new Date(t.createdAt).toLocaleString()}>{relativeTime(t.createdAt, now)}</span>, align: "right", hideBelow: "sm" },
          { label: "", render: (t) => <ActionMenu ariaLabel={`Actions for ${t.name}`}>{actions.menuItems(t)}</ActionMenu>, className: "w-10 !px-2 text-right" },
        ]}
      />

      {creating && <CreateOperatorDialog onClose={() => setCreating(false)} onCreated={(text) => { actions.setNotice(text); void tenants.load(); }} />}
    </div>
  );
}

function CreateOperatorDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (notice: { text: string; secret?: string }) => void }) {
  const toast = useToast();
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const close = () => { if (!busy) onClose(); };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const res = await api.post<{ temporaryPassword: string | null; welcomeEmailSent: boolean }>("/api/admin/tenants", { name: name.trim(), email: email.trim(), phone: phone.trim() });
      onClose();
      if (res.data.welcomeEmailSent) toast("success", "Operator created. Their temporary login details were sent by email.");
      else onCreated({ text: "Operator created. Email isn't configured, so give them this temporary password securely. It won't be shown again.", secret: res.data.temporaryPassword ?? undefined });
    } catch (err) {
      setError(message(err) === "Request failed. Please retry." ? "Failed to create tenant." : message(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog title="Register hotspot operator" description="Create a secure dashboard account for a new business." onClose={close}>
      <p className="mb-5 rounded-lg border border-[var(--border)] bg-[var(--subtle)] p-3.5 text-xs leading-5 text-[var(--fg-muted)]">
        We generate a temporary password, email it to the operator, and ask them to change it on first sign-in.
      </p>
      {error && <p role="alert" className="mb-4 flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 p-3 text-xs text-red-700"><WarningCircle className="h-4 w-4 shrink-0" />{error}</p>}
      <form onSubmit={submit} className="space-y-4">
        <div><label htmlFor="operator-business" className={label}>Business name</label>
          <input id="operator-business" required autoFocus maxLength={120} value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Acacia Cafe & Lounge" className={field} /></div>
        <div><label htmlFor="operator-email" className={label}>Operator email</label>
          <input id="operator-email" type="email" required autoComplete="email" maxLength={120} value={email} onChange={(e) => setEmail(e.target.value)} placeholder="e.g. manager@acaciacafe.ug" className={field} /></div>
        <div><label htmlFor="operator-phone" className={label}>Contact phone number</label>
          <input id="operator-phone" type="tel" required autoComplete="tel" maxLength={30} value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="e.g. +256770000000" className={field} /></div>
        <div className="flex flex-col-reverse gap-2 border-t border-[var(--border)] pt-5 sm:flex-row sm:justify-end">
          <button type="button" onClick={close} disabled={busy} className={buttonGhost}>Cancel</button>
          <button type="submit" disabled={busy} className={button}>{busy ? "Creating account…" : <><UserPlus className="h-4 w-4" />Create operator account</>}</button>
        </div>
      </form>
    </Dialog>
  );
}
