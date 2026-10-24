"use client";
import React, { useState } from "react";
import api from "@/lib/api";
import { useResource, Table, Notice, field, button, buttonGhost, message, PageContainer,PageHeader, EmptyState, label } from "@/components/BillingUI";
import { Dialog } from "@/components/ui/Dialog";
import { useToast } from "@/components/ui/Toast";
import { Users, UserPlus } from "@phosphor-icons/react";

interface Customer { id: number; name: string; phone: string; notes: string }

export default function Page() {
  const { data, loading, error, load } = useResource<Customer>("/api/customers");
  const [edit, setEdit] = useState<Partial<Customer> | null>(null);
  const [note, setNote] = useState("");
  const [saving, setSaving] = useState(false);
  const toast = useToast();

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
        action={<button onClick={() => setEdit({ name: "", phone: "", notes: "" })} className={button}><UserPlus size={14} />Add customer</button>}
      />
      <Notice text={error || note} />
      {!loading && !data.length ? (
        <section className="rounded-xl border border-[var(--border)] bg-[var(--surface)] shadow-sm">
          <EmptyState
            icon={<Users className="h-8 w-8 text-slate-300" />}
            title="No customers yet"
            description="Customers let you attach vouchers and payments to a name and phone number — useful for repeat walk-in buyers and support lookups."
            action={<button onClick={() => setEdit({ name: "", phone: "", notes: "" })} className={button}>Add your first customer</button>}
          />
        </section>
      ) : (
        <Table
          rows={data}
          loading={loading}
          emptyTitle="No customers match"
          emptyDescription="Try a different search."
          columns={[
            { label: "Customer ID", render: c => <span className="font-mono text-xs">C{c.id}</span> },
            { label: "Full name", render: c => <span className="font-medium">{c.name}</span> },
            { label: "Phone", render: c => c.phone || "—" },
            { label: "Notes", render: c => c.notes || "—" },
            { label: "", render: c => <button onClick={() => setEdit(c)} className="text-xs font-medium text-[var(--accent-text)] hover:underline">Edit</button> },
          ]}
        />
      )}
      {edit && (
        <Dialog
          title={edit.id ? `Edit ${edit.name}` : "Add customer"}
          description="Customer records are internal — customers never see this information."
          onClose={() => !saving && setEdit(null)}
        >
          <form onSubmit={save} className="space-y-4">
            <div>
              <label className={label}>Full name <span className="text-red-500">*</span></label>
              <input required maxLength={120} value={edit.name || ""} onChange={e => setEdit({ ...edit, name: e.target.value })} className={field} />
            </div>
            <div>
              <label className={label}>Phone</label>
              <input maxLength={32} value={edit.phone || ""} onChange={e => setEdit({ ...edit, phone: e.target.value })} className={field} placeholder="e.g. 256792000000" />
            </div>
            <div>
              <label className={label}>Notes</label>
              <textarea maxLength={500} value={edit.notes || ""} onChange={e => setEdit({ ...edit, notes: e.target.value })} className={field} rows={3} placeholder="Location, device, or anything worth remembering" />
            </div>
            <div className="flex justify-end gap-2 pt-1">
              <button type="button" onClick={() => setEdit(null)} disabled={saving} className={buttonGhost}>Cancel</button>
              <button disabled={saving} className={button}>{saving ? "Saving…" : edit.id ? "FloppyDisk customer" : "Add customer"}</button>
            </div>
          </form>
        </Dialog>
      )}
    </PageContainer>
  );
}
