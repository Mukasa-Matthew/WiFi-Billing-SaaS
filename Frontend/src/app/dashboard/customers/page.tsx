"use client";
import React, { useMemo, useState } from "react";
import api from "@/lib/api";
import { useResource, Table, ErrorNotice, field, button, buttonGhost, message, PageContainer, PageHeader, EmptyState, label, StatCard } from "@/components/BillingUI";
import { Dialog } from "@/components/ui/Dialog";
import { useToast } from "@/components/ui/Toast";
import { Users, UserPlus, Phone, NotePencil, FloppyDisk } from "@/components/icons";

interface Customer { id: number; name: string; phone: string; notes: string }

export default function Page() {
  const { data, loading, error, load } = useResource<Customer>("/api/customers");
  const [edit, setEdit] = useState<Partial<Customer> | null>(null);
  const [note, setNote] = useState("");
  const [saving, setSaving] = useState(false);
  const toast = useToast();
  const stats = useMemo(() => ({
    total: data.length,
    reachable: data.filter((customer) => customer.phone?.trim()).length,
    documented: data.filter((customer) => customer.notes?.trim()).length,
  }), [data]);

  const openCreate = () => { setNote(""); setEdit({ name: "", phone: "", notes: "" }); };
  const openEdit = (customer: Customer) => { setNote(""); setEdit({ ...customer }); };

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    try {
      if (edit?.id) { await api.put(`/api/customers/${edit.id}`, edit); toast("success", "Customer updated."); }
      else { await api.post("/api/customers", edit); toast("success", "Customer added."); }
      setEdit(null);
      await load();
    } catch (err) { setNote(message(err)); }
    finally { setSaving(false); }
  };

  return (
    <PageContainer>
      <PageHeader
        title="Customers"
        description="Named records for voucher assignment, walk-in sales, and service history."
        action={<button onClick={openCreate} className={button}><UserPlus className="h-4 w-4" />Add customer</button>}
      />
      <ErrorNotice text={error || note} />

      <div className="grid gap-3 sm:grid-cols-3">
        <StatCard label="Customer records" value={loading ? "—" : stats.total.toLocaleString()} icon={<Users />} hintText="Saved in your business directory" />
        <StatCard label="Reachable by phone" value={loading ? "—" : stats.reachable.toLocaleString()} icon={<Phone />} hintText={stats.total ? `${Math.round((stats.reachable / stats.total) * 100)}% have a phone number` : "Add contact details as customers return"} tone="green" />
        <StatCard label="With service notes" value={loading ? "—" : stats.documented.toLocaleString()} icon={<NotePencil />} hintText="Context available for future support" />
      </div>

      {!loading && !data.length ? (
        <section className="rounded-xl border border-[var(--border)] bg-[var(--surface)] shadow-sm">
          <EmptyState
            icon={<Users className="h-8 w-8 text-slate-300" />}
            title="No customers yet"
            description="Customers let you attach vouchers and payments to a name and phone number — useful for repeat walk-in buyers and support lookups."
            action={<button onClick={openCreate} className={`${button} mt-3`}><UserPlus className="h-4 w-4" />Add your first customer</button>}
          />
        </section>
      ) : (
        <Table
          rows={data}
          loading={loading}
          searchPlaceholder="Search customer name, phone or notes…"
          emptyIcon={<Users />}
          emptyTitle="No customers match"
          emptyDescription="Try a different name, phone number, or note."
          rowKey={(customer) => customer.id}
          columns={[
            { label: "Customer", render: c => <div className="flex min-w-48 items-center gap-3"><span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-[var(--fill)] text-xs font-semibold uppercase text-[var(--fg-muted)]">{c.name.split(/\s+/).slice(0, 2).map((part) => part[0]).join("")}</span><span className="min-w-0"><span className="block font-semibold text-[var(--fg)]">{c.name}</span><span className="num block text-[11px] text-[var(--fg-faint)]">Customer C{c.id}</span></span></div> },
            { label: "Phone", render: c => c.phone ? <span className="num inline-flex items-center gap-2"><Phone className="h-3.5 w-3.5 text-[var(--fg-faint)]" />{c.phone}</span> : <span className="text-[var(--fg-faint)]">Not provided</span> },
            { label: "Notes", hideBelow: "md", render: c => c.notes ? <span className="block max-w-md truncate text-[var(--fg-muted)]" title={c.notes}>{c.notes}</span> : <span className="text-[var(--fg-faint)]">No notes</span> },
            { label: "", align: "right", className: "w-24", render: c => <button onClick={() => openEdit(c)} className="min-h-9 rounded-lg px-3 text-xs font-semibold text-[var(--accent-text)] transition hover:bg-blue-50">Edit details</button> },
          ]}
        />
      )}
      {edit && (
        <Dialog
          title={edit.id ? `Edit ${edit.name}` : "Add customer"}
          description="Customer records are internal — customers never see this information."
          onClose={() => !saving && setEdit(null)}
        >
          <form onSubmit={save} className="space-y-5">
            <div>
              <label htmlFor="customer-name" className={label}>Full name <span className="text-red-500">*</span></label>
              <input id="customer-name" autoFocus required maxLength={120} value={edit.name || ""} onChange={e => setEdit({ ...edit, name: e.target.value })} className={field} placeholder="e.g. Sarah Namusoke" />
            </div>
            <div>
              <label htmlFor="customer-phone" className={label}>Phone number</label>
              <input id="customer-phone" type="tel" autoComplete="tel" maxLength={32} value={edit.phone || ""} onChange={e => setEdit({ ...edit, phone: e.target.value })} className={field} placeholder="e.g. +256 792 000 000" />
              <p className="mt-1.5 text-[11px] text-[var(--fg-faint)]">Include the country code when possible for easier support lookups.</p>
            </div>
            <div>
              <div className="flex items-center justify-between"><label htmlFor="customer-notes" className={label}>Internal notes</label><span className="num text-[11px] text-[var(--fg-faint)]">{(edit.notes || "").length}/500</span></div>
              <textarea id="customer-notes" maxLength={500} value={edit.notes || ""} onChange={e => setEdit({ ...edit, notes: e.target.value })} className={`${field} resize-none`} rows={4} placeholder="Location, preferred package, device, or anything worth remembering" />
            </div>
            <div className="flex flex-col-reverse gap-2 border-t border-[var(--border)] pt-5 sm:flex-row sm:justify-end">
              <button type="button" onClick={() => setEdit(null)} disabled={saving} className={buttonGhost}>Cancel</button>
              <button disabled={saving} className={button}>{saving ? "Saving…" : <><FloppyDisk className="h-4 w-4" />{edit.id ? "Save changes" : "Add customer"}</>}</button>
            </div>
          </form>
        </Dialog>
      )}
    </PageContainer>
  );
}
