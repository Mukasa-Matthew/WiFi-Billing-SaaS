"use client";

import Link from "next/link";
import { ChartLine, Database, HardDrives, Radio, Warning } from "@/components/icons";
import { Badge, Card, ErrorNotice, PageContainer, PageHeader, useApi, relativeTime, useNow } from "@/components/BillingUI";

// Static descriptions; statuses come from the live probe endpoint.
const components = [
  { key: "wireguard", name: "WireGuard hub (wg0)", description: "Virtual network hub on 10.9.0.1/16 that every tenant router tunnels into.", port: "UDP 443", icon: Radio },
  { key: "freeradius", name: "FreeRADIUS", description: "SQL-backed authorization via radcheck/radreply, plus RFC 5176 CoA/PoD disconnects.", port: "1812 · 3799", icon: ChartLine },
  { key: "postgres", name: "PostgreSQL", description: "Shared radius_db used by both the Spring Boot backend and FreeRADIUS.", port: "5432", icon: Database },
  { key: "worker", name: "Voucher expiry worker", description: "30-second background task that activates first logins and disconnects expired sessions.", port: "Scheduled", icon: HardDrives },
];

interface HealthComponent { key: string; status: "UP" | "DEGRADED" | "DOWN" | "UNKNOWN"; detail: string }
interface HealthReport { checkedAt: string; components: HealthComponent[] }

const badgeFor = (status: string | undefined) =>
  status === "UP" ? { tone: "green", label: "Healthy" }
    : status === "DEGRADED" ? { tone: "amber", label: "Degraded" }
    : status === "DOWN" ? { tone: "red", label: "Down" }
    : { tone: "slate", label: "Unknown" };

export default function AdminHealthPage() {
  const report = useApi<HealthReport>("/api/admin/system-health");
  const now = useNow();
  const byKey = new Map((report.data?.components ?? []).map((c) => [c.key, c]));
  const down = (report.data?.components ?? []).filter((c) => c.status === "DOWN" || c.status === "DEGRADED");

  return (
    <PageContainer>
      <PageHeader
        title="System health"
        description="Live probes of the services the platform depends on."
        action={report.data && (
          <button type="button" onClick={() => report.load()} className="text-xs text-[var(--fg-muted)] hover:text-[var(--fg)]">
            Checked {relativeTime(report.data.checkedAt, now)} · refresh
          </button>
        )}
      />
      {report.error && <ErrorNotice text={`Couldn't run the health probes: ${report.error}`} />}
      {down.length > 0 && (
        <div role="status" className="flex items-start gap-2.5 rounded-xl border border-red-200 bg-red-50 px-4 py-3.5 text-[13px] leading-relaxed text-red-800">
          <Warning className="mt-0.5 h-4 w-4 shrink-0" />
          <span><b className="font-semibold">{down.length} {down.length === 1 ? "service needs" : "services need"} attention.</b> Details are on each card below. For router-level health, use <Link href="/admin/network" className="font-medium underline">Network operations</Link>.</span>
        </div>
      )}
      <Card bodyClassName="">
        <ul className="divide-y divide-[var(--border-soft)]">
          {components.map(({ key, name, description, port, icon: Icon }) => {
            const probe = byKey.get(key);
            const badge = badgeFor(probe?.status);
            return (
              <li key={key} className="flex items-start gap-4 px-5 py-4">
                <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-[var(--fill)] text-[var(--fg-muted)]"><Icon className="h-[18px] w-[18px]" /></span>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <h2 className="text-[14px] font-medium text-[var(--fg)]">{name}</h2>
                    <span className="font-mono text-[11px] text-[var(--fg-faint)]">{port}</span>
                  </div>
                  <p className="mt-0.5 text-[13px] text-[var(--fg-muted)]">{description}</p>
                  {probe && <p className="mt-1 text-xs text-[var(--fg-faint)]">{probe.detail}</p>}
                </div>
                {report.loading && !report.data
                  ? <span className="mt-1 text-xs text-[var(--fg-faint)]">Probing…</span>
                  : <Badge tone={badge.tone} dot>{badge.label}</Badge>}
              </li>
            );
          })}
        </ul>
      </Card>
    </PageContainer>
  );
}
