"use client";
import { mutateCache } from "@/lib/cacheInvalidation";

import React, { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import api from "@/lib/api";
import {
  useResource, Notice, field, label, hint, message,
  FormHeader, FormSection, FormActions, FormLayout, SummaryCard,
} from "@/components/BillingUI";
import { Stack, Ticket, Gift } from "@/components/icons";

interface Plan { id: number; name: string; availability: string }

const FORMATS: Record<string, string> = {
  ALPHANUMERIC: "ABCDEFGHJKLMNPQRSTUVWXYZ23456789",
  NUMERIC: "0123456789",
  LETTERS: "ABCDEFGHJKLMNPQRSTUVWXYZ",
};

export default function CreateVouchersPage() {
  const nav = useRouter();
  const { data, error } = useResource<Plan>("/api/packages");
  const [plan, setPlan] = useState("");
  const [count, setCount] = useState("1");
  const [prefix, setPrefix] = useState("");
  const [length, setLength] = useState("8");
  const [format, setFormat] = useState("ALPHANUMERIC");
  const [batch, setBatch] = useState("");
  const [giveaway, setGiveaway] = useState(false);
  const [saving, setSaving] = useState(false);
  const [note, setNote] = useState("");

  useEffect(() => {
    api.get<{ voucherPrefix: string | null; voucherCodeLength: number; voucherCodeFormat: string }>("/api/settings")
      .then((r) => {
        if (r.data.voucherPrefix) setPrefix(r.data.voucherPrefix);
        if (r.data.voucherCodeLength) setLength(String(r.data.voucherCodeLength));
        if (r.data.voucherCodeFormat) setFormat(r.data.voucherCodeFormat);
      })
      .catch(() => undefined);
  }, []);

  /* Shows the shape of the code the current settings produce, so the operator
     can sanity-check prefix and length before generating a batch. */
  const sample = useMemo(() => {
    const pool = FORMATS[format] || FORMATS.ALPHANUMERIC;
    const body = Math.max(0, Number(length) - prefix.length);
    return prefix + Array.from({ length: body }, (_, i) => pool[(i * 7 + 3) % pool.length]).join("");
  }, [prefix, length, format]);

  const selected = data.find((p) => String(p.id) === plan);

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    try {
      await api.post("/api/vouchers", {
        packageId: Number(plan), count: Number(count), prefix,
        codeLength: Number(length), codeFormat: format,
        batchReference: batch, giveaway,
      });
      // The list may still be cached as fresh; without this the new batch
      // would be missing when the list opens.
      mutateCache.voucherChanged();
      nav.push("/dashboard/vouchers");
    } catch (err) { setNote(message(err)); }
    finally { setSaving(false); }
  };

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <FormHeader
        backHref="/dashboard/vouchers" backLabel="Vouchers"
        title="Generate vouchers"
        description="Create a batch of access codes for a package. Validity starts at first login, not when you generate them."
      />
      <Notice text={error || note} />

      <form onSubmit={save}>
        <FormLayout
          aside={
            <SummaryCard
              title="Batch summary"
              rows={[
                { label: "Package", value: selected ? selected.name : <span className="font-normal text-[var(--fg-muted)]">Not selected</span> },
                { label: "Vouchers", value: Number(count) || 0 },
                { label: "Code length", value: `${length} characters` },
                { label: "Example code", value: <span className="font-mono tracking-wider">{sample || "—"}</span> },
                { label: "Records revenue", value: giveaway ? "No · giveaway" : "Yes" },
              ]}
              footer="Generating inventory does not record a sale. Delivery is manual."
            />
          }
        >
          <FormSection
            icon={<Stack size={18} aria-hidden />}
            title="Package and quantity"
            hint="Which plan these codes unlock, and how many to produce."
          >
            <div className="grid gap-5 sm:grid-cols-2">
              <div>
                <label htmlFor="v-plan" className={label}>Package *</label>
                <select id="v-plan" required value={plan} onChange={(e) => setPlan(e.target.value)} className={field}>
                  <option value="">Select a package</option>
                  {data.map((p) => <option key={p.id} value={p.id}>{p.name} ({p.availability.toLowerCase()})</option>)}
                </select>
              </div>
              <div>
                <label htmlFor="v-count" className={label}>Number of vouchers *</label>
                <input id="v-count" required type="number" min="1" max="1000" value={count}
                  onChange={(e) => setCount(e.target.value)} className={field} />
                <p className={hint}>Up to 1,000 per batch.</p>
              </div>
            </div>
            <div>
              <label htmlFor="v-batch" className={label}>Batch reference</label>
              <input id="v-batch" maxLength={80} value={batch} onChange={(e) => setBatch(e.target.value)}
                placeholder="Auto-generated if empty" className={field} />
              <p className={hint}>Helps you find this batch later on the vouchers list.</p>
            </div>
          </FormSection>

          <FormSection
            icon={<Ticket size={18} aria-hidden />}
            title="Code format"
            hint="Defaults come from Settings → Vouchers. Changing them here affects this batch only."
          >
            <div className="grid gap-5 sm:grid-cols-3">
              <div>
                <label htmlFor="v-prefix" className={label}>Prefix</label>
                <input id="v-prefix" maxLength={1} pattern="[A-Za-z]?" value={prefix}
                  onChange={(e) => setPrefix(e.target.value.toUpperCase())} className={field} />
                <p className={hint}>One letter, optional.</p>
              </div>
              <div>
                <label htmlFor="v-length" className={label}>Code length *</label>
                <select id="v-length" value={length} onChange={(e) => setLength(e.target.value)} className={field}>
                  {[5, 6, 7, 8, 9, 10, 11, 12].map((n) => <option key={n} value={n}>{n} characters</option>)}
                </select>
                <p className={hint}>Includes the prefix.</p>
              </div>
              <div>
                <label htmlFor="v-format" className={label}>Characters *</label>
                <select id="v-format" value={format} onChange={(e) => setFormat(e.target.value)} className={field}>
                  <option value="ALPHANUMERIC">Letters and numbers</option>
                  <option value="NUMERIC">Numbers</option>
                  <option value="LETTERS">Letters</option>
                </select>
              </div>
            </div>
          </FormSection>

          <FormSection
            icon={<Gift size={18} aria-hidden />}
            title="Revenue"
            hint="Mark complimentary batches so they stay out of your sales figures."
          >
            <label className="flex cursor-pointer items-start gap-3 rounded-lg border border-[var(--border)] p-4 transition hover:bg-[var(--subtle)]">
              <input type="checkbox" checked={giveaway} onChange={(e) => setGiveaway(e.target.checked)}
                className="mt-0.5 h-4 w-4 shrink-0 accent-blue-700" />
              <span>
                <span className="block text-sm font-medium text-[var(--fg)]">Giveaway batch</span>
                <span className="mt-0.5 block text-xs text-[var(--fg-muted)]">No revenue is recorded when these codes are used.</span>
              </span>
            </label>
          </FormSection>

          <FormActions busy={saving} busyLabel="Generating…" submitLabel="Create vouchers" cancelHref="/dashboard/vouchers" />
        </FormLayout>
      </form>
    </div>
  );
}
