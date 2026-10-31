"use client";
import { BusyLabel, useSingleFlight } from "@/components/LoadingUI";
import { mutateCache } from "@/lib/cacheInvalidation";
import React, { useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import api from "@/lib/api";
import Link from "next/link";
import { useResource, useApi, Table, ErrorNotice, FilterTabs, Segmented, Badge, field, button, buttonGhost, message, PageContainer, PageHeader, EmptyState, label, bytes, relativeTime, useNow, date, type Column } from "@/components/BillingUI";
import { Dialog } from "@/components/ui/Dialog";
import { useToast } from "@/components/ui/Toast";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import { ActionMenu, ActionMenuItem } from "@/components/ui/ActionMenu";
import { Users, UserPlus, FloppyDisk, NotePencil, User as UserIcon, Funnel, Columns, DownloadSimple, Eye, Copy, Prohibit, X, Check } from "@/components/icons";

type Status = "active" | "ready" | "expired" | "suspended" | "new";
interface Customer {
  id: number; number: number; name: string; phone: string | null; notes: string | null; createdAt: string;
  status: Status; voucher: string | null; packageName: string | null; sessionStart: string | null; sessionEnd: string | null;
  online: boolean; dataBytes: number; lastOnline: string | null; purchases: number; totalSpent: number;
}
interface History {
  vouchers: { code: string; packageName: string | null; status: string; createdAt: string | null; sessionStart: string | null; sessionEnd: string | null; dataBytes: number; online: boolean }[];
  payments: { reference: string; amount: number; method: string; voucher: string | null; createdAt: string }[];
}

const STATUS: Record<Status, { label: string; tone: string }> = {
  active: { label: "Active", tone: "green" },
  ready: { label: "Not started", tone: "blue" },
  expired: { label: "Expired", tone: "slate" },
  suspended: { label: "Suspended", tone: "red" },
  new: { label: "No purchase", tone: "slate" },
};
const VOUCHER_STATUS: Record<string, { label: string; tone: string }> = {
  active: { label: "In use", tone: "green" }, unused: { label: "Not started", tone: "blue" },
  expired: { label: "Expired", tone: "slate" }, used: { label: "Used up", tone: "slate" }, revoked: { label: "Revoked", tone: "red" },
};
// "Not started" is paid, valid access, so it counts as active.
const TABS = [
  { key: "active", label: "Active", match: (s: Status) => s === "active" || s === "ready" },
  { key: "expired", label: "Expired", match: (s: Status) => s === "expired" },
  { key: "suspended", label: "Suspended", match: (s: Status) => s === "suspended" },
  { key: "all", label: "All", match: () => true },
];
// A voucher that can still be used is the only kind worth revoking.
const revocable = (c: Customer) => !!c.voucher && (c.status === "active" || c.status === "ready");

interface Filters { pkg: string; connection: "any" | "online" | "offline"; name: "any" | "named" | "unnamed"; joined: "any" | "7" | "30" }
const NO_FILTERS: Filters = { pkg: "", connection: "any", name: "any", joined: "any" };
const filterCount = (f: Filters) => [f.pkg !== "", f.connection !== "any", f.name !== "any", f.joined !== "any"].filter(Boolean).length;

/* Optional columns; the choice is a per-device preference. */
const OPTIONAL = ["Phone", "Voucher", "Package", "Session start", "Session end", "Data used", "Spent", "Last online"] as const;
type Optional = (typeof OPTIONAL)[number];
const COLS_KEY = "wb_customer_columns_hidden";
const columnStore = {
  subscribe(cb: () => void) { window.addEventListener("storage", cb); window.addEventListener(COLS_KEY, cb); return () => { window.removeEventListener("storage", cb); window.removeEventListener(COLS_KEY, cb); }; },
  get() { try { return localStorage.getItem(COLS_KEY) || "[]"; } catch { return "[]"; } },
  set(v: Set<Optional>) { try { localStorage.setItem(COLS_KEY, JSON.stringify([...v])); } catch { /* unavailable */ } window.dispatchEvent(new Event(COLS_KEY)); },
};

const customerId = (n: number) => `C-${String(n).padStart(4, "0")}`;
const shortDateTime = (v: string | null) => v ? date(v) : null;
const ugx = (v: number) => `UGX ${Number(v || 0).toLocaleString()}`;
// Buyers recorded from a payment carry their number as a placeholder name.
const hasName = (c: Customer) => c.name.trim() !== "" && c.name !== c.phone;
const displayName = (c: Customer) => hasName(c) ? c.name : c.phone || customerId(c.number);
const initials = (c: Customer) => hasName(c) ? c.name.split(/\s+/).slice(0, 2).map((p) => p[0]).join("") : <UserIcon className="h-4 w-4" aria-hidden />;

function exportCsv(rows: Customer[]) {
  const header = ["Customer ID", "Name", "Phone", "Status", "Voucher", "Package", "Access start", "Access end", "Data used", "Spent (UGX)", "Purchases", "Last online", "Joined"];
  const body = rows.map((c) => [customerId(c.number), hasName(c) ? c.name : "", c.phone || "", STATUS[c.status].label, c.voucher || "", c.packageName || "",
    shortDateTime(c.sessionStart) || "", shortDateTime(c.sessionEnd) || "", bytes(c.dataBytes), String(c.totalSpent || 0), String(c.purchases),
    c.online ? "Online now" : shortDateTime(c.lastOnline) || "Never", shortDateTime(c.createdAt) || ""]);
  const csv = [header, ...body].map((row) => row.map((value) => `"${value.replace(/"/g, '""').replace(/^[=+@-]/, "'")}"`).join(",")).join("\r\n");
  const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
  const anchor = document.createElement("a"); anchor.href = url; anchor.download = "customers.csv"; anchor.click(); URL.revokeObjectURL(url);
}

/** Toolbar button with an anchored panel; closes on outside click or Escape. */
function Popover({ label: text, icon, count, children, align = "left" }: { label: string; icon: React.ReactNode; count?: number; children: React.ReactNode; align?: "left" | "right" }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const away = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false); };
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("mousedown", away); document.addEventListener("keydown", esc);
    return () => { document.removeEventListener("mousedown", away); document.removeEventListener("keydown", esc); };
  }, [open]);
  return (
    <div ref={ref} className="relative">
      <button type="button" aria-expanded={open} onClick={() => setOpen(!open)}
        className={`inline-flex h-9 items-center gap-2 rounded-lg border px-3 text-xs font-medium transition ${open || count ? "border-[var(--border-strong)] bg-[var(--fill)] text-[var(--fg)]" : "border-[var(--border)] text-[var(--fg-body)] hover:bg-[var(--fill)]"}`}>
        {icon}{text}
        {!!count && <span className="num rounded-full bg-[var(--fg)] px-1.5 text-[10px] font-semibold leading-4 text-[var(--surface)]">{count}</span>}
      </button>
      {open && (
        <div role="dialog" aria-label={text} className={`absolute top-full z-30 mt-2 w-72 rounded-xl border border-[var(--border)] bg-[var(--surface)] p-4 shadow-[var(--shadow-overlay)] ${align === "right" ? "right-0" : "left-0"}`}>
          {children}
        </div>
      )}
    </div>
  );
}

