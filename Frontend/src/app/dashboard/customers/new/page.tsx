"use client";
import { BusyLabel, useSingleFlight } from "@/components/LoadingUI";
import { notify } from "@/components/ui/Toast";
import { mutateCache } from "@/lib/cacheInvalidation";
import React, { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import api from "@/lib/api";
import { useResource, Notice, field, label, hint, message, button, buttonGhost, ugx, FormHeader, FormSection, FormLayout, SummaryCard } from "@/components/BillingUI";
import { User, Stack, Money } from "@/components/icons";

interface Plan { id: number; name: string; price: number; sessionTimeout: number; availability: string }
interface Router { id: number; name: string }

const req = <span className="text-red-500">*</span>;
const METHODS = [["CASH", "Cash"], ["MTN", "MTN Mobile Money"], ["AIRTEL", "Airtel Money"], ["BANK", "Bank"], ["OTHER", "Other"]] as const;

/** "1 day", "7 days", "3 hours" from a package's session length in seconds. */
function duration(seconds: number) {
  if (!seconds) return "—";
  const days = seconds / 86400, hours = seconds / 3600;
  if (Number.isInteger(days)) return `${days} day${days === 1 ? "" : "s"}`;
  if (Number.isInteger(hours)) return `${hours} hour${hours === 1 ? "" : "s"}`;
  return `${Math.round(seconds / 60)} minutes`;
}

export default function NewCustomerPage() {
  const nav = useRouter();
  const plans = useResource<Plan>("/api/packages");
  const routers = useResource<Router>("/api/routers");
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [phone, setPhone] = useState("");
  const [notes, setNotes] = useState("");
  const [plan, setPlan] = useState("");
  const [paid, setPaid] = useState(false);
  const [router, setRouter] = useState("");
  const [amount, setAmount] = useState("");
  const [method, setMethod] = useState("CASH");
  const [saving, setSaving] = useState<"" | "create" | "another">("");
  const [note, setNote] = useState("");

  const chosen = plans.data.find((p) => String(p.id) === plan);
  const sellable = plans.data.filter((p) => p.availability !== "DISABLED");
  const name = `${firstName.trim()} ${lastName.trim()}`.trim();

  const reset = () => { setFirstName(""); setLastName(""); setPhone(""); setNotes(""); setPlan(""); setPaid(false); setAmount(""); setNote(""); };

  const singleFlight = useSingleFlight();
  const save = singleFlight(async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const another = (e.nativeEvent as SubmitEvent).submitter?.getAttribute("value") === "another";
    setSaving(another ? "another" : "create"); setNote("");
    try {
      const res = await api.post<{ customerId: number; voucher: string | null }>("/api/customers/onboard", {
        name, phone, notes,
        packageId: chosen ? chosen.id : null,
        routerId: chosen && paid ? Number(router) : null,
        amount: chosen && paid ? Number(amount || chosen.price) : null,
        method: chosen && paid ? method : null,
      });
      mutateCache.customerChanged();
      if (res.data.voucher) mutateCache.voucherChanged();
      notify.success(res.data.voucher
        ? `Customer added with voucher ${res.data.voucher}${paid ? " and the payment recorded" : ""}.`
        : "Customer added.");
      if (another) reset();
      else nav.push("/dashboard/customers");
    } catch (err) { setNote(message(err)); }
    finally { setSaving(""); }
  });

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <FormHeader backHref="/dashboard/customers" backLabel="Customers" title="Create customer"
        description="Add a walk-in buyer. Mobile money buyers are added automatically, so you only need this for cash sales or to save someone's details." />
      <Notice text={note || plans.error || routers.error} />
      <form onSubmit={save}>
        <FormLayout aside={
          <SummaryCard title="Customer summary" rows={[
            { label: "Name", value: name || <span className="font-normal text-[var(--fg-muted)]">Saved as their phone number</span> },
            { label: "Phone", value: phone ? <span className="num">{phone}</span> : <span className="font-normal text-[var(--fg-muted)]">Required</span> },
            { label: "Package", value: chosen ? chosen.name : <span className="font-normal text-[var(--fg-muted)]">None yet</span> },
            { label: "Access", value: chosen ? `${duration(chosen.sessionTimeout)} from first login` : "—" },
            { label: "Payment", value: chosen && paid ? `${ugx(amount || chosen.price)} · ${METHODS.find(([k]) => k === method)?.[1]}` : chosen ? "Not recorded" : "—" },
          ]} footer="Future mobile money payments from this number are linked to this customer automatically." />
        }>
          <FormSection icon={<User size={18} aria-hidden />} title="Customer details" hint="Only the phone number is required. Customers never see these details.">
            <div className="grid gap-5 sm:grid-cols-2">
              <div>
                <label htmlFor="c-first" className={label}>First name</label>
                <input id="c-first" autoFocus maxLength={60} value={firstName} onChange={(e) => setFirstName(e.target.value)} className={field} placeholder="e.g. Sarah" />
              </div>
              <div>
                <label htmlFor="c-last" className={label}>Last name</label>
                <input id="c-last" maxLength={60} value={lastName} onChange={(e) => setLastName(e.target.value)} className={field} placeholder="e.g. Namusoke" />
              </div>
              <div className="sm:col-span-2">
                <label htmlFor="c-phone" className={label}>Phone number{req}</label>
                <input id="c-phone" required type="tel" autoComplete="tel" maxLength={32} value={phone} onChange={(e) => setPhone(e.target.value)} className={field} placeholder="e.g. 0772 000 000" />
                <p className={hint}>Used to match their mobile money payments to this record.</p>
              </div>
              <div className="sm:col-span-2">
                <label htmlFor="c-notes" className={label}>Internal notes</label>
                <textarea id="c-notes" maxLength={500} rows={3} value={notes} onChange={(e) => setNotes(e.target.value)} className={`${field} resize-none`} placeholder="Location, device, or anything worth remembering" />
              </div>
            </div>
          </FormSection>

          <FormSection icon={<Stack size={18} aria-hidden />} title="Package & access" hint="Optional. Choosing a package issues them a voucher straight away.">
            <div className="grid gap-5 sm:grid-cols-2">
              <div className="sm:col-span-2">
                <label htmlFor="c-plan" className={label}>Package</label>
                <select id="c-plan" value={plan} onChange={(e) => { setPlan(e.target.value); setAmount(""); }} className={field} disabled={plans.loading}>
                  <option value="">{plans.loading ? "Loading packages…" : "No package for now"}</option>
                  {sellable.map((p) => <option key={p.id} value={p.id}>{p.name} · {ugx(p.price)} · {duration(p.sessionTimeout)}</option>)}
                </select>
                <p className={hint}>{chosen ? `Access starts the first time they log in and lasts ${duration(chosen.sessionTimeout)}.` : sellable.length || plans.loading ? "Leave empty to only save their details." : <>No packages yet. <Link href="/dashboard/packages/new" className="font-medium text-[var(--accent-text)] hover:underline">Create one</Link> first.</>}</p>
              </div>
            </div>
          </FormSection>

          {chosen && (
            <FormSection icon={<Money size={18} aria-hidden />} title="Payment" hint="Record what they paid, so it appears in Payments and revenue.">
              <label className="flex items-center gap-2.5 text-sm text-[var(--fg-body)]">
                <input type="checkbox" checked={paid} onChange={(e) => setPaid(e.target.checked)} className="h-4 w-4 rounded border-[var(--border-strong)]" />
                They paid for this package
              </label>
              {paid && (
                <div className="mt-5 grid gap-5 sm:grid-cols-3">
                  <div>
                    <label htmlFor="c-router" className={label}>Paid at{req}</label>
                    <select id="c-router" required value={router} onChange={(e) => setRouter(e.target.value)} className={field}>
                      <option value="">Select router</option>
                      {routers.data.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
                    </select>
                  </div>
                  <div>
                    <label htmlFor="c-amount" className={label}>Amount (UGX){req}</label>
                    <input id="c-amount" required type="number" min="1" step="1" value={amount || String(chosen.price)} onChange={(e) => setAmount(e.target.value)} className={field} />
                  </div>
                  <div>
                    <label htmlFor="c-method" className={label}>Method</label>
                    <select id="c-method" value={method} onChange={(e) => setMethod(e.target.value)} className={field}>
                      {METHODS.map(([k, l]) => <option key={k} value={k}>{l}</option>)}
                    </select>
                  </div>
                </div>
              )}
            </FormSection>
          )}

          <div className="flex flex-col-reverse gap-3 pb-2 sm:flex-row sm:items-center sm:justify-end">
            <Link href="/dashboard/customers" className={`${buttonGhost} min-h-[44px] justify-center py-3 sm:min-h-0 sm:py-2.5`}>Cancel</Link>
            <button type="submit" value="another" disabled={!!saving} className={`${buttonGhost} min-h-[44px] justify-center py-3 sm:min-h-0 sm:py-2.5`}>
              <BusyLabel busy={saving === "another"} label="Creating…">Create &amp; add another</BusyLabel>
            </button>
            <button type="submit" value="create" disabled={!!saving} className={`${button} min-h-[44px] justify-center py-3 sm:min-h-0 sm:py-2.5`}>
              <BusyLabel busy={saving === "create"} label="Creating…">Create customer</BusyLabel>
            </button>
          </div>
        </FormLayout>
      </form>
    </div>
  );
}
