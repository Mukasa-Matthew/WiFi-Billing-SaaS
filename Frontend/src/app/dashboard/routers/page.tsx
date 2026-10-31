"use client";
import { mutateCache } from "@/lib/cacheInvalidation";
import React, { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import api from "@/lib/api";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import { notify, useToast } from "@/components/ui/Toast";
import { useBusyAction } from "@/components/LoadingUI";
import { ActionMenu, ActionMenuItem } from "@/components/ui/ActionMenu";
import { useResource, Table, Badge, Notice, FilterTabs, button, date, message, panel } from "@/components/BillingUI";
import { Eye, ArrowClockwise, FileArrowDown, Clock, Terminal, Trash, Activity } from "@/components/icons";

interface RouterRec { id: number; name: string; wgIp: string; registered: boolean; cpuLoad: number; memoryUsage: number; status: string; lastSeen: string | null; uptime: string | null; hotspotReady: boolean | null; radiusReady: boolean | null; portalReady: boolean | null }
interface Task { id: number; action: string; status: string; createdAt: string }

export default function Page() {
  const nav = useRouter();
  const { data, loading, error, load } = useResource<RouterRec>("/api/routers", true);
  const [tab, setTab] = useState("ALL");
  const [selected, setSelected] = useState<RouterRec | null>(null);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [note, setNote] = useState("");
  const [command, setCommand] = useState("");
  const [commandExpiry, setCommandExpiry] = useState<string | null>(null);

  const open = async (r: RouterRec) => {
    setSelected(r); setCommand("");
    try { setTasks((await api.get<Task[]>(`/api/routers/${r.id}/tasks`)).data); }
    catch (e) { setNote(message(e)); }
  };
  // One router action at a time: a second click while one is in flight is dropped.
  const { run } = useBusyAction<number>();
  const action = (r: RouterRec, actionName: string) => run(r.id, async () => {
    setNote("");
    const label = actionName.replace("_", " ").toLowerCase();
    try {
      await notify.promise(api.post(`/api/routers/${r.id}/tasks`, { action: actionName }), {
        loading: `Sending ${label} to ${r.name}…`,
        success: `Requested. ${r.name} will apply it at its next check-in.`,
      });
    } catch { return; } // the toast already shows why
    try {
      mutateCache.routerChanged();
      await open(r);

      // Auto-poll tasks for immediate acknowledgment update
      let attempts = 0;
      const timer = setInterval(async () => {
        attempts++;
        try {
          const res = (await api.get<Task[]>(`/api/routers/${r.id}/tasks`)).data;
          setTasks(res);
          const latest = res[0];
          if (latest && latest.action === actionName && latest.status === "APPLIED") {
            clearInterval(timer);
            toast("success", `${actionName.replace("_", " ")} successfully applied to ${r.name}!`);
          } else if (attempts >= 8) {
            clearInterval(timer);
          }
        } catch (_) {
          clearInterval(timer);
        }
      }, 1500);
    } catch (e) { setNote(message(e)); }
  });
  const toast = useToast();
  const { confirm, dialog } = useConfirmDialog();
  const remove = (r: RouterRec) => confirm({
    title: "Delete router",
    body: `Are you sure you want to delete ${r.name}? Active customer sessions and payment history linked to this router will be affected.`,
    confirmLabel: "Delete router",
    onConfirm: async () => {
      try { 
        await api.delete(`/api/routers/${r.id}`); 
        toast("success", `${r.name} deleted.`); 
        await load(); 
        setSelected(null); 
      }
      catch (e) { 
        toast("error", message(e)); 
      }
    },
  });
  // Each command carries a fresh one-time setup code from the owner-only endpoint.
  const showCommand = (r: RouterRec, install = false) => run(r.id, async () => {
    try {
      const p = (await api.post<{ path: string; expiresAt: string }>(`/api/routers/${r.id}/setup-code`, { purpose: install ? "MANAGEMENT" : "FULL" })).data;
      const url = `${window.location.origin}${p.path}`;
      setCommandExpiry(p.expiresAt);
      // open() resets the command, so select the router first, then show it.
      if (!selected || selected.id !== r.id) await open(r);
      // The downloaded script holds the router's RADIUS secret: delete it from flash either way.
      setCommand(`:do { /tool fetch url="${url}" dst-path=wb-setup.rsc; /import wb-setup.rsc; /file remove wb-setup.rsc } on-error={ :do { /file remove wb-setup.rsc } on-error={}; :put "Setup failed. Check router logs." }`);
    } catch (e) { setNote(message(e)); }
  });

  const tabs = [
    { key: "ALL", label: "All", count: data.length },
    { key: "ONLINE", label: "Online", count: data.filter(r => r.status === "online").length },
    { key: "OFFLINE", label: "Offline", count: data.filter(r => r.status !== "online").length },
  ];

  return (
    <div className="space-y-6">{dialog}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-[1.65rem] font-bold leading-tight tracking-[-0.02em] text-[var(--fg)]">MikroTik routers</h1>
          <p className="mt-1 text-sm text-[var(--fg-muted)]">To get started, add a MikroTik router by clicking 'Link a MikroTik'.</p>
        </div>
        <Link href="/dashboard/routers/new" className={button}>Link a MikroTik</Link>
      </div>
      <Notice text={error || note} />
      <FilterTabs loading={loading} options={tabs} value={tab} onChange={setTab} />
      <Table
        loading={loading}
        rows={data.filter(r => tab === "ALL" || (tab === "ONLINE" && r.status === "online") || (tab === "OFFLINE" && r.status !== "online"))}
        columns={[
          { label: "Router", render: r => <Link href={`/dashboard/routers/${r.id}`} className="font-semibold text-[var(--fg)] hover:underline">{r.name}</Link> },
          { label: "Provisioning", render: r => <Badge tone={r.status === "online" ? "green" : r.status === "degraded" ? "red" : "amber"}>{r.status === "online" ? "Verified" : r.status === "degraded" ? "Needs attention" : r.status === "provisioned" ? "Awaiting checks" : r.registered ? "Registered" : "Awaiting router"}</Badge> },
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
              <ActionMenu ariaLabel={`Actions for ${r.name}`}>
                <ActionMenuItem icon={<Activity size={14} />} label="Monitor live" onClick={() => nav.push(`/dashboard/routers/${r.id}`)} />
                <ActionMenuItem icon={<Eye size={14} />} label="View / manage" onClick={() => open(r)} />
                <ActionMenuItem icon={<Terminal size={14} />} label="Reprovision command" onClick={() => showCommand(r)} />
                <ActionMenuItem icon={<FileArrowDown size={14} />} label="Sync hotspot files" onClick={() => action(r, "SYNC_PORTAL")} />
                <ActionMenuItem icon={<Clock size={14} />} label="Sync router time" onClick={() => action(r, "SYNC_TIME")} />
                <ActionMenuItem icon={<ArrowClockwise size={14} />} label="Install mgmt agent" onClick={() => showCommand(r, true)} />
                <ActionMenuItem icon={<Trash size={14} />} label="Delete router" danger onClick={() => remove(r)} />
              </ActionMenu>
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
                className="h-24 w-full rounded-lg border border-[var(--border-strong)] bg-[var(--sidebar-admin)] p-3 font-mono text-xs text-slate-200" />
              <p className="mt-1 text-xs text-[var(--fg-muted)]">Select the command and copy it. It works once{commandExpiry ? ` and expires at ${new Date(commandExpiry).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}` : ""}. Generating another command cancels this one.</p>
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
