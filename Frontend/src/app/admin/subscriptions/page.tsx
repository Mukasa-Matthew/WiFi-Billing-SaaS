"use client";

import React, { useCallback, useEffect, useState } from "react";
import api from "@/lib/api";
import { Table, Badge, Notice, panel, button, buttonGhost, field, label, hint, message, PageHeader } from "@/components/BillingUI";
import { Dialog } from "@/components/ui/Dialog";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import { DashboardLoading, BusyLabel } from "@/components/LoadingUI";
import { Plus, CheckCircle, WarningCircle, ClockCountdown, CalendarCheck, Money, Users, ShieldCheck, X } from "@/components/icons";

interface Overview {
  totalTenants: number;
  subscribed: number;
  active: number;
  trialing: number;
  grace: number;
  suspended: number;
  expiringSoon: number;
  monthRevenue: number;
  defaultFee: number;
}

interface SubscriptionRow {
  id: number;
  tenantId: number;
  businessName: string;
  planId?: number | null;
  planName: string;
  monthlyFee: number;
  startDate: string;
  nextRenewalDate: string;
  expiresAt: string;
  trialEndsAt?: string | null;
  graceUntil?: string | null;
  status: string;
  adminNote?: string | null;
}

interface PlanItem {
  id: number;
  name: string;
  price: number;
  durationUnit: string;
  durationValue: number;
  description: string;
  active: boolean;
  displayOrder: number;
}

interface InvoiceRow {
  id: number;
  periodStart: string;
  periodEnd: string;
  amountDue: number;
  status: string;
  paidAt?: string | null;
}

interface PaymentRow {
  id: number;
  amount: number;
  method: string;
  externalRef?: string | null;
  paidAt: string;
  note?: string | null;
}

interface NotificationRow {
  id: number;
  notificationType: string;
  title: string;
  message: string;
  severity: string;
  emailSent: boolean;
  createdAt: string;
}

interface BillingDetail {
  subscription: SubscriptionRow;
  invoices: InvoiceRow[];
  payments: PaymentRow[];
  notifications: NotificationRow[];
}

const TONE: Record<string, string> = {
  ACTIVE: "green",
  TRIALING: "blue",
  GRACE_PERIOD: "amber",
  SUSPENDED: "red",
  CANCELLED: "slate",
  PAID: "green",
  UNPAID: "amber",
};

const STATUS_NAME: Record<string, string> = {
  ACTIVE: "Active",
  TRIALING: "Free Trial",
  GRACE_PERIOD: "Grace Period",
  SUSPENDED: "Suspended",
  CANCELLED: "Cancelled",
};

const money = (v?: number | null) => `UGX ${(v ?? 0).toLocaleString()}`;
const d = (s?: string | null) => (s ? new Date(s).toLocaleDateString() : "—");
const dt = (s?: string | null) => (s ? new Date(s).toLocaleString() : "—");

