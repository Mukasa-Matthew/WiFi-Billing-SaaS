"use client";
import React, { useEffect, useRef, useState } from "react";
import Link from "next/link";
import api from "@/lib/api";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";
import { useResource, Table, Badge, Notice, FilterTabs, button, date, message, panel } from "@/components/BillingUI";
import { DotsThreeVertical, Eye, ArrowClockwise, FileArrowDown, Clock, Terminal, Trash } from "@phosphor-icons/react";

interface RouterRec { id: number; name: string; token: string; wgIp: string; wgPublicKey: string | null; cpuLoad: number; memoryUsage: number; status: string; lastSeen: string | null; uptime: string | null; hotspotReady: boolean | null; radiusReady: boolean | null; portalReady: boolean | null }
interface Task { id: number; action: string; status: string; createdAt: string }

function Kebab({ children }: { children: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState({ top: 0, left: 0 });
  const ref = useRef<HTMLDivElement>(null);
  const btnRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const close = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, []);
  const toggle = () => {
    if (!open && btnRef.current) {
      const r = btnRef.current.getBoundingClientRect();
      const width = 208;
      const estimatedHeight = 230;
      const spaceBelow = window.innerHeight - r.bottom;
      setPos({
        left: Math.min(r.right - width, window.innerWidth - width - 8),
        top: spaceBelow >= estimatedHeight ? r.bottom + 6 : Math.max(8, r.top - estimatedHeight - 6),
      });
    }
    setOpen(!open);
  };
  return (
    <div ref={ref}>
      <button ref={btnRef} aria-label="Router actions" aria-expanded={open} onClick={toggle}
        className="rounded-lg p-1.5 text-[var(--fg-muted)] hover:bg-[var(--fill)] hover:text-[var(--fg)]">
        <DotsThreeVertical size={16} />
      </button>
      {open && (
        <div className="fixed z-50 w-52 rounded-xl border border-[var(--border)] bg-[var(--surface)] py-1.5 shadow-lg"
          style={{ top: pos.top, left: pos.left }} onClick={() => setOpen(false)}>
          {children}
        </div>
      )}
    </div>
  );
}

function MenuItem({ icon, label, danger, onClick }: { icon: React.ReactNode; label: string; danger?: boolean; onClick: () => void }) {
  return (
    <button onClick={onClick}
      className={`flex w-full items-center gap-2.5 px-3.5 py-2 text-left text-xs font-medium transition ${danger ? "text-red-600 hover:bg-red-50" : "text-[var(--fg-body)] hover:bg-[var(--subtle)]"}`}>
      {icon}{label}
    </button>
  );
}

