"use client";

import React, { useCallback, useEffect, useState, useMemo } from "react";
import api from "@/lib/api";
import { Dialog } from "@/components/ui/Dialog";
import { buttonGhost, message } from "@/components/BillingUI";
import {
  Users,
  MagnifyingGlass,
  Radio,
  ArrowCircleDown,
  ArrowCircleUp,
  Clock,
  UserMinus,
  ShieldWarning,
} from "@phosphor-icons/react";

interface ActiveSession {
  radAcctId: number;
  username: string;
  framedIpAddress: string | null;
  callingStationId: string | null;
  nasIpAddress: string | null;
  acctStartTime: string | null;
  acctStopTime: string | null;
  acctSessionTime: number | null;
  acctInputOctets: number | null;
  acctOutputOctets: number | null;
  totalOctets: number | null;
  status: string;
  acctTerminateCause: string | null;
}

export default function TenantActiveSessionsPage() {
  const [sessions, setSessions] = useState<ActiveSession[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");

  const [msg, setMsg] = useState<{ text: string; type: "success" | "error" } | null>(null);

  // Disconnect Confirmation Modal
  const [disconnectTarget, setDisconnectTarget] = useState<ActiveSession | null>(null);
  const [disconnecting, setDisconnecting] = useState(false);

  const fetchActiveSessions = useCallback(async () => {
    try {
      const res = await api.get<ActiveSession[]>("/api/sessions/active");
      setSessions(res.data);
    } catch (err) {
      console.error("Failed to load active sessions", err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const initial = window.setTimeout(fetchActiveSessions, 0);
    const interval = window.setInterval(fetchActiveSessions, 10000);
    return () => { window.clearTimeout(initial); window.clearInterval(interval); };
  }, [fetchActiveSessions]);

  const handleDisconnect = async () => {
    if (!disconnectTarget) return;
    try {
      setDisconnecting(true);
      await api.post("/api/sessions/disconnect", {
        username: disconnectTarget.username
      });
      setMsg({
        text: `Disconnect CoA packet transmitted for user ${disconnectTarget.username}`,
        type: "success"
      });
      setDisconnectTarget(null);
      fetchActiveSessions();
    } catch (err: unknown) {
      setMsg({
        text: message(err),
        type: "error"
      });
    } finally {
      setDisconnecting(false);
    }
  };

  const formatBytes = (bytes: number | null) => {
    if (!bytes || bytes === 0) return "0 MB";
    const k = 1024;
    const sizes = ["Bytes", "KB", "MB", "GB", "TB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i];
  };

  const formatDuration = (secs: number | null) => {
    if (!secs || secs === 0) return "0s";
    const hrs = Math.floor(secs / 3600);
    const mins = Math.floor((secs % 3600) / 60);
    const remainderSecs = secs % 60;
    if (hrs > 0) return `${hrs}h ${mins}m`;
    if (mins > 0) return `${mins}m ${remainderSecs}s`;
    return `${remainderSecs}s`;
  };

  const formatTime = (timeStr: string | null) => {
    if (!timeStr) return "—";
    try {
      const d = new Date(timeStr);
      return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
    } catch {
      return timeStr;
    }
  };

  const filteredSessions = useMemo(() => {
    return sessions.filter((s) => {
      const match =
        s.username.toLowerCase().includes(search.toLowerCase()) ||
        (s.framedIpAddress && s.framedIpAddress.includes(search)) ||
        (s.callingStationId && s.callingStationId.toLowerCase().includes(search.toLowerCase())) ||
        (s.nasIpAddress && s.nasIpAddress.includes(search));
      return match;
    });
  }, [sessions, search]);

  const totalDownloadBytes = sessions.reduce((acc, s) => acc + (s.acctOutputOctets || 0), 0);
  const totalUploadBytes = sessions.reduce((acc, s) => acc + (s.acctInputOctets || 0), 0);

  return (
    <div className="space-y-8 max-w-7xl mx-auto">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-[var(--fg)] flex items-center gap-2.5">
            <Radio className="w-6 h-6 text-emerald-500 animate-pulse" />
            <span>Live Active Sessions</span>
          </h1>
          <p className="text-xs text-[var(--fg-muted)] mt-1">
            Customers currently connected through your routers, updating in real time.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <span className="text-xs text-[var(--fg-muted)]">Auto-refreshes every 10 seconds</span>
        </div>
      </div>

      {msg && (
        <div
          className={`p-4 rounded-2xl text-xs font-medium border flex items-center justify-between ${
            msg.type === "success"
              ? "bg-emerald-500/10 border-emerald-500/30 text-emerald-500"
              : "bg-red-500/10 border-red-500/30 text-red-500"
          }`}
        >
          <span>{msg.text}</span>
          <button onClick={() => setMsg(null)} className="text-[var(--fg-muted)] hover:text-[var(--fg)]">
            ✕
          </button>
        </div>
      )}

      {/* Real-time Summary Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-5">
        <div className="p-5 rounded-2xl bg-[var(--surface)] border border-[var(--border)] shadow-sm flex items-center justify-between">
          <div className="space-y-1">
            <span className="text-xs font-medium text-[var(--fg-muted)]">Connected Users</span>
            <div className="text-2xl font-bold tabular-nums text-[var(--fg)] flex items-center gap-2">
              <span>{sessions.length}</span>
              <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-emerald-500 bg-emerald-500/10 px-2 py-0.5 rounded-full border border-emerald-500/30">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-ping"></span>
                Online
              </span>
            </div>
          </div>
          <div className="p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/30 text-emerald-500">
            <Users className="w-5 h-5" />
          </div>
        </div>

        <div className="p-5 rounded-2xl bg-[var(--surface)] border border-[var(--border)] shadow-sm flex items-center justify-between">
          <div className="space-y-1">
            <span className="text-xs font-medium text-[var(--fg-muted)]">Total Live Download</span>
            <div className="text-2xl font-bold tabular-nums text-[var(--accent-text)]">
              {formatBytes(totalDownloadBytes)}
            </div>
          </div>
          <div className="p-3 rounded-xl bg-cyan-500/10 border border-cyan-500/20 text-[var(--accent-text)]">
            <ArrowCircleDown className="w-5 h-5" />
          </div>
        </div>

        <div className="p-5 rounded-2xl bg-[var(--surface)] border border-[var(--border)] shadow-sm flex items-center justify-between">
          <div className="space-y-1">
            <span className="text-xs font-medium text-[var(--fg-muted)]">Total Live Upload</span>
            <div className="text-2xl font-bold tabular-nums text-amber-500">
              {formatBytes(totalUploadBytes)}
            </div>
          </div>
          <div className="p-3 rounded-xl bg-amber-500/10 border border-amber-500/20 text-amber-500">
            <ArrowCircleUp className="w-5 h-5" />
          </div>
        </div>
      </div>

      {/* Search Bar */}
      <div className="relative w-full sm:w-80">
        <MagnifyingGlass className="w-4 h-4 absolute left-3.5 top-1/2 -translate-y-1/2 text-[var(--fg-muted)]" />
        <input
          type="text"
          placeholder="Filter by voucher, IP, or MAC..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="w-full pl-9 pr-4 py-2 rounded-xl bg-[var(--canvas)] border border-[var(--border)] text-xs text-[var(--fg)] placeholder-[var(--fg-muted)] focus:outline-none focus:border-indigo-500"
        />
      </div>

      {/* Active Sessions Table */}
      <div className="rounded-2xl border border-[var(--border)] overflow-hidden bg-[var(--surface)]">
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse text-xs">
            <thead>
              <tr className="border-b border-[var(--border)] bg-[var(--subtle)] text-[var(--fg-muted)] font-medium">
                <th className="py-3 px-4">User / Voucher</th>
                <th className="py-3 px-4">Assigned IP</th>
                <th className="py-3 px-4">Device MAC</th>
                <th className="py-3 px-4">Router</th>
                <th className="py-3 px-4">Session Uptime</th>
                <th className="py-3 px-4">Data Usage (Down / Up)</th>
                <th className="py-3 px-4">Started At</th>
                <th className="py-3 px-4 text-right">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[var(--border)]">
              {loading && sessions.length === 0 ? (
                Array.from({ length: 5 }, (_, row) => <tr key={row} aria-hidden="true">{Array.from({ length: 8 }, (_, column) => <td key={column} className="px-4 py-4"><span className="block h-3.5 animate-pulse rounded bg-[var(--fill)] motion-reduce:animate-none" style={{ width: `${55 + ((row + column) % 4) * 10}%` }} /></td>)}</tr>)
              ) : filteredSessions.length > 0 ? (
                filteredSessions.map((session) => (
                  <tr key={session.radAcctId || session.username} className="hover:bg-[var(--subtle)] transition">
                    <td className="py-3 px-4">
                      <div className="flex items-center gap-2">
                        <span className="w-2 h-2 rounded-full bg-emerald-400"></span>
                        <span className="font-mono text-xs font-bold text-[var(--fg)] tracking-wide">
                          {session.username}
                        </span>
                      </div>
                    </td>
                    <td className="py-3 px-4 font-mono text-[var(--accent-text)]">
                      {session.framedIpAddress || <span className="text-[var(--fg-muted)]">Pending</span>}
                    </td>
                    <td className="py-3 px-4 font-mono text-[var(--fg-muted)]">
                      {session.callingStationId || <span className="text-[var(--fg-muted)]">—</span>}
                    </td>
                    <td className="py-3 px-4 font-mono text-[var(--fg-muted)]">
                      {session.nasIpAddress || <span className="text-[var(--fg-muted)]">—</span>}
                    </td>
                    <td className="py-3 px-4">
                      <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md bg-[var(--fill)] text-[var(--fg-body)] font-mono text-[11px]">
                        <Clock className="w-3 h-3 text-[var(--fg-muted)]" />
                        {formatDuration(session.acctSessionTime)}
                      </span>
                    </td>
                    <td className="py-3 px-4 font-mono text-[var(--fg-body)]">
                      <div className="flex items-center gap-2">
                        <span className="text-[var(--accent-text)] flex items-center gap-0.5">
                          ↓ {formatBytes(session.acctOutputOctets)}
                        </span>
                        <span className="text-[var(--fg-muted)]">/</span>
                        <span className="text-amber-500 flex items-center gap-0.5">
                          ↑ {formatBytes(session.acctInputOctets)}
                        </span>
                      </div>
                    </td>
                    <td className="py-3 px-4 text-[var(--fg-muted)]">
                      {formatTime(session.acctStartTime)}
                    </td>
                    <td className="py-3 px-4 text-right">
                      <button
                        onClick={() => setDisconnectTarget(session)}
                        className="px-2.5 py-1 rounded-lg bg-red-500/10 hover:bg-red-500/20 text-red-500 border border-red-500/30 text-[11px] font-semibold transition cursor-pointer flex items-center gap-1.5 ml-auto"
                      >
                        <UserMinus className="w-3 h-3" />
                        <span>Disconnect</span>
                      </button>
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={8} className="py-12 text-center text-[var(--fg-muted)]">
                    No active sessions found. Once clients authenticate on the captive portal, their sessions will appear here in real time.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Disconnect Modal */}
      {disconnectTarget && (
        <Dialog title="Revoke user session" description="Immediately ends the customer's active hotspot session." onClose={() => { if (!disconnecting) setDisconnectTarget(null); }}>
          <div className="space-y-5">
            <div className="flex items-center gap-3 text-red-500">
              <div className="p-3 rounded-2xl bg-red-500/10 border border-red-500/30">
                <ShieldWarning className="w-6 h-6" />
              </div>
              <p className="text-xs font-semibold text-red-600">This action interrupts the customer&apos;s connection.</p>
            </div>

            <p className="text-xs text-[var(--fg-body)] leading-relaxed">
              Are you sure you want to terminate connection for voucher{" "}
              <span className="font-mono font-bold text-[var(--fg)] bg-[var(--fill)] px-1.5 py-0.5 rounded">
                {disconnectTarget.username}
              </span>
              ? A disconnect request will be sent immediately to router{" "}
              <span className="font-mono text-[var(--accent-text)]">{disconnectTarget.nasIpAddress || "10.9.0.x"}</span> to terminate this hotspot session.
            </p>

            <div className="p-3 rounded-xl bg-[var(--canvas)] border border-[var(--border)] space-y-1.5 text-[11px] font-mono">
              <div className="flex justify-between text-[var(--fg-muted)]">
                <span>Client IP:</span>
                <span className="text-[var(--fg)]">{disconnectTarget.framedIpAddress || "N/A"}</span>
              </div>
              <div className="flex justify-between text-[var(--fg-muted)]">
                <span>Client MAC:</span>
                <span className="text-[var(--fg)]">{disconnectTarget.callingStationId || "N/A"}</span>
              </div>
              <div className="flex justify-between text-[var(--fg-muted)]">
                <span>Session Duration:</span>
                <span className="text-[var(--fg)]">{formatDuration(disconnectTarget.acctSessionTime)}</span>
              </div>
            </div>

            <div className="flex flex-col-reverse gap-3 pt-2 sm:flex-row sm:justify-end">
              <button
                type="button"
                onClick={() => setDisconnectTarget(null)}
                className={buttonGhost}
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleDisconnect}
                disabled={disconnecting}
                className="px-5 py-2.5 rounded-xl bg-red-600 hover:bg-red-500 text-white text-xs font-semibold shadow-red-600/25 transition disabled:opacity-50"
              >
                {disconnecting ? "Disconnecting..." : "Terminate Session"}
              </button>
            </div>
          </div>
        </Dialog>
      )}
    </div>
  );
}