export default function AdminSubscriptionsPage() {
  const [ov, setOv] = useState<Overview | null>(null);
  const [rows, setRows] = useState<SubscriptionRow[]>([]);
  const [plans, setPlans] = useState<PlanItem[]>([]);
  const [filterStatus, setFilterStatus] = useState<string>("ALL");
  const [search, setSearch] = useState<string>("");
  const [err, setErr] = useState("");
  const [ok, setOk] = useState("");
  const [loading, setLoading] = useState(true);

  // Dialogs
  const [activeDialog, setActiveDialog] = useState<null | "planModal" | "extendModal" | "assignPlanModal" | "billingModal">(null);
  const [selectedSub, setSelectedSub] = useState<SubscriptionRow | null>(null);
  const [billingDetail, setBillingDetail] = useState<BillingDetail | null>(null);
  const [busy, setBusy] = useState(false);

  // Plan Form
  const [editingPlan, setEditingPlan] = useState<PlanItem | null>(null);
  const [planName, setPlanName] = useState("");
  const [planPrice, setPlanPrice] = useState("");
  const [planDurationValue, setPlanDurationValue] = useState("1");
  const [planDurationUnit, setPlanDurationUnit] = useState("MONTHS");
  const [planDescription, setPlanDescription] = useState("");
  const [planActive, setPlanActive] = useState(true);

  // Extension Form
  const [extDays, setExtDays] = useState("7");
  const [extReason, setExtReason] = useState("");
  const [extComplimentary, setExtComplimentary] = useState(true);

  // Assign Plan Form
  const [assignPlanId, setAssignPlanId] = useState<number | "">("");

  const load = useCallback(async () => {
    try {
      const [o, r, p] = await Promise.all([
        api.get<Overview>("/api/admin/subscriptions/overview"),
        api.get<SubscriptionRow[]>("/api/admin/subscriptions"),
        api.get<PlanItem[]>("/api/admin/subscriptions/plans"),
      ]);
      setOv(o.data);
      setRows(r.data);
      setPlans(p.data);
    } catch (e) {
      setErr(message(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const handleOpenPlanModal = (plan?: PlanItem) => {
    if (plan) {
      setEditingPlan(plan);
      setPlanName(plan.name);
      setPlanPrice(String(plan.price));
      setPlanDurationValue(String(plan.durationValue));
      setPlanDurationUnit(plan.durationUnit);
      setPlanDescription(plan.description || "");
      setPlanActive(plan.active);
    } else {
      setEditingPlan(null);
      setPlanName("");
      setPlanPrice("");
      setPlanDurationValue("1");
      setPlanDurationUnit("MONTHS");
      setPlanDescription("");
      setPlanActive(true);
    }
    setActiveDialog("planModal");
  };

  const handleSavePlan = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr("");
    try {
      const payload = {
        name: planName.trim(),
        price: parseInt(planPrice, 10),
        durationUnit: planDurationUnit,
        durationValue: parseInt(planDurationValue, 10),
        description: planDescription.trim(),
        active: planActive,
      };

      if (editingPlan) {
        await api.put(`/api/admin/subscriptions/plans/${editingPlan.id}`, payload);
        setOk(`Plan ${planName} updated successfully.`);
      } else {
        await api.post("/api/admin/subscriptions/plans", payload);
        setOk(`Plan ${planName} created successfully.`);
      }
      setActiveDialog(null);
      await load();
    } catch (e) {
      setErr(message(e));
    } finally {
      setBusy(false);
    }
  };

  const handleOpenExtend = (sub: SubscriptionRow) => {
    setSelectedSub(sub);
    setExtDays("7");
    setExtReason("");
    setExtComplimentary(true);
    setActiveDialog("extendModal");
  };

  const handleSaveExtend = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedSub) return;
    setBusy(true);
    try {
      await api.post(`/api/admin/subscriptions/${selectedSub.id}/extend`, {
        extensionDays: parseInt(extDays, 10),
        reason: extReason.trim() || "Super Admin manual extension",
        complimentary: extComplimentary,
      });
      setOk(`Subscription for ${selectedSub.businessName} extended by ${extDays} days.`);
      setActiveDialog(null);
      await load();
    } catch (e) {
      setErr(message(e));
    } finally {
      setBusy(false);
    }
  };

  const handleOpenAssignPlan = (sub: SubscriptionRow) => {
    setSelectedSub(sub);
    setAssignPlanId(sub.planId || (plans[0]?.id ?? ""));
    setActiveDialog("assignPlanModal");
  };

  const handleSaveAssignPlan = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedSub || !assignPlanId) return;
    setBusy(true);
    try {
      await api.post(`/api/admin/subscriptions/${selectedSub.id}/assign-plan`, {
        planId: assignPlanId,
      });
      setOk(`Assigned new plan to ${selectedSub.businessName}.`);
      setActiveDialog(null);
      await load();
    } catch (e) {
      setErr(message(e));
    } finally {
      setBusy(false);
    }
  };

  const handleOpenBilling = async (sub: SubscriptionRow) => {
    setSelectedSub(sub);
    setBusy(true);
    try {
      const res = await api.get<BillingDetail>(`/api/admin/subscriptions/${sub.id}/billing`);
      setBillingDetail(res.data);
      setActiveDialog("billingModal");
    } catch (e) {
      setErr(message(e));
    } finally {
      setBusy(false);
    }
  };

  const filteredRows = rows.filter((r) => {
    if (filterStatus !== "ALL" && r.status !== filterStatus) return false;
    if (search && !r.businessName.toLowerCase().includes(search.toLowerCase())) return false;
    return true;
  });

  if (loading) {
    return (
      <DashboardLoading label="Loading subscriptions…" />
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div>
          <h1 className="text-[1.65rem] font-bold leading-tight tracking-[-0.02em] text-[var(--fg)]">Tenant Subscriptions &amp; Billing</h1>
          <p className="mt-1 text-sm text-[var(--fg-muted)]">
            Manage plans, monitor tenant lifecycles, review audit logs, and issue manual extensions.
          </p>
        </div>
        <div className="flex gap-2">
          <button onClick={() => handleOpenPlanModal()} className={`${button} bg-blue-600 hover:bg-blue-500 text-white`}>
            <Plus size={15} /> Create Subscription Plan
          </button>
        </div>
      </div>

      {ok && (
        <div className="flex items-center justify-between rounded-lg border border-emerald-300 bg-emerald-50 px-4 py-3 text-xs font-semibold text-emerald-800">
          <span className="flex items-center gap-2">
            <CheckCircle size={16} /> {ok}
          </span>
          <button onClick={() => setOk("")} className="text-emerald-700 hover:text-emerald-900">
            <X size={14} />
          </button>
        </div>
      )}

      {err && <Notice text={err} />}

      {/* Overview Cards */}
      {ov && (
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
          <div className={`${panel} p-4`}>
            <div className="text-xs font-semibold text-[var(--fg-muted)]">Active Subscriptions</div>
            <div className="mt-2 text-2xl font-bold text-emerald-600">{ov.active}</div>
            <p className="mt-1 text-[11px] text-[var(--fg-faint)]">Paying tenants</p>
          </div>
          <div className={`${panel} p-4`}>
            <div className="text-xs font-semibold text-[var(--fg-muted)]">Active Trials</div>
            <div className="mt-2 text-2xl font-bold text-blue-600">{ov.trialing}</div>
            <p className="mt-1 text-[11px] text-[var(--fg-faint)]">7-day launch &amp; new trials</p>
          </div>
          <div className={`${panel} p-4`}>
            <div className="text-xs font-semibold text-[var(--fg-muted)]">Grace Period</div>
            <div className="mt-2 text-2xl font-bold text-amber-600">{ov.grace}</div>
            <p className="mt-1 text-[11px] text-[var(--fg-faint)]">Payments paused; 2 days</p>
          </div>
          <div className={`${panel} p-4`}>
            <div className="text-xs font-semibold text-[var(--fg-muted)]">Suspended</div>
            <div className="mt-2 text-2xl font-bold text-red-600">{ov.suspended}</div>
            <p className="mt-1 text-[11px] text-[var(--fg-faint)]">Grace expired</p>
          </div>
          <div className={`${panel} p-4`}>
            <div className="text-xs font-semibold text-[var(--fg-muted)]">Month Revenue</div>
            <div className="mt-2 text-2xl font-bold text-[var(--fg)]">{money(ov.monthRevenue)}</div>
            <p className="mt-1 text-[11px] text-[var(--fg-faint)]">From SaaS plans</p>
          </div>
        </div>
      )}

      {/* Subscription Plans Management */}
      <div className={panel}>
        <div className="flex items-center justify-between border-b border-[var(--border-soft)] px-5 py-4">
          <div>
            <h2 className="text-sm font-bold text-[var(--fg)]">SaaS Subscription Plans</h2>
            <p className="text-xs text-[var(--fg-muted)]">Super Admin configured pricing and billing duration tiers</p>
          </div>
          <button onClick={() => handleOpenPlanModal()} className={`${buttonGhost} text-xs`}>
            <Plus size={14} /> Add Plan
          </button>
        </div>
        <div className="grid grid-cols-1 gap-4 p-5 md:grid-cols-3">
          {plans.map((p) => (
            <div key={p.id} className="rounded-xl border border-[var(--border-soft)] p-4 flex flex-col justify-between">
              <div>
                <div className="flex items-center justify-between">
                  <h3 className="font-bold text-[var(--fg)] text-base">{p.name}</h3>
                  <Badge tone={p.active ? "green" : "slate"}>{p.active ? "Active" : "Inactive"}</Badge>
                </div>
                <div className="mt-2 text-xl font-extrabold text-[var(--fg)]">
                  {money(p.price)}{" "}
                  <span className="text-xs font-normal text-[var(--fg-muted)]">
                    / {p.durationValue} {p.durationUnit.toLowerCase()}
                  </span>
                </div>
                {p.description && <p className="mt-2 text-xs text-[var(--fg-muted)] leading-relaxed">{p.description}</p>}
              </div>
              <div className="mt-4 pt-3 border-t border-[var(--border-soft)] flex justify-end">
                <button onClick={() => handleOpenPlanModal(p)} className="text-xs font-semibold text-blue-600 hover:text-blue-500">
                  Edit Plan
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Tenant Subscriptions List & Monitoring */}
      <div className={panel}>
        <div className="flex flex-col gap-3 border-b border-[var(--border-soft)] px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-sm font-bold text-[var(--fg)]">Tenant Subscription Monitoring</h2>
            <p className="text-xs text-[var(--fg-muted)]">Live status, grace timers, and manual controls</p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <input
              type="text"
              placeholder="Search tenant..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="h-8 rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] px-2.5 text-xs text-[var(--fg)]"
            />
            <select
              value={filterStatus}
              onChange={(e) => setFilterStatus(e.target.value)}
              className="h-8 rounded-lg border border-[var(--border-strong)] bg-[var(--surface)] px-2.5 text-xs text-[var(--fg)]"
            >
              <option value="ALL">All Statuses</option>
              <option value="ACTIVE">Active</option>
              <option value="TRIALING">Trialing</option>
              <option value="GRACE_PERIOD">Grace Period</option>
              <option value="SUSPENDED">Suspended</option>
            </select>
          </div>
        </div>

        <Table
          rows={filteredRows}
          emptyTitle="No tenant subscriptions matching query."
          columns={[
            {
              label: "Tenant",
              render: (r) => (
                <div>
                  <div className="font-bold text-[var(--fg)]">{r.businessName}</div>
                  <div className="text-[11px] text-[var(--fg-muted)]">ID #{r.tenantId}</div>
                </div>
              ),
            },
            {
              label: "Plan",
              render: (r) => (
                <div>
                  <div className="font-semibold text-xs">{r.planName}</div>
                  <div className="text-[11px] text-[var(--fg-muted)]">{money(r.monthlyFee)}</div>
                </div>
              ),
            },
            {
              label: "Status",
              render: (r) => <Badge tone={TONE[r.status] || "slate"}>{STATUS_NAME[r.status] || r.status}</Badge>,
            },
            {
              label: "Expires / Renews",
              render: (r) => (
                <div>
                  <div className="text-xs font-medium text-[var(--fg-body)]">{d(r.expiresAt || r.nextRenewalDate)}</div>
                  {r.status === "GRACE_PERIOD" && r.graceUntil && (
                    <div className="text-[10px] text-amber-600 font-bold">Grace ends {d(r.graceUntil)}</div>
                  )}
                </div>
              ),
            },
            {
              label: "Notes",
              render: (r) => (
                <div className="max-w-[200px] truncate text-xs text-[var(--fg-muted)]" title={r.adminNote || ""}>
                  {r.adminNote || "—"}
                </div>
              ),
            },
            {
              label: "Actions",
              render: (r) => (
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => handleOpenExtend(r)}
                    className="rounded bg-blue-50 px-2 py-1 text-[11px] font-semibold text-blue-700 hover:bg-blue-100 dark:bg-blue-950 dark:text-blue-300"
                  >
                    Extend
                  </button>
                  <button
                    onClick={() => handleOpenAssignPlan(r)}
                    className="rounded bg-slate-100 px-2 py-1 text-[11px] font-semibold text-slate-700 hover:bg-slate-200 dark:bg-slate-800 dark:text-slate-300"
                  >
                    Assign Plan
                  </button>
                  <button
                    onClick={() => handleOpenBilling(r)}
                    className="rounded border border-[var(--border-strong)] px-2 py-1 text-[11px] font-semibold text-[var(--fg-muted)] hover:bg-[var(--subtle)]"
                  >
                    History
                  </button>
                </div>
              ),
            },
          ]}
        />
      </div>

      {/* Plan Create/Edit Dialog */}
      {activeDialog === "planModal" && (
        <Dialog onClose={() => setActiveDialog(null)} title={editingPlan ? "Edit Subscription Plan" : "Create Subscription Plan"}>
          <form onSubmit={handleSavePlan} className="space-y-4">
            <div>
              <label className={label}>Plan Name</label>
              <input
                type="text"
                value={planName}
                onChange={(e) => setPlanName(e.target.value)}
                placeholder="e.g. Monthly Standard"
                className={field}
                required
              />
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={label}>Price in UGX</label>
                <input
                  type="number"
                  min="1"
                  value={planPrice}
                  onChange={(e) => setPlanPrice(e.target.value)}
                  placeholder="50000"
                  className={field}
                  required
                />
              </div>
              <div>
                <label className={label}>Duration</label>
                <div className="flex gap-2">
                  <input
                    type="number"
                    min="1"
                    value={planDurationValue}
                    onChange={(e) => setPlanDurationValue(e.target.value)}
                    className={`${field} w-20`}
                    required
                  />
                  <select
                    value={planDurationUnit}
                    onChange={(e) => setPlanDurationUnit(e.target.value)}
                    className={field}
                  >
                    <option value="MONTHS">Months</option>
                    <option value="DAYS">Days</option>
                  </select>
                </div>
              </div>
            </div>

            <div>
              <label className={label}>Description</label>
              <textarea
                value={planDescription}
                onChange={(e) => setPlanDescription(e.target.value)}
                placeholder="Details of plan features or discounts"
                rows={3}
                className={field}
              />
            </div>

            <div className="flex items-center gap-2">
              <input
                type="checkbox"
                id="planActiveCheck"
                checked={planActive}
                onChange={(e) => setPlanActive(e.target.checked)}
                className="rounded border-slate-300 text-blue-600 focus:ring-blue-500"
              />
              <label htmlFor="planActiveCheck" className="text-xs font-semibold text-[var(--fg)]">
                Active for tenant self-service selection
              </label>
            </div>

            <div className="mt-6 flex justify-end gap-2 border-t border-[var(--border-soft)] pt-4">
              <button type="button" onClick={() => setActiveDialog(null)} className={buttonGhost}>
                Cancel
              </button>
              <button type="submit" disabled={busy} className={`${button} bg-blue-600 text-white`}>
                <BusyLabel busy={busy} label="Saving…">Save Plan</BusyLabel>
              </button>
            </div>
          </form>
        </Dialog>
      )}

      {/* Manual Extension Dialog */}
      {activeDialog === "extendModal" && selectedSub && (
        <Dialog onClose={() => setActiveDialog(null)} title={`Extend Subscription: ${selectedSub.businessName}`}>
          <form onSubmit={handleSaveExtend} className="space-y-4">
            <p className="text-xs text-[var(--fg-muted)]">
              Manually extend tenant access. Extends from existing expiration if active, or from today if already expired.
            </p>

            <div>
              <label className={label}>Additional Days</label>
              <input
                type="number"
                min="1"
                max="365"
                value={extDays}
                onChange={(e) => setExtDays(e.target.value)}
                className={field}
                required
              />
            </div>

            <div>
              <label className={label}>Reason / Audit Note</label>
              <input
                type="text"
                value={extReason}
                onChange={(e) => setExtReason(e.target.value)}
                placeholder="e.g. VIP onboarding waiver or network maintenance compensation"
                className={field}
                required
              />
            </div>

            <div className="flex items-center gap-2">
              <input
                type="checkbox"
                id="complimentaryCheck"
                checked={extComplimentary}
                onChange={(e) => setExtComplimentary(e.target.checked)}
                className="rounded border-slate-300 text-blue-600 focus:ring-blue-500"
              />
              <label htmlFor="complimentaryCheck" className="text-xs font-semibold text-[var(--fg)]">
                Mark as complimentary extension
              </label>
            </div>

            <div className="mt-6 flex justify-end gap-2 border-t border-[var(--border-soft)] pt-4">
              <button type="button" onClick={() => setActiveDialog(null)} className={buttonGhost}>
                Cancel
              </button>
              <button type="submit" disabled={busy} className={`${button} bg-blue-600 text-white`}>
                <BusyLabel busy={busy} label="Applying…">Grant Extension</BusyLabel>
              </button>
            </div>
          </form>
        </Dialog>
      )}

      {/* Assign Plan Dialog */}
      {activeDialog === "assignPlanModal" && selectedSub && (
        <Dialog onClose={() => setActiveDialog(null)} title={`Assign Plan: ${selectedSub.businessName}`}>
          <form onSubmit={handleSaveAssignPlan} className="space-y-4">
            <div>
              <label className={label}>Select Plan</label>
              <select
                value={assignPlanId}
                onChange={(e) => setAssignPlanId(Number(e.target.value))}
                className={field}
                required
              >
                {plans.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name} — {money(p.price)} / {p.durationValue} {p.durationUnit.toLowerCase()}
                  </option>
                ))}
              </select>
            </div>

            <div className="mt-6 flex justify-end gap-2 border-t border-[var(--border-soft)] pt-4">
              <button type="button" onClick={() => setActiveDialog(null)} className={buttonGhost}>
                Cancel
              </button>
              <button type="submit" disabled={busy} className={`${button} bg-blue-600 text-white`}>
                <BusyLabel busy={busy} label="Saving…">Assign Plan</BusyLabel>
              </button>
            </div>
          </form>
        </Dialog>
      )}

      {/* Billing & Notification Audit History Dialog */}
      {activeDialog === "billingModal" && billingDetail && (
        <Dialog onClose={() => setActiveDialog(null)} title={`Billing & Audit: ${billingDetail.subscription.businessName}`}>
          <div className="space-y-6 max-h-[75vh] overflow-y-auto pr-1">
            {/* Subscription Summary */}
            <div className="rounded-lg border border-[var(--border-soft)] bg-[var(--subtle)] p-3.5 text-xs space-y-1">
              <div className="flex justify-between">
                <span className="font-semibold text-[var(--fg-muted)]">Plan:</span>
                <span className="font-bold text-[var(--fg)]">{billingDetail.subscription.planName} ({money(billingDetail.subscription.monthlyFee)})</span>
              </div>
              <div className="flex justify-between">
                <span className="font-semibold text-[var(--fg-muted)]">Status:</span>
                <Badge tone={TONE[billingDetail.subscription.status] || "slate"}>
                  {STATUS_NAME[billingDetail.subscription.status] || billingDetail.subscription.status}
                </Badge>
              </div>
              <div className="flex justify-between">
                <span className="font-semibold text-[var(--fg-muted)]">Expires / Renews:</span>
                <span className="font-medium text-[var(--fg)]">{d(billingDetail.subscription.expiresAt || billingDetail.subscription.nextRenewalDate)}</span>
              </div>
              {billingDetail.subscription.adminNote && (
                <div className="pt-2 text-[11px] text-[var(--fg-faint)] italic">
                  Note: {billingDetail.subscription.adminNote}
                </div>
              )}
            </div>

            {/* Invoices */}
            <div>
              <h3 className="font-bold text-xs uppercase tracking-wider text-[var(--fg-muted)] mb-2">Invoices</h3>
              <Table
                rows={billingDetail.invoices}
                emptyTitle="No invoices recorded."
                columns={[
                  { label: "Period", render: (i) => `${d(i.periodStart)} → ${d(i.periodEnd)}` },
                  { label: "Due", render: (i) => money(i.amountDue) },
                  { label: "Status", render: (i) => <Badge tone={TONE[i.status] || "slate"}>{i.status}</Badge> },
                  { label: "Paid At", render: (i) => d(i.paidAt) },
                ]}
              />
            </div>

            {/* Payments */}
            <div>
              <h3 className="font-bold text-xs uppercase tracking-wider text-[var(--fg-muted)] mb-2">Payment Receipts</h3>
              <Table
                rows={billingDetail.payments}
                emptyTitle="No payments recorded."
                columns={[
                  { label: "Date", render: (p) => dt(p.paidAt) },
                  { label: "Ref", render: (p) => <span className="font-mono text-xs">{p.externalRef || "—"}</span> },
                  { label: "Amount", render: (p) => money(p.amount) },
                  { label: "Method", render: (p) => p.method },
                ]}
              />
            </div>

            {/* Notifications Audit */}
            <div>
              <h3 className="font-bold text-xs uppercase tracking-wider text-[var(--fg-muted)] mb-2">Notification &amp; Reminder Audit</h3>
              <Table
                rows={billingDetail.notifications}
                emptyTitle="No notifications dispatched."
                columns={[
                  { label: "Date", render: (n) => dt(n.createdAt) },
                  { label: "Type", render: (n) => <span className="font-mono text-xs">{n.notificationType}</span> },
                  { label: "Title", render: (n) => <span className="font-medium">{n.title}</span> },
                  { label: "Email Sent", render: (n) => <Badge tone={n.emailSent ? "green" : "slate"}>{n.emailSent ? "Yes" : "No"}</Badge> },
                ]}
              />
            </div>

            <div className="flex justify-end pt-3">
              <button onClick={() => setActiveDialog(null)} className={buttonGhost}>
                Close
              </button>
            </div>
          </div>
        </Dialog>
      )}
    </div>
  );
}
