"use client";
import { mutateCache } from "@/lib/cacheInvalidation";
import React, { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import api from "@/lib/api";
import { Plus, Lightning, Trash, NotePencil } from "@/components/icons";
import { Table, Badge, ErrorNotice, FilterTabs, PageHeader, button, buttonGhost, panel, ugx, message } from "@/components/BillingUI";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";

interface Plan { id: number; name: string; price: number; rateLimit: string; sessionTimeout: number; sharedUsers: number; availability: string; featured: boolean; routerName?: string | null }

const AVAILABILITY: Record<string, { label: string; hint: string }> = {
  ACTIVE: { label: "On sale", hint: "Shown on the portal" },
  HIDDEN: { label: "Vouchers only", hint: "Not on the portal; sell as vouchers" },
  DISABLED: { label: "Off", hint: "Can’t be bought" },
};

function duration(seconds: number) {
  const [size, unit] = seconds % 86400 === 0 ? [86400, "day"] : seconds % 3600 === 0 ? [3600, "hour"] : [60, "minute"];
  const n = seconds / size;
  return `${n} ${unit}${n === 1 ? "" : "s"}`;
}
/** rateLimit is MikroTik "upload/download", e.g. "2M/5M". */
function speed(rateLimit?: string) {
  const [up, down] = (rateLimit || "").split("/");
  return up && down ? `${down} down · ${up} up` : "—";
}

export default function PackagesPage() {
  const [plans, setPlans] = useState<Plan[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [status, setStatus] = useState("ALL");
  const [templates, setTemplates] = useState(false);
  const [saving, setSaving] = useState<number | null>(null);
  const { confirm, dialog } = useConfirmDialog();
  const toast = useToast();

  const load = useCallback(async () => {
    try { setPlans((await api.get<Plan[]>("/api/packages")).data); setError(""); }
    catch (e) { setError(message(e)); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { const t = window.setTimeout(load, 0); return () => window.clearTimeout(t); }, [load]);

  const rows = plans.filter((p) => status === "ALL" || p.availability === status);

  const change = async (plan: Plan, availability: string) => {
    setSaving(plan.id);
    try { await api.patch(`/api/packages/${plan.id}/availability`, { availability }); mutateCache.packageChanged(); await load(); }
    catch (e) { toast("error", message(e)); }
    finally { setSaving(null); }
  };

  const remove = (plan: Plan) => confirm({
    title: `Delete “${plan.name}”?`,
    body: "Customers can no longer buy it. Vouchers already sold keep working.",
    confirmLabel: "Delete plan",
    onConfirm: async () => {
      try { await api.delete(`/api/packages/${plan.id}`); mutateCache.packageChanged(); toast("success", "Plan deleted."); await load(); }
      catch (e) { toast("error", message(e)); }
    },
  });

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      {dialog}
      <PageHeader title="Plans" description="The internet packages you sell." action={<>
        <button onClick={() => setTemplates((v) => !v)} className={buttonGhost} aria-expanded={templates}><Lightning size={14} /> Templates</button>
        <Link href="/dashboard/packages/new" className={button}><Plus size={14} /> New plan</Link>
      </>} />

      {templates && (
        <div className="grid gap-3 sm:grid-cols-4">
          {["hourly", "daily", "weekly", "monthly"].map((t) => (
            <Link key={t} href={`/dashboard/packages/new?template=${t}`} className={`${panel} p-4 transition hover:border-[var(--border-strong)]`}>
              <span className="text-sm font-semibold capitalize text-[var(--fg)]">{t} plan</span>
              <span className="mt-0.5 block text-xs text-[var(--fg-muted)]">Start from a template</span>
            </Link>
          ))}
        </div>
      )}

      <ErrorNotice text={error} />

      <Table
        loading={loading}
        rows={rows}
        rowKey={(p: Plan) => p.id}
        searchPlaceholder="Search plans"
        toolbar={<FilterTabs value={status} onChange={setStatus} loading={loading} options={[
          { key: "ALL", label: "All", count: plans.length },
          ...Object.entries(AVAILABILITY).map(([key, a]) => ({ key, label: a.label, count: plans.filter((p) => p.availability === key).length })),
        ]} />}
        emptyTitle={plans.length ? "No plans match" : "No plans yet"}
        emptyDescription={plans.length ? "Try a different filter or search." : "Create your first plan so customers can buy internet."}
        columns={[
          { label: "Plan", render: (p: Plan) => (
            <span>
              <span className="font-medium text-[var(--fg)]">{p.name}</span>
              {p.featured && <span className="ml-2"><Badge tone="amber">Popular</Badge></span>}
              {p.routerName && <span className="block text-xs text-[var(--fg-faint)]">{p.routerName} only</span>}
              {/* Phones drop the Price, Lasts and Status columns; summarise them here. */}
              <span className="mt-0.5 block text-xs text-[var(--fg-muted)] sm:hidden"><span className="num font-semibold text-[var(--fg)]">{ugx(p.price)}</span> · {duration(p.sessionTimeout)} · {AVAILABILITY[p.availability]?.label ?? p.availability}</span>
            </span>
          )},
          { label: "Price", align: "right", hideBelow: "sm", render: (p: Plan) => <span className="num font-semibold text-[var(--fg)]">{ugx(p.price)}</span> },
          { label: "Lasts", hideBelow: "sm", render: (p: Plan) => <span className="text-[var(--fg-body)]">{duration(p.sessionTimeout)}</span> },
          { label: "Speed", hideBelow: "md", render: (p: Plan) => <span className="whitespace-nowrap text-[var(--fg-muted)]">{speed(p.rateLimit)}</span> },
          { label: "Devices", hideBelow: "lg", align: "right", render: (p: Plan) => <span className="num text-[var(--fg-muted)]">{p.sharedUsers}</span> },
          { label: "Status", hideBelow: "sm", render: (p: Plan) => (
            <select aria-label={`Status of ${p.name}`} value={p.availability} disabled={saving === p.id} title={AVAILABILITY[p.availability]?.hint}
              onChange={(e) => change(p, e.target.value)}
              className="min-h-9 rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] px-2 text-xs font-medium text-[var(--fg)] disabled:opacity-50">
              {Object.entries(AVAILABILITY).map(([key, a]) => <option key={key} value={key}>{a.label}</option>)}
            </select>
          )},
          { label: "", align: "right", render: (p: Plan) => (
            <span className="inline-flex items-center gap-1">
              <Link aria-label={`Edit ${p.name}`} title="Edit" href={`/dashboard/packages/new?id=${p.id}`} className="grid h-9 w-9 place-items-center rounded-lg text-[var(--fg-muted)] transition hover:bg-[var(--fill)] hover:text-[var(--fg)]"><NotePencil size={15} /></Link>
              <button aria-label={`Delete ${p.name}`} title="Delete" disabled={saving === p.id} onClick={() => remove(p)} className="grid h-9 w-9 place-items-center rounded-lg text-[var(--fg-muted)] transition hover:bg-red-50 hover:text-red-600 disabled:opacity-40 dark:hover:bg-red-500/10"><Trash size={15} /></button>
            </span>
          )},
        ]}
      />
    </div>
  );
}
