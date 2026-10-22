"use client";

import React, { useEffect, useState, useMemo } from "react";
import api from "@/lib/api";
import {
  FileText,
  RefreshCw,
  Search,
  Download,
  Calendar,
  Clock,
  HardDrive,
  ArrowDownCircle,
  ArrowUpCircle,
  CheckCircle2,
  AlertCircle
} from "lucide-react";

interface SessionHistory {
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

export default function TenantSessionLogsPage() {
  const [logs, setLogs] = useState<SessionHistory[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");
  const [causeFilter, setCauseFilter] = useState("ALL");

  const fetchLogs = async () => {
    try {
      setLoading(true);
      const res = await api.get<SessionHistory[]>("/api/sessions/history");
      setLogs(res.data);
    } catch (err) {
      console.error("Failed to load session logs", err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchLogs();
  }, []);

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

  const formatDateTime = (dtStr: string | null) => {
    if (!dtStr) return "—";
    try {
      const d = new Date(dtStr);
      return d.toLocaleDateString("en-GB", {
        day: "2-digit",
        month: "short",
        hour: "2-digit",
        minute: "2-digit"
      });
    } catch {
      return dtStr;
    }
  };

  const filteredLogs = useMemo(() => {
    return logs.filter((log) => {
      const matchSearch =
        log.username.toLowerCase().includes(search.toLowerCase()) ||
        (log.framedIpAddress && log.framedIpAddress.includes(search)) ||
        (log.callingStationId && log.callingStationId.toLowerCase().includes(search.toLowerCase())) ||
        (log.acctTerminateCause && log.acctTerminateCause.toLowerCase().includes(search.toLowerCase()));
      const matchCause =
        causeFilter === "ALL" ||
        (log.acctTerminateCause && log.acctTerminateCause.toLowerCase() === causeFilter.toLowerCase());
      return matchSearch && matchCause;
    });
  }, [logs, search, causeFilter]);

  const exportCsv = () => {
    const headers = [
      "User / Voucher",
      "Framed IP",
      "Client MAC",
      "Router NAS IP",
      "Start Time",
      "Stop Time",
      "Duration (Sec)",
      "Download (Bytes)",
      "Upload (Bytes)",
      "Terminate Cause"
    ];

    const rows = filteredLogs.map((l) => [
      l.username,
      l.framedIpAddress || "N/A",
      l.callingStationId || "N/A",
      l.nasIpAddress || "N/A",
      l.acctStartTime || "N/A",
      l.acctStopTime || "N/A",
      l.acctSessionTime || 0,
      l.acctOutputOctets || 0,
      l.acctInputOctets || 0,
      l.acctTerminateCause || "N/A"
    ]);

    const csvContent =
      "data:text/csv;charset=utf-8," + [headers.join(","), ...rows.map((e) => e.join(","))].join("\n");
    const encodedUri = encodeURI(csvContent);
    const link = document.createElement("a");
    link.setAttribute("href", encodedUri);
    link.setAttribute("download", `session_accounting_logs_${new Date().toISOString().slice(0, 10)}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  const totalHistoricalBytes = logs.reduce((acc, l) => acc + (l.totalOctets || 0), 0);

  return (
    <div className="space-y-8 max-w-7xl mx-auto">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 tracking-tight flex items-center gap-2.5">
            <FileText className="w-6 h-6 text-blue-700" />
            <span>Accounting Logs & History</span>
          </h1>
          <p className="text-xs text-slate-500 mt-1">
            Historical radius accounting records (radacct) storing session durations, disconnect causes, and byte transfers.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={fetchLogs}
            className="p-2.5 rounded-xl bg-slate-100 hover:bg-slate-200 text-slate-700 hover:text-slate-900 border border-slate-300 transition cursor-pointer"
            title="Refresh Logs"
          >
            <RefreshCw className={`w-4 h-4 ${loading ? "animate-spin text-blue-700" : ""}`} />
          </button>

          <button
            onClick={exportCsv}
            disabled={filteredLogs.length === 0}
            className="px-3.5 py-2.5 rounded-xl bg-slate-100 hover:bg-slate-200 text-slate-700 hover:text-slate-900 border border-slate-300 text-xs font-semibold flex items-center gap-2 transition cursor-pointer disabled:opacity-40"
          >
            <Download className="w-4 h-4" />
            <span>Export CSV</span>
          </button>
        </div>
      </div>

      {/* Summary Stats */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-5">
        <div className="p-5 rounded-2xl bg-white border border-slate-200 shadow-sm">
          <span className="text-xs font-medium text-slate-500">Total Logged Sessions</span>
          <div className="text-2xl font-extrabold text-slate-900 mt-1">{logs.length} Records</div>
        </div>

        <div className="p-5 rounded-2xl bg-white border border-slate-200 shadow-sm">
          <span className="text-xs font-medium text-slate-500">Historical Data Consumed</span>
          <div className="text-2xl font-extrabold text-blue-700 mt-1">
            {formatBytes(totalHistoricalBytes)}
          </div>
        </div>

        <div className="p-5 rounded-2xl bg-white border border-slate-200 shadow-sm">
          <span className="text-xs font-medium text-slate-500">Compliance & Storage</span>
          <div className="text-2xl font-extrabold text-emerald-700 mt-1">FreeRADIUS SQL</div>
        </div>
      </div>

      {/* Filters Bar */}
      <div className="flex flex-col sm:flex-row items-center justify-between gap-4 p-4 rounded-2xl bg-slate-50 border border-slate-200">
        <div className="relative w-full sm:w-80">
          <Search className="w-4 h-4 absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-500" />
          <input
            type="text"
            placeholder="Search code, IP, MAC, cause..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full pl-9 pr-4 py-2 rounded-xl bg-[#F5F7FA] border border-slate-200 text-xs text-slate-900 placeholder-slate-500 focus:outline-none focus:border-indigo-500"
          />
        </div>

        <div className="flex items-center gap-1.5 p-1 rounded-xl bg-[#F5F7FA]/80 border border-slate-200 w-full sm:w-auto overflow-x-auto">
          {["ALL", "User-Request", "Session-Timeout", "Admin-Reset", "Lost-Carrier"].map((cause) => (
            <button
              key={cause}
              onClick={() => setCauseFilter(cause)}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition whitespace-nowrap cursor-pointer ${
                causeFilter === cause
                  ? "bg-blue-700 text-white shadow-sm"
                  : "text-slate-500 hover:text-slate-700"
              }`}
            >
              {cause === "ALL" ? "All Causes" : cause}
            </button>
          ))}
        </div>
      </div>

      {/* Logs Table */}
      <div className="rounded-2xl border border-slate-200 overflow-hidden bg-white/30">
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse text-xs">
            <thead>
              <tr className="border-b border-slate-200 bg-white/80 text-slate-500 font-medium">
                <th className="py-3 px-4">User / Code</th>
                <th className="py-3 px-4">IP Address</th>
                <th className="py-3 px-4">Device MAC</th>
                <th className="py-3 px-4">Session Duration</th>
                <th className="py-3 px-4">Data Transferred (Down / Up)</th>
                <th className="py-3 px-4">Connected At</th>
                <th className="py-3 px-4">Terminated At</th>
                <th className="py-3 px-4">Terminate Reason</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100/60">
              {loading ? (
                <tr>
                  <td colSpan={8} className="py-12 text-center text-slate-500">
                    <RefreshCw className="w-5 h-5 mx-auto animate-spin text-blue-700 mb-2" />
                    <span>Loading accounting logs...</span>
                  </td>
                </tr>
              ) : filteredLogs.length > 0 ? (
                filteredLogs.map((log) => (
                  <tr key={log.radAcctId} className="hover:bg-slate-50 transition">
                    <td className="py-3 px-4">
                      <span className="font-mono text-xs font-bold text-slate-700">
                        {log.username}
                      </span>
                    </td>
                    <td className="py-3 px-4 font-mono text-cyan-300">
                      {log.framedIpAddress || <span className="text-slate-600">—</span>}
                    </td>
                    <td className="py-3 px-4 font-mono text-slate-500">
                      {log.callingStationId || <span className="text-slate-600">—</span>}
                    </td>
                    <td className="py-3 px-4">
                      <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md bg-slate-100 text-slate-700 font-mono text-[11px]">
                        <Clock className="w-3 h-3 text-slate-500" />
                        {formatDuration(log.acctSessionTime)}
                      </span>
                    </td>
                    <td className="py-3 px-4 font-mono text-slate-700">
                      <div className="flex items-center gap-2">
                        <span className="text-cyan-400 flex items-center gap-0.5">
                          ↓ {formatBytes(log.acctOutputOctets)}
                        </span>
                        <span className="text-slate-600">/</span>
                        <span className="text-purple-400 flex items-center gap-0.5">
                          ↑ {formatBytes(log.acctInputOctets)}
                        </span>
                      </div>
                    </td>
                    <td className="py-3 px-4 text-slate-500">{formatDateTime(log.acctStartTime)}</td>
                    <td className="py-3 px-4 text-slate-500">{formatDateTime(log.acctStopTime)}</td>
                    <td className="py-3 px-4">
                      <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold bg-slate-100 border border-slate-300 text-slate-700">
                        {log.acctTerminateCause || "Normal-Logout"}
                      </span>
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={8} className="py-12 text-center text-slate-500">
                    No historical session records found matching filter.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