function Detail({ label: text, children }: { label: string; children: React.ReactNode }) {
  return <div className="min-w-0"><dt className="text-xs text-[var(--fg-muted)]">{text}</dt><dd className="num mt-0.5 truncate text-[13px] font-medium text-[var(--fg)]">{children}</dd></div>;
}

function CustomerDetails({ customer: c, now, onClose, onEdit }: { customer: Customer; now: number; onClose: () => void; onEdit: () => void }) {
  const history = useApi<History>(`/api/customers/${c.id}/history`);
  const h = history.data;
  return (
    <Dialog wide title={displayName(c)} description={`${customerId(c.number)} · customer since ${new Date(c.createdAt).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" })}`} onClose={onClose}>
      <div className="space-y-6">
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={STATUS[c.status].tone}>{STATUS[c.status].label}</Badge>
          {c.online && <span className="inline-flex items-center gap-1.5 text-xs font-medium text-emerald-700"><span className="h-2 w-2 rounded-full bg-emerald-500" />Online now</span>}
        </div>
        <dl className="grid grid-cols-2 gap-x-6 gap-y-4 rounded-xl border border-[var(--border)] bg-[var(--subtle)] p-4 sm:grid-cols-3">
          <Detail label="Phone">{c.phone || "—"}</Detail>
          <Detail label="Purchases">{c.purchases.toLocaleString()}</Detail>
          <Detail label="Total spent">{ugx(c.totalSpent)}</Detail>
          <Detail label="Data used">{bytes(c.dataBytes)}</Detail>
          <Detail label="Last online">{c.online ? "Online now" : c.lastOnline ? relativeTime(c.lastOnline, now || undefined) : "Never"}</Detail>
          <Detail label="Current package">{c.packageName || "—"}</Detail>
        </dl>
        {c.notes && <p className="rounded-lg border border-[var(--border)] px-4 py-3 text-[13px] text-[var(--fg-body)]">{c.notes}</p>}

        <ErrorNotice text={history.error ? `History couldn't be loaded: ${history.error}` : ""} />
        <section>
          <h3 className="mb-2 text-[13px] font-semibold text-[var(--fg)]">Vouchers</h3>
          <div className="overflow-x-auto rounded-xl border border-[var(--border)]">
            <table className="w-full whitespace-nowrap text-left text-[13px]">
              <thead className="border-b border-[var(--border)] bg-[var(--subtle)] text-xs text-[var(--fg-muted)]">
                <tr><th className="px-4 py-2 font-medium">Voucher</th><th className="px-4 py-2 font-medium">Status</th><th className="px-4 py-2 font-medium">Access</th><th className="px-4 py-2 text-right font-medium">Data used</th></tr>
              </thead>
              <tbody className="divide-y divide-[var(--border-soft)]">
                {!h && !history.error && <tr><td colSpan={4} className="px-4 py-6 text-center text-[var(--fg-muted)]">Loading…</td></tr>}
                {h && !h.vouchers.length && <tr><td colSpan={4} className="px-4 py-6 text-center text-[var(--fg-muted)]">No vouchers yet.</td></tr>}
                {h?.vouchers.map((v) => {
                  const st = VOUCHER_STATUS[v.status] ?? { label: v.status, tone: "slate" };
                  return (
                    <tr key={v.code}>
                      <td className="px-4 py-2.5"><span className="font-mono text-xs font-semibold text-[var(--fg)]">{v.code}</span>{v.packageName && <span className="block text-[11px] text-[var(--fg-muted)]">{v.packageName}</span>}</td>
                      <td className="px-4 py-2.5"><Badge tone={st.tone}>{st.label}</Badge>{v.online && <span className="ml-2 text-xs font-medium text-emerald-700">Online</span>}</td>
                      <td className="num px-4 py-2.5 text-[var(--fg-body)]">{v.sessionStart ? <>{shortDateTime(v.sessionStart)}<span className="block text-xs text-[var(--fg-muted)]">{v.sessionEnd ? `to ${shortDateTime(v.sessionEnd)}` : ""}</span></> : <span className="text-[var(--fg-faint)]">Not started</span>}</td>
                      <td className="num px-4 py-2.5 text-right">{bytes(v.dataBytes)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </section>
        <section>
          <h3 className="mb-2 text-[13px] font-semibold text-[var(--fg)]">Payments</h3>
          <div className="overflow-x-auto rounded-xl border border-[var(--border)]">
            <table className="w-full whitespace-nowrap text-left text-[13px]">
              <thead className="border-b border-[var(--border)] bg-[var(--subtle)] text-xs text-[var(--fg-muted)]">
                <tr><th className="px-4 py-2 font-medium">Date</th><th className="px-4 py-2 font-medium">Method</th><th className="px-4 py-2 font-medium">Voucher</th><th className="px-4 py-2 text-right font-medium">Amount</th></tr>
              </thead>
              <tbody className="divide-y divide-[var(--border-soft)]">
                {!h && !history.error && <tr><td colSpan={4} className="px-4 py-6 text-center text-[var(--fg-muted)]">Loading…</td></tr>}
                {h && !h.payments.length && <tr><td colSpan={4} className="px-4 py-6 text-center text-[var(--fg-muted)]">No recorded payments.</td></tr>}
                {h?.payments.map((p) => (
                  <tr key={p.reference}>
                    <td className="num px-4 py-2.5 text-[var(--fg-body)]">{shortDateTime(p.createdAt)}</td>
                    <td className="px-4 py-2.5 text-[var(--fg-body)]">{p.method}</td>
                    <td className="px-4 py-2.5 font-mono text-xs">{p.voucher || "—"}</td>
                    <td className="num px-4 py-2.5 text-right font-medium text-[var(--fg)]">{ugx(p.amount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
        <div className="flex justify-end gap-2 border-t border-[var(--border)] pt-5">
          <button type="button" onClick={onClose} className={buttonGhost}>Close</button>
          <button type="button" onClick={onEdit} className={button}><NotePencil className="h-4 w-4" />Edit details</button>
        </div>
      </div>
    </Dialog>
  );
}

export default function Page() {
  const { data, loading, error, load } = useResource<Customer>("/api/customers", 60000);
  const now = useNow(60000);
  const [tab, setTab] = useState("active");
  const [filters, setFilters] = useState<Filters>(NO_FILTERS);
  const hiddenRaw = useSyncExternalStore(columnStore.subscribe, columnStore.get, () => "[]");
  const hidden = useMemo(() => { try { return new Set<Optional>(JSON.parse(hiddenRaw)); } catch { return new Set<Optional>(); } }, [hiddenRaw]);
  const [selected, setSelected] = useState<Set<React.Key>>(() => new Set());
  const [view, setView] = useState<Customer | null>(null);
  const [edit, setEdit] = useState<Partial<Customer> | null>(null);
  const [note, setNote] = useState("");
  const [saving, setSaving] = useState(false);
  const [revoking, setRevoking] = useState(false);
  const toast = useToast();
  const { confirm, dialog } = useConfirmDialog();

  const toggleColumn = (c: Optional) => { const next = new Set(hidden); if (next.has(c)) next.delete(c); else next.add(c); columnStore.set(next); };

  const packages = useMemo(() => [...new Set(data.map((c) => c.packageName).filter((p): p is string => !!p))].sort(), [data]);
  const filtered = useMemo(() => {
    const since = filters.joined === "any" || !now ? 0 : now - Number(filters.joined) * 86400000;
    return data.filter((c) =>
      (!filters.pkg || c.packageName === filters.pkg)
      && (filters.connection === "any" || (filters.connection === "online") === c.online)
      && (filters.name === "any" || (filters.name === "named") === hasName(c))
      && (!since || Date.parse(c.createdAt) >= since));
  }, [data, filters, now]);
  const counts = useMemo(() => Object.fromEntries(TABS.map((t) => [t.key, filtered.filter((c) => t.match(c.status)).length])), [filtered]);
  const online = useMemo(() => data.filter((c) => c.online).length, [data]);
  const rows = useMemo(() => {
    const match = TABS.find((t) => t.key === tab)!.match;
    return filtered.filter((c) => match(c.status));
  }, [filtered, tab]);
  // Selection only ever refers to rows on screen.
  const picked = useMemo(() => rows.filter((c) => selected.has(c.id)), [rows, selected]);
  const changeTab = (t: string) => { setTab(t); setSelected(new Set()); };

  const openEdit = (customer: Customer) => { setNote(""); setView(null); setEdit({ ...customer, name: hasName(customer) ? customer.name : "" }); };

  const singleFlight = useSingleFlight();
  const save = singleFlight(async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    try {
      const body = { name: edit?.name, phone: edit?.phone, notes: edit?.notes };
      if (edit?.id) { await api.put(`/api/customers/${edit.id}`, body); toast("success", "Customer updated."); mutateCache.customerChanged(); }
      else { await api.post("/api/customers", body); toast("success", "Customer added."); mutateCache.customerChanged(); }
      setEdit(null);
      await load();
    } catch (err) { setNote(message(err)); }
    finally { setSaving(false); }
  });

  const revoke = (targets: Customer[]) => {
    const list = targets.filter(revocable);
    if (!list.length) { toast("error", "None of the selected customers has a voucher that can be revoked."); return; }
    const one = list.length === 1 ? list[0] : null;
    confirm({
      title: one ? `Revoke voucher ${one.voucher}?` : `Revoke ${list.length} vouchers?`,
      body: `${one ? `${displayName(one)} is` : "These customers are"} disconnected straight away and the voucher${one ? "" : "s"} can't be used again. This can't be undone.${list.length < targets.length ? ` ${targets.length - list.length} selected customer${targets.length - list.length === 1 ? " has" : "s have"} no usable voucher and will be skipped.` : ""}`,
      confirmLabel: one ? "Revoke voucher" : `Revoke ${list.length} vouchers`,
      onConfirm: async () => {
        setRevoking(true); setNote("");
        let failed = 0;
        for (const c of list) {
          try { await api.post(`/api/vouchers/${encodeURIComponent(c.voucher!)}/revoke`); } catch { failed++; }
        }
        setRevoking(false);
        mutateCache.voucherChanged();
        setSelected(new Set());
        await load();
        if (failed) setNote(`${failed} of ${list.length} voucher${list.length === 1 ? "" : "s"} couldn't be revoked. Try again, or revoke from the Vouchers page.`);
        else toast("success", one ? `Voucher ${one.voucher} revoked.` : `${list.length} vouchers revoked.`);
      },
    });
  };

  const copyPhone = async (c: Customer) => {
    try { await navigator.clipboard.writeText(c.phone!); toast("success", "Phone number copied."); }
    catch { toast("error", "Couldn't copy. Your browser blocked clipboard access."); }
  };

  const allColumns: (Column<Customer> & { key: "Customer" | "Status" | "Actions" | Optional })[] = [
    {
      key: "Customer", label: "Customer", render: (c) => (
        <button type="button" onClick={() => setView(c)} className="flex min-w-44 items-center gap-3 text-left">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-[var(--fill)] text-xs font-semibold uppercase text-[var(--fg-muted)]">{initials(c)}</span>
          <span className="min-w-0">
            {hasName(c) ? <span className="block truncate font-medium text-[var(--fg)] hover:underline">{c.name}</span> : <span className="block text-[var(--fg-muted)] hover:underline">No name yet</span>}
            <span className="num block text-[11px] text-[var(--fg-faint)]">
              {customerId(c.number)}
              {c.phone && <span className="sm:hidden"> · {c.phone}</span>}
              {c.purchases > 0 && <span className="max-sm:hidden"> · {c.purchases} purchase{c.purchases === 1 ? "" : "s"}</span>}
            </span>
          </span>
        </button>
      ),
    },
    { key: "Phone", label: "Phone", hideBelow: "sm", render: (c) => c.phone ? <span className="num whitespace-nowrap">{c.phone}</span> : <span className="text-[var(--fg-faint)]">—</span> },
    {
      key: "Voucher", label: "Voucher", hideBelow: "sm", render: (c) => c.voucher
        ? <span className="whitespace-nowrap font-mono text-xs font-semibold text-[var(--fg)]">{c.voucher}</span>
        : <span className="text-[var(--fg-faint)]">—</span>,
    },
    { key: "Package", label: "Package", hideBelow: "md", render: (c) => c.packageName ? <Badge tone="blue">{c.packageName}</Badge> : <span className="text-[var(--fg-faint)]">—</span> },
    { key: "Status", label: "Status", render: (c) => <Badge tone={STATUS[c.status].tone}>{STATUS[c.status].label}</Badge> },
    {
      key: "Session start", label: "Session start", hideBelow: "lg", render: (c) => c.sessionStart
        ? <span className="num whitespace-nowrap text-[13px] text-[var(--fg-body)]">{shortDateTime(c.sessionStart)}</span>
        : <span className="text-[13px] text-[var(--fg-faint)]">{c.status === "ready" ? "Not started" : "—"}</span>,
    },
    {
      // Ends in the future for active access; the time access ended otherwise.
      key: "Session end", label: "Session end", hideBelow: "lg", render: (c) => c.sessionEnd
        ? <span className={`num whitespace-nowrap text-[13px] ${c.status === "active" ? "text-emerald-700" : "text-[var(--fg-muted)]"}`}>{shortDateTime(c.sessionEnd)}</span>
        : <span className="text-[13px] text-[var(--fg-faint)]">{c.status === "ready" ? "Starts on first login" : "—"}</span>,
    },
    { key: "Data used", label: "Data used", align: "right", hideBelow: "md", render: (c) => <span className="num whitespace-nowrap">{bytes(c.dataBytes)}</span> },
    { key: "Spent", label: "Spent", align: "right", hideBelow: "lg", render: (c) => <span className="num whitespace-nowrap">{ugx(c.totalSpent)}</span> },
    {
      key: "Last online", label: "Last online", hideBelow: "md", render: (c) => c.online
        ? <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-[13px] font-medium text-emerald-700"><span className="h-2 w-2 rounded-full bg-emerald-500" />Online now</span>
        : c.lastOnline
          ? <span className="num block whitespace-nowrap text-[13px]"><span className="block text-[var(--fg-body)]">{shortDateTime(c.lastOnline)}</span><span className="block text-xs text-[var(--fg-muted)]">{relativeTime(c.lastOnline, now || undefined)}</span></span>
          : <span className="text-[13px] text-[var(--fg-faint)]">Never</span>,
    },
    {
      key: "Actions", label: "", align: "right", className: "w-12 !px-2", render: (c) => (
        <ActionMenu ariaLabel={`Actions for ${displayName(c)}`}>
          <ActionMenuItem icon={<Eye className="h-3.5 w-3.5" />} label="View details & history" onClick={() => setView(c)} />
          <ActionMenuItem icon={<NotePencil className="h-3.5 w-3.5" />} label="Edit details" onClick={() => openEdit(c)} />
          {c.phone && <ActionMenuItem icon={<Copy className="h-3.5 w-3.5" />} label="Copy phone number" onClick={() => copyPhone(c)} />}
          {revocable(c) && <>
            <div className="my-1.5 border-t border-[var(--border-soft)]" />
            <ActionMenuItem danger icon={<Prohibit className="h-3.5 w-3.5" />} label="Revoke voucher" onClick={() => revoke([c])} />
          </>}
        </ActionMenu>
      ),
    },
  ];
  const columns = allColumns.filter((c) => !hidden.has(c.key as Optional));
  const activeFilters = filterCount(filters);

  const toolbar = picked.length > 0 ? (
    <div className="flex flex-wrap items-center gap-2">
      <span className="num text-xs font-semibold text-[var(--fg)]">{picked.length} selected</span>
      <button type="button" onClick={() => { exportCsv(picked); toast("success", `${picked.length} customer${picked.length === 1 ? "" : "s"} exported.`); }} className={`${buttonGhost} !h-9 !px-3 !text-xs`}><DownloadSimple className="h-3.5 w-3.5" />Export</button>
      <button type="button" disabled={revoking} onClick={() => revoke(picked)} className={`${buttonGhost} !h-9 !px-3 !text-xs !text-red-700`}><BusyLabel busy={revoking} label="Revoking…"><Prohibit className="h-3.5 w-3.5" />Revoke vouchers</BusyLabel></button>
      <button type="button" onClick={() => setSelected(new Set())} className="inline-flex h-9 items-center gap-1 rounded-lg px-2 text-xs font-medium text-[var(--fg-muted)] hover:text-[var(--fg)]"><X className="h-3.5 w-3.5" />Clear</button>
    </div>
  ) : (
    <div className="flex flex-wrap items-center gap-2">
      <Popover label="Filter" icon={<Funnel className="h-3.5 w-3.5" />} count={activeFilters}>
        <div className="space-y-4">
          <div>
            <label htmlFor="filter-package" className="text-xs font-medium text-[var(--fg-muted)]">Package</label>
            <select id="filter-package" value={filters.pkg} onChange={(e) => { setFilters({ ...filters, pkg: e.target.value }); setSelected(new Set()); }} className={`${field} !mt-1.5`}>
              <option value="">Any package</option>
              {packages.map((p) => <option key={p} value={p}>{p}</option>)}
            </select>
          </div>
          <div>
            <p className="mb-1.5 text-xs font-medium text-[var(--fg-muted)]">Connection</p>
            <Segmented label="Connection" value={filters.connection} onChange={(v) => { setFilters({ ...filters, connection: v }); setSelected(new Set()); }} options={[{ key: "any", label: "Any" }, { key: "online", label: "Online now" }, { key: "offline", label: "Offline" }]} />
          </div>
          <div>
            <p className="mb-1.5 text-xs font-medium text-[var(--fg-muted)]">Name</p>
            <Segmented label="Name" value={filters.name} onChange={(v) => { setFilters({ ...filters, name: v }); setSelected(new Set()); }} options={[{ key: "any", label: "Any" }, { key: "named", label: "Has name" }, { key: "unnamed", label: "No name" }]} />
          </div>
          <div>
            <p className="mb-1.5 text-xs font-medium text-[var(--fg-muted)]">Joined</p>
            <Segmented label="Joined" value={filters.joined} onChange={(v) => { setFilters({ ...filters, joined: v }); setSelected(new Set()); }} options={[{ key: "any", label: "Any time" }, { key: "7", label: "Last 7 days" }, { key: "30", label: "Last 30 days" }]} />
          </div>
          {activeFilters > 0 && <button type="button" onClick={() => setFilters(NO_FILTERS)} className="text-xs font-semibold text-[var(--accent-text)]">Clear all filters</button>}
        </div>
      </Popover>
      <Popover label="Columns" icon={<Columns className="h-3.5 w-3.5" />}>
        <p className="mb-2 text-xs font-medium text-[var(--fg-muted)]">Show columns</p>
        <ul className="space-y-0.5">
          {OPTIONAL.map((c) => (
            <li key={c}>
              <button type="button" role="menuitemcheckbox" aria-checked={!hidden.has(c)} onClick={() => toggleColumn(c)} className="flex w-full items-center gap-2.5 rounded-md px-2 py-1.5 text-left text-[13px] text-[var(--fg-body)] hover:bg-[var(--fill)]">
                <span className={`flex h-4 w-4 items-center justify-center rounded border ${hidden.has(c) ? "border-[var(--border-strong)]" : "border-[var(--accent-text)] bg-[var(--accent-text)] text-[var(--surface)]"}`}>{!hidden.has(c) && <Check className="h-3 w-3" />}</span>
                {c}
              </button>
            </li>
          ))}
        </ul>
      </Popover>
      <button type="button" disabled={!rows.length} onClick={() => { exportCsv(rows); toast("success", `${rows.length} customer${rows.length === 1 ? "" : "s"} exported.`); }} className="inline-flex h-9 items-center gap-2 rounded-lg border border-[var(--border)] px-3 text-xs font-medium text-[var(--fg-body)] transition hover:bg-[var(--fill)] disabled:opacity-40"><DownloadSimple className="h-3.5 w-3.5" />Export</button>
    </div>
  );

  return (
    <PageContainer width="wide">
      {dialog}
      <PageHeader
        title="Customers"
        description="Everyone who has bought access. Mobile money buyers are added automatically by phone number."
        meta={!loading && data.length > 0 ? <>
          <span className="num">{data.length.toLocaleString()} customer{data.length === 1 ? "" : "s"}</span>
          <span aria-hidden className="text-[var(--fg-faint)]">·</span>
          <span className="num inline-flex items-center gap-1.5"><span className={`h-1.5 w-1.5 rounded-full ${online ? "bg-emerald-500" : "bg-[var(--fg-faint)]"}`} />{online.toLocaleString()} online now</span>
        </> : undefined}
        action={<Link href="/dashboard/customers/new" className={button}><UserPlus className="h-4 w-4" />New customer</Link>}
      />
      <ErrorNotice text={error || note} />

      {!loading && !data.length ? (
        <section className="rounded-xl border border-[var(--border)] bg-[var(--surface)] shadow-sm">
          <EmptyState
            icon={<Users className="h-8 w-8 text-slate-300" />}
            title="No customers yet"
            description="Customers appear here automatically when someone pays with mobile money. You can also add walk-in buyers yourself."
            action={<Link href="/dashboard/customers/new" className={`${button} mt-3`}><UserPlus className="h-4 w-4" />Add a customer</Link>}
          />
        </section>
      ) : (
        <>
          <div className="flex justify-center">
            <FilterTabs loading={loading} value={tab} onChange={changeTab} options={TABS.map((t) => ({ key: t.key, label: t.label, count: loading ? undefined : counts[t.key] }))} />
          </div>
          <Table
            key={tab}
            rows={rows}
            loading={loading}
            toolbar={toolbar}
            selection={{ selected, onChange: setSelected }}
            searchPlaceholder="Search name, phone or voucher…"
            emptyIcon={<Users />}
            emptyTitle={activeFilters ? "No customers match these filters" : tab === "all" ? "No customers match" : `No ${TABS.find((t) => t.key === tab)!.label.toLowerCase()} customers`}
            emptyDescription={activeFilters ? "Clear a filter to see more customers." : tab === "active" ? "Customers with access in use or waiting to start appear here." : tab === "suspended" ? "Customers whose voucher was revoked appear here." : "Try a different name, phone number or voucher code."}
            rowKey={(customer) => customer.id}
            columns={columns}
          />
        </>
      )}
      {view && <CustomerDetails customer={view} now={now} onClose={() => setView(null)} onEdit={() => openEdit(view)} />}
      {edit && (
        <Dialog
          title={edit.id ? `Edit ${edit.name || customerId(edit.number ?? 0)}` : "Add customer"}
          description="Customer records are internal — customers never see this information."
          onClose={() => !saving && setEdit(null)}
        >
          <form onSubmit={save} className="space-y-5">
            <div>
              <label htmlFor="customer-name" className={label}>Full name <span className="text-red-700">*</span></label>
              <input id="customer-name" autoFocus required maxLength={120} value={edit.name || ""} onChange={e => setEdit({ ...edit, name: e.target.value })} className={field} placeholder="e.g. Sarah Namusoke" />
            </div>
            <div>
              <label htmlFor="customer-phone" className={label}>Phone number</label>
              <input id="customer-phone" type="tel" autoComplete="tel" maxLength={32} value={edit.phone || ""} onChange={e => setEdit({ ...edit, phone: e.target.value })} className={field} placeholder="e.g. 0772 000 000" />
              <p className="mt-1.5 text-[11px] text-[var(--fg-faint)]">Future mobile money payments from this number are linked to this customer.</p>
            </div>
            <div>
              <div className="flex items-center justify-between"><label htmlFor="customer-notes" className={label}>Internal notes</label><span className="num text-[11px] text-[var(--fg-faint)]">{(edit.notes || "").length}/500</span></div>
              <textarea id="customer-notes" maxLength={500} value={edit.notes || ""} onChange={e => setEdit({ ...edit, notes: e.target.value })} className={`${field} resize-none`} rows={4} placeholder="Location, preferred package, device, or anything worth remembering" />
            </div>
            <div className="flex flex-col-reverse gap-2 border-t border-[var(--border)] pt-5 sm:flex-row sm:justify-end">
              <button type="button" onClick={() => setEdit(null)} disabled={saving} className={buttonGhost}>Cancel</button>
              <button disabled={saving} className={button}><BusyLabel busy={saving} label="Saving…"><FloppyDisk className="h-4 w-4" />{edit.id ? "Save changes" : "Add customer"}</BusyLabel></button>
            </div>
          </form>
        </Dialog>
      )}
    </PageContainer>
  );
}
