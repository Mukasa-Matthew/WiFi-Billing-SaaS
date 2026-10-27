/**
 * One reading of router health for every platform screen.
 *
 * Routers push telemetry every 2 minutes (RouterScriptGenerator, wb-telemetry)
 * and the backend flips them to "offline" after 6 minutes of silence
 * (telemetry.offline-after-minutes, checked every minute). So a router still
 * stored as online/degraded with a report older than 8 minutes means the
 * stored status itself is stale — the same cut-off the backend's own
 * diagnostics use for "Router telemetry". We call that out instead of
 * trusting the stored label.
 */

export const TELEMETRY_STALE_MS = 8 * 60 * 1000;

export type RouterState = "online" | "degraded" | "offline" | "stale" | "provisioning" | "unknown";
export type StateTone = "green" | "amber" | "red" | "slate";

export interface RouterStateInfo { state: RouterState; label: string; tone: StateTone; detail: string }

const PROVISIONING = new Set(["pending", "provisioning", "registered", "provisioned"]);

export function routerState(r: { status: string | null; lastSeen: string | null }, now: number): RouterStateInfo {
  const status = (r.status || "").toLowerCase();
  const seen = r.lastSeen ? new Date(r.lastSeen).getTime() : null;
  if (status === "online" || status === "degraded") {
    if (seen == null || (now > 0 && now - seen > TELEMETRY_STALE_MS)) {
      return { state: "stale", label: "Stale", tone: "amber", detail: `Stored as ${status}, but no report for over 8 minutes` };
    }
    return status === "online"
      ? { state: "online", label: "Online", tone: "green", detail: "Reporting normally" }
      : { state: "degraded", label: "Degraded", tone: "amber", detail: "Reporting, but a service is not ready" };
  }
  if (status === "offline") return { state: "offline", label: "Offline", tone: "red", detail: "No telemetry for over 6 minutes" };
  if (PROVISIONING.has(status)) {
    const label = status === "pending" ? "Not set up" : status === "provisioning" ? "Provisioning" : status === "registered" ? "Awaiting import" : "Provisioned";
    return { state: "provisioning", label, tone: "slate", detail: "Has not reported telemetry yet" };
  }
  return { state: "unknown", label: status ? status : "Unknown", tone: "slate", detail: "Status not recognised" };
}

/** Hotspot / RADIUS / portal readiness flags as one verdict. */
export function servicesState(flags: (boolean | null | undefined)[]): { label: string; tone: StateTone } {
  if (flags.every((v) => v === true)) return { label: "All ready", tone: "green" };
  if (flags.some((v) => v === false)) return { label: "Not ready", tone: "amber" };
  return { label: "Not reported", tone: "slate" };
}

/** RADIUS session states from NocService: LIVE (interim update in the last 15 min), OPEN_STALE, CLOSED. */
export function sessionState(state: string): { label: string; tone: StateTone } {
  if (state === "LIVE") return { label: "Live", tone: "green" };
  if (state === "OPEN_STALE") return { label: "Open, no updates", tone: "amber" };
  if (state === "CLOSED") return { label: "Ended", tone: "slate" };
  return { label: state || "Unknown", tone: "slate" };
}

export function duration(seconds: number | null | undefined) {
  if (seconds == null) return "—";
  const d = Math.floor(seconds / 86400), h = Math.floor((seconds % 86400) / 3600), m = Math.floor((seconds % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m`;
  return `${seconds}s`;
}

export const severityTone = (s: string): StateTone => (s === "CRITICAL" ? "red" : s === "WARNING" ? "amber" : "slate");

export interface FleetSummary { total: number; online: number; degraded: number; down: number; provisioning: number; tone: StateTone; label: string; detail: string }

/** Roll a business's routers up into one honest line: "down" (offline or stale)
 *  outranks degraded, and routers still being set up never count as failures. */
export function summarizeRouters(routers: { status: string | null; lastSeen: string | null }[], now: number): FleetSummary {
  const states = routers.map((r) => routerState(r, now).state);
  const online = states.filter((s) => s === "online").length;
  const degraded = states.filter((s) => s === "degraded").length;
  const down = states.filter((s) => s === "offline" || s === "stale").length;
  const provisioning = states.length - online - degraded - down;
  const total = states.length;
  const label = `${online}/${total} online`;
  if (!total) return { total, online, degraded, down, provisioning, tone: "slate", label: "None linked", detail: "" };
  if (down) return { total, online, degraded, down, provisioning, tone: down === total ? "red" : "amber", label, detail: `${down} not reporting` };
  if (degraded) return { total, online, degraded, down, provisioning, tone: "amber", label, detail: `${degraded} degraded` };
  if (provisioning === total) return { total, online, degraded, down, provisioning, tone: "slate", label: "Setting up", detail: `${total} not yet online` };
  return { total, online, degraded, down, provisioning, tone: "green", label, detail: provisioning ? `${provisioning} still setting up` : "All online" };
}
