"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import api, { API_BASE_URL } from "@/lib/api";
import { Dialog } from "@/components/ui/Dialog";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import { notify } from "@/components/ui/Toast";
import { BusyLabel, ListSkeleton } from "@/components/LoadingUI";
import { Badge, ErrorNotice, buttonGhost, date, humanize, message } from "@/components/BillingUI";

type Status = {
  enabled: boolean; state: string; message: string; address: string | null; username: string | null;
  tunnelIp: string | null; lastSuccessfulCheck: string | null; installed: boolean; available: boolean;
};

export default function WinboxAccessDialog({ base, onClose }: { base: string; onClose: () => void }) {
  const [status, setStatus] = useState<Status | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [password, setPassword] = useState("");
  const [installation, setInstallation] = useState<{ command: string; expiresAt: string } | null>(null);
  const { confirm, dialog } = useConfirmDialog();
  const alive = useRef(false);
  const epoch = useRef(0);
  const endpoint = `${base}/winbox`;

  const load = useCallback(async () => {
    const current = epoch.current;
    try {
      const { data } = await api.get<Status>(endpoint);
      if (!alive.current || current !== epoch.current) return;
      setStatus(data); setError("");
      if (data.state !== "READY") { epoch.current++; setPassword(""); }
    } catch (e) { if (alive.current) { setError(message(e)); setPassword(""); } }
  }, [endpoint]);

  useEffect(() => {
    alive.current = true;
    const first = window.setTimeout(() => { void load(); }, 0);
    const timer = window.setInterval(() => { if (document.visibilityState === "visible") void load(); }, 5000);
    const hide = () => { if (document.visibilityState !== "visible") setPassword(""); };
    document.addEventListener("visibilitychange", hide);
    return () => { alive.current = false; window.clearTimeout(first); window.clearInterval(timer); document.removeEventListener("visibilitychange", hide); };
  }, [load]);

  const copy = async (value: string) => {
    try { await navigator.clipboard.writeText(value); notify.success("Copied. Keep connection details private."); }
    catch { notify.error("Clipboard access was denied. Select and copy the value manually."); }
  };

  const change = async (operation: "enable" | "disable" | "rotate") => {
    epoch.current++; setBusy(true); setPassword(""); setInstallation(null);
    try {
      const { data } = await api.post<Status>(`${endpoint}/${operation}`);
      if (alive.current) { epoch.current++; setStatus(data); setError(""); notify.info(data.message); }
    } catch (e) { if (alive.current) setError(message(e)); }
    finally { if (alive.current) setBusy(false); }
  };

  const sensitivePassword = async (copyOnly = false) => {
    if (password && !copyOnly) { setPassword(""); return; }
    setBusy(true);
    const current = epoch.current;
    try {
      const { data } = await api.post<{ password: string }>(`${endpoint}/credentials`);
      if (!alive.current || current !== epoch.current) return;
      if (copyOnly) await copy(data.password); else setPassword(data.password);
    } catch (e) { if (alive.current) setError(message(e)); }
    finally { if (alive.current) setBusy(false); }
  };

  const install = async () => {
    setBusy(true); setPassword("");
    try {
      const { data } = await api.post<{ path: string; expiresAt: string }>(`${endpoint}/installation`);
      const url = new URL(data.path, API_BASE_URL || window.location.origin).toString();
      const command = `/tool/fetch url="${url}" check-certificate=yes dst-path=ap-winbox-install.rsc; :do { /import ap-winbox-install.rsc } on-error={ :put "WinBox agent installation failed" }; /file/remove [find name="ap-winbox-install.rsc"]`;
      if (alive.current) setInstallation({ command, expiresAt: data.expiresAt });
    } catch (e) { if (alive.current) setError(message(e)); }
    finally { if (alive.current) setBusy(false); }
  };

  const confirmChange = (operation: "disable" | "rotate") => confirm({
    title: operation === "disable" ? "Disable remote WinBox?" : "Rotate WinBox password?",
    body: operation === "disable" ? "The public endpoint and existing relay sessions will close within 15 seconds. The dedicated router account is disabled at its next agent check-in. Billing is unchanged."
      : "The current endpoint is withdrawn until the router applies the new password. Existing relay sessions will close. Billing, RADIUS and WireGuard credentials are unchanged.",
    confirmLabel: operation === "disable" ? "Disable access" : "Rotate password",
    onConfirm: () => change(operation),
  });

  const ready = status?.state === "READY" && !error;
  const value = (label: string, content: string | null | undefined) => (
    <div className="space-y-1.5">
      <p className="text-xs font-medium text-[var(--fg-muted)]">{label}</p>
      <div className="flex items-center gap-2 rounded-lg border border-[var(--border)] bg-[var(--fill)] px-3 py-2">
        <span className="min-w-0 flex-1 break-all font-mono text-sm">{content || "Not ready"}</span>
        {content && <button type="button" className={`${buttonGhost} !min-h-8 !px-2 !text-xs`} aria-label={`Copy ${label}`} onClick={() => void copy(content)}>Copy</button>}
      </div>
    </div>
  );

  return dialog || (
    <Dialog title="Remote WinBox Access" description="Connect from the WinBox application over AirPairy's public TCP relay and your router's private WireGuard tunnel." onClose={onClose} wide>
      <div className="space-y-5 text-[var(--fg)]">
        <ErrorNotice text={error} />
        {!status ? error ? <button type="button" className={buttonGhost} onClick={() => void load()}>Retry status</button> : <ListSkeleton rows={4} avatar={false} /> : <>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <Badge tone={ready ? "green" : status.enabled ? "amber" : "slate"} dot>{status.enabled ? "Enabled" : "Disabled"} · {humanize(status.state)}</Badge>
            <button type="button" className={buttonGhost} onClick={() => void load()} disabled={busy}>Refresh status</button>
          </div>
          <p role="status" className="text-sm text-[var(--fg-muted)]">{status.message}</p>
          {value("WinBox Address", ready ? status.address : null)}
          {value("Username", status.username)}
          <div className="space-y-1.5">
            <p className="text-xs font-medium text-[var(--fg-muted)]">Password</p>
            <div className="flex flex-wrap items-center gap-2 rounded-lg border border-[var(--border)] bg-[var(--fill)] px-3 py-2">
              <input aria-label="WinBox password" readOnly autoComplete="off" type={password ? "text" : "password"} value={password} placeholder={ready ? "••••••••" : "Not ready"} className="min-w-0 flex-1 bg-transparent font-mono text-sm outline-none" />
              <button type="button" className={buttonGhost} disabled={!ready || busy} onClick={() => void sensitivePassword()}>{password ? "Hide" : "Reveal"}</button>
              <button type="button" aria-label="Copy password" className={buttonGhost} disabled={!ready || busy} onClick={() => void sensitivePassword(true)}>Copy</button>
            </div>
          </div>
          {value("Router WireGuard IP", status.tunnelIp)}
          <p className="text-xs text-[var(--fg-muted)]">Last successful hub TCP check: {date(status.lastSuccessfulCheck)}. This is not proof of an external WinBox login.</p>
          <section className="space-y-2 rounded-lg border border-[var(--border)] p-4 text-sm">
            <h3 className="font-semibold">How to connect</h3>
            <ol className="list-inside list-decimal space-y-1 text-[var(--fg-muted)]">
              <li>Wait for Ready. Open an up-to-date MikroTik WinBox application on your computer.</li>
              <li>Paste the WinBox Address, including its TCP port, into Connect To.</li>
              <li>Enter the dedicated username and password, then connect. Do not use the private tunnel IP from an external network.</li>
              <li>Verify the router identity before making changes. Never share these credentials.</li>
            </ol>
            <p className="text-xs text-amber-600">This account can change network configuration and reboot this router. Incorrect changes can interrupt billing or remove remote access. It cannot manage users, reveal sensitive settings, or use SSH/API. The agent blocks WinBox on non-LAN interfaces other than the approved billing tunnel; review any custom management VPN before installation.</p>
          </section>
          {installation && <section className="space-y-2 rounded-lg border border-[var(--border)] p-4">
            <p className="text-sm">On this router only, open a local administrator terminal and run this one-time command. The agent needs elevated user-management permissions. Expires {date(installation.expiresAt)}.</p>
            <pre className="whitespace-pre-wrap break-all rounded bg-[var(--fill)] p-3 text-xs">{installation.command}</pre>
            <button type="button" className={buttonGhost} onClick={() => void copy(installation.command)}>Copy installation command</button>
          </section>}
          <div className="flex flex-wrap gap-2 border-t border-[var(--border)] pt-4">
            <button type="button" disabled={busy || (!status.enabled && !status.available)} className={buttonGhost} onClick={() => status.enabled ? confirmChange("disable") : void change("enable")}><BusyLabel busy={busy} label="Working…">{status.enabled ? "Disable access" : "Enable access"}</BusyLabel></button>
            {status.enabled && <>
              <button type="button" disabled={busy} className={buttonGhost} onClick={() => void install()}>{status.installed ? "Reinstall agent" : "Install router agent"}</button>
              <button type="button" disabled={busy} className={buttonGhost} onClick={() => confirmChange("rotate")}>Rotate password</button>
            </>}
          </div>
        </>}
      </div>
    </Dialog>
  );
}
