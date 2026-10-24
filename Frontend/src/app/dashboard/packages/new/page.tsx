"use client";

import React, { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import api from "@/lib/api";
import {
  field, label, hint, panel, ErrorNotice, ugx,
  FormHeader, FormSection, FormActions, FormLayout,
} from "@/components/BillingUI";
import { Stack, Lightning, Money, Eye, CellTower } from "@phosphor-icons/react";

const presets: Record<string, { name: string; duration: string; unit: string; price: string }> = {
  hourly: { name: "Hourly Internet", duration: "1", unit: "3600", price: "500" },
  daily: { name: "Daily Internet", duration: "1", unit: "86400", price: "2000" },
  weekly: { name: "Weekly Internet", duration: "7", unit: "86400", price: "12000" },
  monthly: { name: "Monthly Internet", duration: "30", unit: "86400", price: "30000" },
};

const UNITS: Record<string, string> = { "60": "minute", "3600": "hour", "86400": "day" };

export default function CreatePackagePage() {
  const nav = useRouter();
  const [name, setName] = useState(""); const [duration, setDuration] = useState("1"); const [unit, setUnit] = useState("86400");
  const [description, setDescription] = useState(""); const [upload, setUpload] = useState("5"); const [download, setDownload] = useState("5");
  const [price, setPrice] = useState(""); const [devices, setDevices] = useState("1"); const [availability, setAvailability] = useState("ACTIVE");
  const [featured, setFeatured] = useState(false); const [order, setOrder] = useState("0");
  const [saving, setSaving] = useState(false); const [error, setError] = useState("");
  const [routers, setRouters] = useState<{ id: number; name: string }[]>([]); const [routerId, setRouterId] = useState("");

  useEffect(() => {
    const template = presets[new URLSearchParams(window.location.search).get("template") || ""];
    if (template) { setName(template.name); setDuration(template.duration); setUnit(template.unit); setPrice(template.price); }
  }, []);
  useEffect(() => { api.get<{ id: number; name: string }[]>("/api/routers").then((r) => setRouters(r.data)).catch(() => {}); }, []);

  const n = Number(duration) || 0;
  const validity = `${n} ${UNITS[unit] || "day"}${n === 1 ? "" : "s"}`;

  const save = async (e: React.FormEvent) => {
    e.preventDefault(); setError("");
    const seconds = Number(duration) * Number(unit);
    if (!Number.isSafeInteger(seconds) || seconds < 60 || seconds > 2147483647) {
      setError("Choose a valid duration of at least one minute."); return;
    }
    setSaving(true);
    try {
      await api.post("/api/packages", {
        name: name.trim(), description: description.trim(), price: Number(price),
        sessionTimeout: seconds, rateLimit: `${upload}M/${download}M`, sharedUsers: Number(devices),
        availability, featured, displayOrder: Number(order), routerId: routerId ? Number(routerId) : null,
      });
      nav.push("/dashboard/packages");
    } catch { setError("Could not save the package. Check your entries and try again."); }
    finally { setSaving(false); }
  };

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <FormHeader
        backHref="/dashboard/packages" backLabel="Packages"
        title="Create package"
        description="Set the plan's duration, speeds, price and where customers can see it."
      />

      <form onSubmit={save}>
        <FormLayout
          aside={
            /* The same information the customer sees on the captive portal,
               so pricing and limits can be checked before saving. */
            <section className={panel}>
              <h2 className="border-b border-[var(--border)] px-5 py-3.5 text-xs font-semibold uppercase tracking-wide text-[var(--fg-muted)]">
                Portal preview
              </h2>
              <div className="p-5">
                <div className="rounded-xl border border-[var(--border)] bg-[var(--subtle)] p-4">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="truncate text-sm font-semibold text-[var(--fg)]">{name.trim() || "Package name"}</span>
                        {featured && (
                          <span className="shrink-0 rounded-full bg-blue-50 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide text-[var(--accent-text)]">
                            Popular
                          </span>
                        )}
                      </div>
                      <p className="mt-1 truncate text-xs text-[var(--fg-muted)]">
                        {validity} · {download} Mbps · {devices} device{Number(devices) === 1 ? "" : "s"}
                      </p>
                    </div>
                    <span className="shrink-0 text-sm font-bold tabular-nums text-[var(--fg)]">
                      {price ? ugx(price) : "—"}
                    </span>
                  </div>
                </div>
                <p className="mt-3 text-xs leading-relaxed text-[var(--fg-muted)]">
                  {availability === "ACTIVE"
                    ? "Visible to customers on the captive portal."
                    : availability === "HIDDEN"
                      ? "Hidden from the portal. Reachable by voucher only."
                      : "Disabled. Existing vouchers keep their access."}
                </p>
              </div>
            </section>
          }
        >
          <FormSection icon={<Stack size={18} weight="duotone" aria-hidden />}
            title="Basic configuration" hint="Name the plan and set how long access lasts.">
            <div>
              <label htmlFor="p-name" className={label}>Package name *</label>
              <input id="p-name" required maxLength={80} value={name} onChange={(e) => setName(e.target.value)}
                placeholder="e.g. Daily Internet" className={field} />
            </div>
            <div className="grid gap-5 sm:grid-cols-2">
              <div>
                <label htmlFor="p-duration" className={label}>Duration *</label>
                <input id="p-duration" type="number" required min={1} step={1} value={duration}
                  onChange={(e) => setDuration(e.target.value)} className={field} />
              </div>
              <div>
                <label htmlFor="p-unit" className={label}>Unit *</label>
                <select id="p-unit" value={unit} onChange={(e) => setUnit(e.target.value)} className={field}>
                  <option value="60">Minutes</option>
                  <option value="3600">Hours</option>
                  <option value="86400">Days</option>
                </select>
              </div>
            </div>
            <div>
              <label htmlFor="p-desc" className={label}>Description</label>
              <textarea id="p-desc" maxLength={500} rows={3} value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="Optional description for this package" className={field} />
            </div>
          </FormSection>

          <FormSection icon={<Lightning size={18} weight="duotone" aria-hidden />}
            title="Speed and performance" hint="Maximum speeds applied per connected user.">
            <div className="grid gap-5 sm:grid-cols-2">
              <div>
                <label htmlFor="p-up" className={label}>Upload speed (Mbps) *</label>
                <input id="p-up" type="number" required min="0.1" max="10000" step="0.1" value={upload}
                  onChange={(e) => setUpload(e.target.value)} className={field} />
              </div>
              <div>
                <label htmlFor="p-down" className={label}>Download speed (Mbps) *</label>
                <input id="p-down" type="number" required min="0.1" max="10000" step="0.1" value={download}
                  onChange={(e) => setDownload(e.target.value)} className={field} />
              </div>
            </div>
            <p className={hint}>Data volume is unlimited. The duration above controls the session allowance.</p>
          </FormSection>

          <FormSection icon={<Money size={18} weight="duotone" aria-hidden />}
            title="Pricing and devices" hint="What customers pay, and how many devices one voucher covers.">
            <div className="grid gap-5 sm:grid-cols-2">
              <div>
                <label htmlFor="p-price" className={label}>Price (UGX) *</label>
                <input id="p-price" type="number" required min="0" max="9999999999" step="0.01" value={price}
                  onChange={(e) => setPrice(e.target.value)} placeholder="2000" className={field} />
              </div>
              <div>
                <label htmlFor="p-devices" className={label}>Devices *</label>
                <input id="p-devices" type="number" required min="1" max="100" step="1" value={devices}
                  onChange={(e) => setDevices(e.target.value)} className={field} />
                <p className={hint}>Simultaneous devices per voucher.</p>
              </div>
            </div>
          </FormSection>

          <FormSection icon={<Eye size={18} weight="duotone" aria-hidden />}
            title="Availability and visibility" hint="Choose how customers can reach this package.">
            <fieldset>
              <legend className={label}>Package availability *</legend>
              <div className="mt-2 flex flex-wrap gap-2.5">
                {["ACTIVE", "HIDDEN", "DISABLED"].map((s) => (
                  <label key={s}
                    className={`flex min-h-[44px] cursor-pointer items-center rounded-lg border px-4 text-xs font-medium capitalize transition ${
                      availability === s
                        ? "border-blue-500 bg-blue-50 text-[var(--accent-text)]"
                        : "border-[var(--border-strong)] text-[var(--fg-muted)] hover:bg-[var(--subtle)]"
                    }`}>
                    <input type="radio" name="availability" value={s} checked={availability === s}
                      onChange={() => setAvailability(s)} className="mr-2 accent-blue-700" />
                    {s.toLowerCase()}
                  </label>
                ))}
              </div>
              <p className={hint}>
                Active: visible on the portal. Hidden: voucher-only. Disabled: not purchasable.
                Existing vouchers keep their access.
              </p>
            </fieldset>

            <label className="flex cursor-pointer items-start gap-3 rounded-lg border border-[var(--border)] p-4 transition hover:bg-[var(--subtle)]">
              <input type="checkbox" checked={featured} onChange={(e) => setFeatured(e.target.checked)}
                className="mt-0.5 h-4 w-4 shrink-0 accent-blue-700" />
              <span>
                <span className="block text-sm font-medium text-[var(--fg)]">Featured package</span>
                <span className="mt-0.5 block text-xs text-[var(--fg-muted)]">Appears first on the portal with a Popular badge.</span>
              </span>
            </label>

            <div className="grid gap-5 sm:grid-cols-2">
              <div>
                <label htmlFor="p-order" className={label}>Display order</label>
                <input id="p-order" type="number" required min="0" step="1" max="2147483647" value={order}
                  onChange={(e) => setOrder(e.target.value)} className={field} />
                <p className={hint}>Breaks ties between packages with the same featured status and price.</p>
              </div>
              <div>
                <label htmlFor="p-router" className={label}>Router</label>
                <select id="p-router" value={routerId} onChange={(e) => setRouterId(e.target.value)} className={field}>
                  <option value="">All routers</option>
                  {routers.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
                </select>
                <p className={hint}>Limit this plan to one site. Vouchers stay valid on every router you own.</p>
              </div>
            </div>
          </FormSection>

          {routers.length === 0 && (
            <p className="flex items-start gap-2 px-1 text-xs text-[var(--fg-muted)]">
              <CellTower size={14} className="mt-0.5 shrink-0" aria-hidden />
              No routers linked yet, so this package will apply everywhere.
            </p>
          )}

          <ErrorNotice text={error} />
          <FormActions busy={saving} busyLabel="Saving…" submitLabel="Create package" cancelHref="/dashboard/packages" />
        </FormLayout>
      </form>
    </div>
  );
}
