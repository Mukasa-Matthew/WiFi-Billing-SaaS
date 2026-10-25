"use client";

import Link from "next/link";
import { ChartLine, Database, HardDrives, Radio } from "@/components/icons";
import { Badge, Card, PageHeader } from "@/components/BillingUI";

// Configured platform services. No live probes are wired to this page, so it
// states that plainly rather than implying a health result.
const components = [
  { name: "WireGuard hub (wg0)", description: "Virtual network hub on 10.9.0.1/16 that every tenant router tunnels into.", port: "UDP 443", icon: Radio },
  { name: "FreeRADIUS", description: "SQL-backed authorization via radcheck/radreply, plus RFC 5176 CoA/PoD disconnects.", port: "1812 · 3799", icon: ChartLine },
  { name: "PostgreSQL", description: "Shared radius_db used by both the Spring Boot backend and FreeRADIUS.", port: "5432", icon: Database },
  { name: "Voucher expiry worker", description: "30-second background task that activates first logins and disconnects expired sessions.", port: "Scheduled", icon: HardDrives },
];

export default function AdminHealthPage() {
  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <PageHeader title="System health" description="Core services the platform depends on." />
      <div role="status" className="rounded-xl border border-[var(--border)] bg-[var(--subtle)] px-4 py-3.5 text-[13px] leading-relaxed text-[var(--fg-body)]">
        <b className="font-semibold">Live service probes aren&apos;t connected yet.</b> This page lists what runs, not whether it&apos;s healthy right now. For router-level health, use <Link href="/admin/network" className="text-[var(--accent-text)] hover:underline">Network operations</Link>, where diagnostics come from real telemetry and RADIUS data.
      </div>
      <Card bodyClassName="">
        <ul className="divide-y divide-[var(--border-soft)]">
          {components.map(({ name, description, port, icon: Icon }) => (
            <li key={name} className="flex items-start gap-4 px-5 py-4">
              <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-[var(--fill)] text-[var(--fg-muted)]"><Icon className="h-[18px] w-[18px]" /></span>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="text-[14px] font-medium text-[var(--fg)]">{name}</h2>
                  <span className="font-mono text-[11px] text-[var(--fg-faint)]">{port}</span>
                </div>
                <p className="mt-0.5 text-[13px] text-[var(--fg-muted)]">{description}</p>
              </div>
              <Badge tone="slate" dot>Not monitored</Badge>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
