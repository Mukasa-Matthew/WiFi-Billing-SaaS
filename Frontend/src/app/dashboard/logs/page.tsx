"use client";

import React, { useCallback, useEffect, useState, useMemo } from "react";
import api from "@/lib/api";
import { date } from "@/components/BillingUI";
import {
  FileText,
  MagnifyingGlass,
  Download,
  Clock,
} from "@/components/icons";

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

  const fetchLogs = useCallback(async () => {
    try {
      setLoading(true);
      const res = await api.get<SessionHistory[]>("/api/sessions/history");
      setLogs(res.data);
    } catch (err) {
      console.error("Failed to load session logs", err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const initial = window.setTimeout(fetchLogs, 0);
    const interval = window.setInterval(fetchLogs, 30000);
    return () => { window.clearTimeout(initial); window.clearInterval(interval); };
  }, [fetchLogs]);

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

  const formatDateTime = date;

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
          <h1 className="text-[1.65rem] font-bold leading-tight tracking-[-0.02em] text-[var(--fg)] flex items-center gap-2.5">
            <FileText className="w-6 h-6 text-[var(--accent-text)]" />
            <span>Accounting Logs</span>
          </h1>
          <p className="text-xs text-[var(--fg-muted)] mt-1">
            Historical session records — durations, disconnect causes, and data transferred.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={exportCsv}
            disabled={filteredLogs.length === 0}
            className="px-3.5 py-2.5 rounded-xl bg-[var(--fill)] hover:bg-[var(--border)] text-[var(--fg-body)] hover:text-[var(--fg)] border border-[var(--border-strong)] text-xs font-semibold flex items-center gap-2 transition cursor-pointer disabled:opacity-40"
          >
            <Download className="w-4 h-4" />
            <span>Export CSV</span>
          </button>
        </div>
      </div>

      {/* Summary Stats */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-5">
        <div className="p-5 rounded-2xl bg-[var(--surface)] border border-[var(--border)] shadow-sm">
          <span className="text-xs font-medium text-[var(--fg-muted)]">Total Logged Sessions</span>
          <div className="text-2xl font-bold tabular-nums text-[var(--fg)] mt-1">{logs.length} Records</div>
        </div>

        <div className="p-5 rounded-2xl bg-[var(--surface)] border border-[var(--border)] shadow-sm">
          <span className="text-xs font-medium text-[var(--fg-muted)]">Historical Data Consumed</span>
          <div className="text-2xl font-bold tabular-nums text-[var(--accent-text)] mt-1">
            {formatBytes(totalHistoricalBytes)}
          </div>
        </div>
      </div>

      {/* Filters Bar */}
      <div className="flex flex-col sm:flex-row items-center justify-between gap-4 p-4 rounded-2xl bg-[var(--subtle)] border border-[var(--border)]">
        <div className="relative w-full sm:w-80">
          <MagnifyingGlass className="w-4 h-4 absolute left-3.5 top-1/2 -translate-y-1/2 text-[var(--fg-muted)]" />
          <input
            type="text"
            placeholder="Search code, IP, MAC, cause..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full pl-9 pr-4 py-2 max-sm:min-h-11 rounded-xl bg-[var(--canvas)] border border-[var(--border)] text-xs text-[var(--fg)] placeholder-[var(--fg-muted)] focus:outline-none focus:border-indigo-500"
          />
        </div>

        <div className="flex items-center gap-1.5 p-1 rounded-xl bg-[var(--canvas)]/80 border border-[var(--border)] w-full sm:w-auto overflow-x-auto">
          {["ALL", "User-Request", "Session-Timeout", "Admin-Reset", "Lost-Carrier"].map((cause) => (
            <button
              key={cause}
              onClick={() => setCauseFilter(cause)}
              className={`px-3 py-1.5 max-sm:min-h-11 rounded-lg text-xs font-semibold transition whitespace-nowrap cursor-pointer ${
                causeFilter === cause
                  ? "bg-blue-700 text-white shadow-sm"
                  : "text-[var(--fg-muted)] hover:text-[var(--fg-body)]"
              }`}
            >
              {cause === "ALL" ? "All Causes" : cause}
            </button>
          ))}
        </div>
      </div>

      {/* Logs Table */}
      <div className="rounded-2xl border border-[var(--border)] overflow-hidden bg-[var(--surface)]">
        <div className="overflow-x-auto">
          <table className="stack-table w-full text-left border-collapse text-xs">
            <thead>
              <tr className="border-b border-[var(--border)] bg-[var(--subtle)] text-[var(--fg-muted)] font-medium">
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
            <tbody className="divide-y divide-[var(--border)]">
              {loading ? (
                Array.from({ length: 6 }, (_, row) => <tr key={row} aria-hidden="true">{Array.from({ length: 8 }, (_, column) => <td key={column} className="px-4 py-4"><span className="block h-3.5 animate-pulse rounded bg-[var(--fill)] motion-reduce:animate-none" style={{ width: `${55 + ((row + column) % 4) * 10}%` }} /></td>)}</tr>)
              ) : filteredLogs.length > 0 ? (
                filteredLogs.map((log) => (
                  <tr key={log.radAcctId} className="hover:bg-[var(--subtle)] transition">
                    <td className="py-3 px-4">
                      <span className="font-mono text-xs font-bold text-[var(--fg-body)]">
                        {log.username}
                      </span>
                    </td>
                    <td data-label="IP address" className="py-3 px-4 font-mono text-[var(--accent-text)]">
                      {log.framedIpAddress || <span className="text-[var(--fg-muted)]">—</span>}
                    </td>
                    <td data-label="Device MAC" className="py-3 px-4 font-mono text-[var(--fg-muted)]">
                      {log.callingStationId || <span className="text-[var(--fg-muted)]">—</span>}
                    </td>
                    <td data-label="Duration" className="py-3 px-4">
                      <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md bg-[var(--fill)] text-[var(--fg-body)] font-mono text-[11px]">
                        <Clock className="w-3 h-3 text-[var(--fg-muted)]" />
                        {formatDuration(log.acctSessionTime)}
                      </span>
                    </td>
                    <td data-label="Data" className="py-3 px-4 font-mono text-[var(--fg-body)]">
                      <div className="flex items-center gap-2">
                        <span className="text-[var(--accent-text)] flex items-center gap-0.5">
                          ↓ {formatBytes(log.acctOutputOctets)}
                        </span>
                        <span className="text-[var(--fg-muted)]">/</span>
                        <span className="text-amber-700 flex items-center gap-0.5">
                          ↑ {formatBytes(log.acctInputOctets)}
                        </span>
                      </div>
                    </td>
                    <td data-label="Connected" className="py-3 px-4 text-[var(--fg-muted)]">{formatDateTime(log.acctStartTime)}</td>
                    <td data-label="Terminated" className="py-3 px-4 text-[var(--fg-muted)]">{formatDateTime(log.acctStopTime)}</td>
                    <td data-label="Reason" className="py-3 px-4">
                      <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold bg-[var(--fill)] border border-[var(--border-strong)] text-[var(--fg-body)]">
                        {log.acctTerminateCause || "Normal-Logout"}
                      </span>
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={8} className="py-12 text-center text-[var(--fg-muted)]">
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
