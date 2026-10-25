"use client";

/**
 * Tenant account actions shared by the tenant list and tenant detail pages.
 * Same endpoints and payloads as before; failures now surface as toasts, and
 * anything that blocks a business's access asks first.
 */

import { useState } from "react";
import api from "@/lib/api";
import { message } from "@/components/BillingUI";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";
import { ActionMenuItem } from "@/components/ui/ActionMenu";
import { Check, Copy, EnvelopeSimple, Key, Prohibit, Trash, X } from "@/components/icons";

export const accountTone = (s: string) => (s === "ACTIVE" ? "green" : s === "SUSPENDED" ? "amber" : "red");
/** displayStatus from SubscriptionService: ACTIVE, PAYMENT_DUE, PAST_DUE, or the raw status (SUSPENDED, CANCELLED…). */
export const subscriptionTone = (s: string) => (s === "ACTIVE" ? "green" : s === "PAYMENT_DUE" ? "amber" : s === "PAST_DUE" || s === "SUSPENDED" ? "red" : "slate");

export interface TenantRef { id: number; name: string; email: string; phone: string; status: string }

export function useTenantActions(onChanged: () => void | Promise<void>) {
  const toast = useToast();
  const { confirm, dialog } = useConfirmDialog();
  /** Persistent notice for things the admin must copy (a temporary password). */
  const [notice, setNotice] = useState<{ text: string; secret?: string } | null>(null);

  const resendCredentials = async (t: TenantRef) => {
    try {
      const res = await api.post<{ temporaryPassword: string; welcomeEmailSent: boolean }>(`/api/admin/tenants/${t.id}/resend-credentials`);
      if (res.data.welcomeEmailSent) toast("success", `New temporary credentials emailed to ${t.email}.`);
      else setNotice({ text: `Email isn't configured, so give ${t.name} this temporary password securely. It won't be shown again.`, secret: res.data.temporaryPassword });
    } catch (e) {
      toast("error", `Couldn't generate credentials: ${message(e)}`);
    }
  };

  const setStatus = async (t: TenantRef, status: string) => {
    try {
      await api.put(`/api/admin/tenants/${t.id}/status`, { status });
      toast("success", `${t.name} is now ${status.toLowerCase()}.`);
      await onChanged();
    } catch (e) {
      toast("error", `Couldn't update ${t.name}: ${message(e)}`);
    }
  };

  const suspend = (t: TenantRef) => confirm({
    title: `Suspend ${t.name}?`,
    body: "The operator loses dashboard access until you reactivate the account. Their routers, vouchers and data are kept.",
    confirmLabel: "Suspend operator",
    onConfirm: () => setStatus(t, "SUSPENDED"),
  });

  const decommission = (t: TenantRef) => confirm({
    title: `Decommission ${t.name}?`,
    body: "Permanently deleting a tenant with live transactions and routers is restricted to preserve financial audit logs. You can disable the operator account to instantly block portal access and logins. Would you like to set their status to DISABLED?",
    confirmLabel: "Disable account",
    onConfirm: () => setStatus(t, "DISABLED"),
  });

  const copyDetails = async (t: TenantRef) => {
    try {
      await navigator.clipboard.writeText(`Business: ${t.name}\nEmail: ${t.email}\nPhone: ${t.phone}\nID: #${t.id}`);
      toast("success", `Copied details for ${t.name}.`);
    } catch {
      toast("error", "Clipboard isn't available in this browser.");
    }
  };

  /** The standard row/overflow menu items. */
  const menuItems = (t: TenantRef) => (
    <>
      <ActionMenuItem icon={<Key className="h-4 w-4 text-[var(--accent-text)]" />} label="Generate / resend password" onClick={() => resendCredentials(t)} />
      {t.status === "ACTIVE"
        ? <ActionMenuItem icon={<Prohibit className="h-4 w-4" />} label="Suspend operator" danger onClick={() => suspend(t)} />
        : <ActionMenuItem icon={<Check className="h-4 w-4 text-emerald-600" />} label="Activate operator" onClick={() => setStatus(t, "ACTIVE")} />}
      <ActionMenuItem icon={<EnvelopeSimple className="h-4 w-4 text-[var(--fg-muted)]" />} label="Email operator" onClick={() => { window.location.href = `mailto:${t.email}`; }} />
      <ActionMenuItem icon={<Copy className="h-4 w-4 text-[var(--fg-muted)]" />} label="Copy details" onClick={() => copyDetails(t)} />
      <ActionMenuItem icon={<Trash className="h-4 w-4" />} label="Decommission" danger onClick={() => decommission(t)} />
    </>
  );

  const noticeBanner = notice && (
    <div role="status" className="flex flex-col gap-3 rounded-xl border border-amber-200 bg-amber-50 p-4 text-amber-900 sm:flex-row sm:items-center">
      <p className="flex-1 text-[13px] leading-relaxed">{notice.text}</p>
      {notice.secret && (
        <span className="flex items-center gap-2">
          <code className="rounded-md border border-amber-200 bg-[var(--surface)] px-2.5 py-1 font-mono text-sm text-[var(--fg)]">{notice.secret}</code>
          <button onClick={() => { void navigator.clipboard?.writeText(notice.secret!); toast("success", "Password copied."); }} className="rounded-md p-1.5 hover:bg-amber-100" aria-label="Copy password"><Copy className="h-4 w-4" /></button>
        </span>
      )}
      <button onClick={() => setNotice(null)} aria-label="Dismiss" className="self-start rounded-md p-1 hover:bg-amber-100 sm:self-center"><X className="h-4 w-4" /></button>
    </div>
  );

  return { resendCredentials, setStatus, suspend, decommission, copyDetails, menuItems, dialog, noticeBanner, setNotice } as const;
}

export type TenantActions = ReturnType<typeof useTenantActions>;
