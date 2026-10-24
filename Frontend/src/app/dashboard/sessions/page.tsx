"use client";

import React, { useEffect, useState, useMemo } from "react";
import api from "@/lib/api";
import {
  Users,
  ArrowClockwise,
  MagnifyingGlass,
  WifiHigh,
  Radio,
  ArrowCircleDown,
  ArrowCircleUp,
  Clock,
  UserMinus,
  Warning,
  CheckCircle,
  ShieldWarning,
  HardDrive
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

  const fetchActiveSessions = async () => {
    try {
      const res = await api.get<ActiveSession[]>("/api/sessions/active");
      setSessions(res.data);
    } catch (err) {
      console.error("Failed to load active sessions", err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchActiveSessions();
  }, []);

  useEffect(() => {
    const interval = setInterval(() => {
      fetchActiveSessions();
    }, 10000);
    return () => clearInterval(interval);
  }, []);

  const handleDisconnect = async () => {
    if (!disconnectTarget) return;
    try {
      setDisconnecting(true);
      const res = await api.post("/api/sessions/disconnect", {
        username: disconnectTarget.username
      });
      setMsg({
        text: `Disconnect CoA packet transmitted for user ${disconnectTarget.username}`,
        type: "success"
      });
      setDisconnectTarget(null);
      fetchActiveSessions();
    } catch (err: any) {
      setMsg({
        text: err.response?.data?.message || "Failed to disconnect session",
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
          <h1 className="text-xl font-bold tracking-tight text-slate-900 flex items-center gap-2.5">
            <Radio className="w-6 h-6 text-emerald-700 animate-pulse" />
            <span>Live Active Sessions</span>
          </h1>
          <p className="text-xs text-slate-500 mt-1">
            Real-time accounting telemetry streamed from MikroTik NAS devices via FreeRADIUS radacct.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <span className="text-xs text-slate-500">Auto-refreshes every 10 seconds</span>
        </div>
      </div>

      {msg && (
        <div
          className={`p-4 rounded-2xl text-xs font-medium border flex items-center justify-between ${
            msg.type === "success"
              ? "bg-emerald-50 border-emerald-200 text-emerald-700"
              : "bg-red-50 border-red-200 text-red-600"
          }`}
        >
          <span>{msg.text}</span>
          <button onClick={() => setMsg(null)} className="text-slate-500 hover:text-slate-900">
            ✕
          </button>
        </div>
      )}

      {/* Real-time Summary Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-5">
        <div className="p-5 rounded-2xl bg-white border border-slate-200 shadow-sm flex items-center justify-between">
          <div className="space-y-1">
            <span className="text-xs font-medium text-slate-500">Connected Users</span>
            <div className="text-2xl font-bold tabular-nums text-slate-900 flex items-center gap-2">
              <span>{sessions.length}</span>
              <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-emerald-700 bg-emerald-50 px-2 py-0.5 rounded-full border border-emerald-200">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-ping"></span>
                Online
              </span>
            </div>
          </div>
          <div className="p-3 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-700">
            <Users className="w-5 h-5" />
          </div>
        </div>

        <div className="p-5 rounded-2xl bg-white border border-slate-200 shadow-sm flex items-center justify-between">
          <div className="space-y-1">
            <span className="text-xs font-medium text-slate-500">Total Live Download</span>
            <div className="text-2xl font-bold tabular-nums text-blue-700">
              {formatBytes(totalDownloadBytes)}
            </div>
          </div>
          <div className="p-3 rounded-xl bg-cyan-500/10 border border-cyan-500/20 text-blue-700">
            <ArrowCircleDown className="w-5 h-5" />
          </div>
        </div>

        <div className="p-5 rounded-2xl bg-white border border-slate-200 shadow-sm flex items-center justify-between">
          <div className="space-y-1">
            <span className="text-xs font-medium text-slate-500">Total Live Upload</span>
            <div className="text-2xl font-bold tabular-nums text-amber-700">
              {formatBytes(totalUploadBytes)}
            </div>
          </div>
          <div className="p-3 rounded-xl bg-purple-500/10 border border-purple-500/20 text-amber-700">
            <ArrowCircleUp className="w-5 h-5" />
          </div>
        </div>
      </div>

      {/* Search Bar */}
      <div className="relative w-full sm:w-80">
        <MagnifyingGlass className="w-4 h-4 absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-500" />
        <input
          type="text"
          placeholder="Filter by voucher, IP, or MAC..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="w-full pl-9 pr-4 py-2 rounded-xl bg-[#F5F7FA] border border-slate-200 text-xs text-slate-900 placeholder-slate-500 focus:outline-none focus:border-indigo-500"
        />
      </div>

      {/* Active Sessions Table */}
      <div className="rounded-2xl border border-slate-200 overflow-hidden bg-white/30">
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse text-xs">
            <thead>
              <tr className="border-b border-slate-200 bg-white/80 text-slate-500 font-medium">
                <th className="py-3 px-4">User / Voucher</th>
                <th className="py-3 px-4">Assigned IP</th>
                <th className="py-3 px-4">Device MAC</th>
                <th className="py-3 px-4">NAS CellTower</th>
                <th className="py-3 px-4">Session Uptime</th>
                <th className="py-3 px-4">Data Usage (Down / Up)</th>
                <th className="py-3 px-4">Started At</th>
                <th className="py-3 px-4 text-right">RFC 5176 Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100/60">
              {loading && sessions.length === 0 ? (
                <tr>
                  <td colSpan={8} className="py-12 text-center text-slate-500">
                    <ArrowClockwise className="w-5 h-5 mx-auto animate-spin text-emerald-700 mb-2" />
                    <span>Streaming active sessions from FreeRADIUS...</span>
                  </td>
                </tr>
              ) : filteredSessions.length > 0 ? (
                filteredSessions.map((session) => (
                  <tr key={session.radAcctId || session.username} className="hover:bg-slate-50 transition">
                    <td className="py-3 px-4">
                      <div className="flex items-center gap-2">
                        <span className="w-2 h-2 rounded-full bg-emerald-400"></span>
                        <span className="font-mono text-xs font-bold text-slate-900 tracking-wide">
                          {session.username}
                        </span>
                      </div>
                    </td>
                    <td className="py-3 px-4 font-mono text-cyan-300">
                      {session.framedIpAddress || <span className="text-slate-600">Pending</span>}
                    </td>
                    <td className="py-3 px-4 font-mono text-slate-500">
                      {session.callingStationId || <span className="text-slate-600">—</span>}
                    </td>
                    <td className="py-3 px-4 font-mono text-slate-500">
                      {session.nasIpAddress || <span className="text-slate-600">—</span>}
                    </td>
                    <td className="py-3 px-4">
                      <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md bg-slate-100 text-slate-700 font-mono text-[11px]">
                        <Clock className="w-3 h-3 text-slate-500" />
                        {formatDuration(session.acctSessionTime)}
                      </span>
                    </td>
                    <td className="py-3 px-4 font-mono text-slate-700">
                      <div className="flex items-center gap-2">
                        <span className="text-blue-700 flex items-center gap-0.5">
                          ↓ {formatBytes(session.acctOutputOctets)}
                        </span>
                        <span className="text-slate-600">/</span>
                        <span className="text-amber-700 flex items-center gap-0.5">
                          ↑ {formatBytes(session.acctInputOctets)}
                        </span>
                      </div>
                    </td>
                    <td className="py-3 px-4 text-slate-500">
                      {formatTime(session.acctStartTime)}
                    </td>
                    <td className="py-3 px-4 text-right">
                      <button
                        onClick={() => setDisconnectTarget(session)}
                        className="px-2.5 py-1 rounded-lg bg-red-50 hover:bg-red-500/20 text-red-600 border border-red-200 text-[11px] font-semibold transition cursor-pointer flex items-center gap-1.5 ml-auto"
                      >
                        <UserMinus className="w-3 h-3" />
                        <span>Disconnect</span>
                      </button>
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={8} className="py-12 text-center text-slate-500">
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
        <div className="fixed inset-0 bg-[#F5F7FA]/80 backdrop-blur-md z-50 flex items-center justify-center p-4">
          <div className="w-full max-w-md bg-white border border-slate-200 rounded-xl p-6 space-y-6 shadow-sm">
            <div className="flex items-center gap-3 text-red-600">
              <div className="p-3 rounded-2xl bg-red-50 border border-red-200">
                <ShieldWarning className="w-6 h-6" />
              </div>
              <div>
                <h3 className="text-base font-bold text-slate-900">Revoke User Session</h3>
                <p className="text-xs text-slate-500">RFC 5176 Disconnect-Request (PoD)</p>
              </div>
            </div>

            <p className="text-xs text-slate-700 leading-relaxed">
              Are you sure you want to terminate connection for voucher{" "}
              <span className="font-mono font-bold text-slate-900 bg-slate-100 px-1.5 py-0.5 rounded">
                {disconnectTarget.username}
              </span>
              ? A UDP 3799 CoA packet will be immediately transmitted to MikroTik router{" "}
              <span className="font-mono text-cyan-300">{disconnectTarget.nasIpAddress || "10.9.0.x"}</span> to terminate this hotspot session.
            </p>

            <div className="p-3 rounded-xl bg-[#F5F7FA] border border-slate-200 space-y-1.5 text-[11px] font-mono">
              <div className="flex justify-between text-slate-500">
                <span>Client IP:</span>
                <span className="text-slate-900">{disconnectTarget.framedIpAddress || "N/A"}</span>
              </div>
              <div className="flex justify-between text-slate-500">
                <span>Client MAC:</span>
                <span className="text-slate-900">{disconnectTarget.callingStationId || "N/A"}</span>
              </div>
              <div className="flex justify-between text-slate-500">
                <span>Session Duration:</span>
                <span className="text-slate-900">{formatDuration(disconnectTarget.acctSessionTime)}</span>
              </div>
            </div>

            <div className="flex items-center justify-end gap-3 pt-2">
              <button
                type="button"
                onClick={() => setDisconnectTarget(null)}
                className="px-4 py-2.5 rounded-xl bg-slate-100 text-xs font-medium text-slate-700 hover:text-slate-900 transition"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleDisconnect}
                disabled={disconnecting}
                className="px-5 py-2.5 rounded-xl bg-red-600 hover:bg-red-500 text-white text-xs font-semibold shadow-red-600/25 transition disabled:opacity-50"
              >
                {disconnecting ? "Transmitting CoA..." : "Terminate Session"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