export default function Page() {
  const { data, loading, error, load } = useResource<RouterRec>("/api/routers", true);
  const [tab, setTab] = useState("ALL");
  const [selected, setSelected] = useState<RouterRec | null>(null);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [note, setNote] = useState("");
  const [command, setCommand] = useState("");

  const open = async (r: RouterRec) => {
    setSelected(r); setCommand("");
    try { setTasks((await api.get<Task[]>(`/api/routers/${r.id}/tasks`)).data); }
    catch (e) { setNote(message(e)); }
  };
  const action = async (r: RouterRec, action: string) => {
    try {
      await api.post(`/api/routers/${r.id}/tasks`, { action });
      setNote("Action queued. Applied status requires a successful router acknowledgement.");
      await open(r);
    } catch (e) { setNote(message(e)); }
  };
  const toast = useToast();
  const { confirm, dialog } = useConfirmDialog();
  const remove = (r: RouterRec) => confirm({
    title: "Delete router",
    body: `Delete ${r.name}? Payment history may prevent deletion.`,
    confirmLabel: "Delete",
    onConfirm: async () => {
      try { await api.delete(`/api/routers/${r.id}`); toast("success", `${r.name} deleted.`); await load(); setSelected(null); }
      catch (e) { toast("error", message(e)); }
    },
  });
  const showCommand = (r: RouterRec, install = false) => {
    const url = `${window.location.origin}/api/router/provision/${r.token}${install ? "/install-management.rsc" : ""}`;
    setCommand(`:do { /tool fetch url="${url}" dst-path=wb-setup.rsc; /import wb-setup.rsc } on-error={ :put "Setup failed. Check router logs." }`);
    if (!selected || selected.id !== r.id) void open(r);
  };

  const tabs = [
    { key: "ALL", label: "All", count: data.length },
    { key: "ONLINE", label: "Online", count: data.filter(r => r.status === "online").length },
    { key: "OFFLINE", label: "Offline", count: data.filter(r => r.status !== "online").length },
  ];

  return (
    <div className="space-y-6">{dialog}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-[var(--fg)]">MikroTik routers</h1>
          <p className="mt-1 text-sm text-[var(--fg-muted)]">To get started, add a MikroTik router by clicking 'Link a MikroTik'.</p>
        </div>
        <Link href="/dashboard/routers/new" className={button}>Link a MikroTik</Link>
      </div>
      <Notice text={error || note} />
      <FilterTabs options={tabs} value={tab} onChange={setTab} />
      <Table
        rows={data.filter(r => tab === "ALL" || (tab === "ONLINE" ? r.status === "online" : r.status !== "online"))}
        loading={loading}
        columns={[
          { label: "Name", render: r => <button onClick={() => open(r)} className="font-medium text-[var(--accent-text)] hover:underline">{r.name}</button> },
          { label: "Provisioning", render: r => <Badge tone={r.status === "online" ? "green" : r.status === "degraded" ? "red" : "amber"}>{r.status === "online" ? "Verified" : r.status === "degraded" ? "Needs attention" : r.status === "provisioned" ? "Awaiting checks" : r.wgPublicKey ? "Registered" : "Awaiting router"}</Badge> },
          { label: "CPU", render: r => r.lastSeen ? `${r.cpuLoad || 0}%` : "—" },
          { label: "Memory", render: r => r.lastSeen ? `${r.memoryUsage || 0}%` : "—" },
          { label: "Status", render: r => <Badge tone={r.status === "online" ? "green" : "amber"}>{r.status}</Badge> },
          { label: "Connection", render: r => r.wgIp ? <Badge tone="green">WireGuard</Badge> : <Badge tone="slate">Pending</Badge> },
          { label: "Health", render: r => {
            const flags = [r.hotspotReady, r.radiusReady, r.portalReady];
            if (flags.every(f => f === true)) return <Badge tone="green">Healthy</Badge>;
            if (flags.some(f => f === false)) return <Badge tone="red">Degraded</Badge>;
            return <Badge tone={r.lastSeen ? "slate" : "amber"}>{r.lastSeen ? "Reporting" : "No data"}</Badge>;
          }},
          { label: "Remote access", render: r => r.wgIp ? <Badge tone="green">Enabled</Badge> : <Badge tone="slate">Off</Badge> },
          { label: "WinBox address", render: r => r.wgIp
            ? <button title="Copy WinBox address" onClick={() => { void navigator.clipboard?.writeText(`${r.wgIp}`); toast("success", "WinBox address copied — connect via the WireGuard tunnel."); }} className="font-mono text-xs text-[var(--accent-text)] hover:underline">{r.wgIp}</button>
            : "—" },
          { label: "Last telemetry", render: r => <span className="text-[var(--fg-muted)]">{date(r.lastSeen)}</span> },
          {
            label: "", render: r => (
              <Kebab>
                <MenuItem icon={<Eye size={14} />} label="View / manage" onClick={() => open(r)} />
                <MenuItem icon={<Terminal size={14} />} label="Reprovision command" onClick={() => showCommand(r)} />
                <MenuItem icon={<FileArrowDown size={14} />} label="Sync hotspot files" onClick={() => action(r, "SYNC_PORTAL")} />
                <MenuItem icon={<Clock size={14} />} label="Sync router time" onClick={() => action(r, "SYNC_TIME")} />
                <MenuItem icon={<ArrowClockwise size={14} />} label="Install mgmt agent" onClick={() => showCommand(r, true)} />
                <MenuItem icon={<Trash size={14} />} label="Delete" danger onClick={() => remove(r)} />
              </Kebab>
            )
          },
        ]}
      />
      {selected && (
        <section className={`${panel} space-y-5 p-5`}>
          <div className="flex justify-between">
            <h2 className="text-lg font-semibold text-[var(--fg)]">{selected.name}</h2>
            <button onClick={() => setSelected(null)} className="text-xs font-medium text-[var(--fg-muted)] hover:text-[var(--fg)]">Close</button>
          </div>
          <p className="text-xs leading-6 text-[var(--fg-muted)]">
            WinBox over your routed WireGuard connection: <code className="text-[var(--accent-text)]">{selected.wgIp || "No tunnel IP"}:8291</code>. This is a private tunnel address, not a public remote-access endpoint.
          </p>
          {command && (
            <div>
              <p className="mb-2 text-xs font-medium text-amber-700">Run this in the router terminal. Full reprovisioning moves customer interfaces; management-agent installation only enables sync and UTC/NTP time.</p>
              <textarea aria-label="Router setup command" readOnly value={command} onFocus={e => e.target.select()}
                className="h-24 w-full rounded-lg border border-[var(--border-strong)] bg-[#0B1F36] p-3 font-mono text-xs text-slate-200" />
              <p className="mt-1 text-xs text-[var(--fg-muted)]">Select the command and copy it. Keep this token private.</p>
            </div>
          )}
          <Table rows={tasks} columns={[
            { label: "Action", render: t => t.action },
            { label: "Status", render: t => <Badge tone={t.status === "APPLIED" ? "green" : t.status === "FAILED" ? "red" : "amber"}>{t.status}</Badge> },
            { label: "Requested", render: t => date(t.createdAt) },
          ]} />
        </section>
      )}
    </div>
  );
}
