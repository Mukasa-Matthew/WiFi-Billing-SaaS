import React from "react";
import { CaretDown, Info } from "@/components/icons";

/** A WinBox menu path, e.g. <Menu>System → SNTP Client</Menu>. */
function Menu({ children }: { children: React.ReactNode }) {
  return <span className="font-semibold text-[var(--fg-body)]">{children}</span>;
}

function Cmd({ children }: { children: React.ReactNode }) {
  return <code className="rounded bg-[var(--fill)] px-1.5 py-0.5 font-mono text-[11px] text-[var(--fg-body)]">{children}</code>;
}

function Steps({ children }: { children: React.ReactNode }) {
  return <ol className="list-decimal space-y-1.5 pl-5">{children}</ol>;
}

interface Issue {
  id: string;
  title: React.ReactNode;
  body: React.ReactNode;
}

/**
 * Common problems owners hit while running the one-line setup command, written
 * for someone using WinBox rather than a network engineer. Native <details>
 * gives keyboard and screen-reader support for free.
 */
export function SetupTroubleshooting({ wanInterface, onNewCommand }: { wanInterface?: string; onNewCommand?: () => void }) {
  const wan = wanInterface?.trim() || "ether1";

  const issues: Issue[] = [
    {
      id: "paste",
      title: "Nothing happened when I pasted the command",
      body: (
        <>
          <Steps>
            <li>In WinBox, click <Menu>New Terminal</Menu> on the left menu.</li>
            <li>Paste the whole command. It is one single line, so make sure nothing was cut off.</li>
            <li>Press <Menu>Enter</Menu> and wait. It can take up to a minute.</li>
          </Steps>
          <p>Sign in to WinBox as <Menu>admin</Menu> (or another user with full rights). A read-only user can&apos;t make the changes and the command will stop.</p>
        </>
      ),
    },
    {
      id: "ssl",
      title: <>It says <Cmd>fetch failed</Cmd> or <Cmd>ssl error</Cmd></>,
      body: (
        <>
          <p>This almost always means the router&apos;s clock is wrong. A router that thinks it&apos;s the wrong date can&apos;t open secure (https) links.</p>
          <Steps>
            <li>In WinBox open <Menu>System → SNTP Client</Menu>.</li>
            <li>Tick <Menu>Enabled</Menu> and add <Cmd>pool.ntp.org</Cmd> as a server, then click <Menu>OK</Menu>.</li>
            <li>Wait about 30 seconds and check <Menu>System → Clock</Menu> shows today&apos;s date and time.</li>
            <li>Run the setup command again.</li>
          </Steps>
        </>
      ),
    },
    {
      id: "internet",
      title: <>It says <Cmd>fetch failed</Cmd>, no response, or &ldquo;check internet access&rdquo;</>,
      body: (
        <>
          <p>If there&apos;s no mention of ssl, the router itself probably can&apos;t reach the internet.</p>
          <Steps>
            <li>Check the cable from your internet provider is plugged into <Cmd>{wan}</Cmd>, the internet port you chose in step 1.</li>
            <li>In WinBox open <Menu>Tools → Ping</Menu>, type <Cmd>8.8.8.8</Cmd> and click <Menu>Start</Menu>.</li>
            <li>If you see replies, run the setup command again. If you don&apos;t, fix the internet connection first (check the provider&apos;s modem or call them).</li>
          </Steps>
        </>
      ),
    },
    {
      id: "wan",
      title: <>It says <Cmd>WAN interface not found</Cmd></>,
      body: (
        <p>
          The router doesn&apos;t have a port called <Cmd>{wan}</Cmd>, and nothing on it was changed. Check the port names under <Menu>Interfaces</Menu> in WinBox. Then delete this router from your Routers list and add it again with the right port name.
        </p>
      ),
    },
    {
      id: "v7",
      title: <>It says <Cmd>RouterOS v7 or newer is required</Cmd></>,
      body: (
        <>
          <p>The router&apos;s software is too old. Updating is free:</p>
          <Steps>
            <li>In WinBox open <Menu>System → Packages</Menu>.</li>
            <li>Click <Menu>Check For Updates</Menu>, then <Menu>Download &amp; Install</Menu>.</li>
            <li>The router restarts by itself. Reconnect WinBox and run the setup command again.</li>
          </Steps>
        </>
      ),
    },
    {
      id: "device-mode",
      title: <>It says <Cmd>device mode not allowed</Cmd></>,
      body: (
        <>
          <p>Newer MikroTik routers lock some features until you allow them.</p>
          <Steps>
            <li>In <Menu>New Terminal</Menu>, run <Cmd>/system/device-mode/update mode=advanced</Cmd></li>
            <li>Within 5 minutes, unplug the router&apos;s power, wait a few seconds and plug it back in. (A normal restart from WinBox doesn&apos;t count.)</li>
            <li>Reconnect WinBox and run the setup command again.</li>
          </Steps>
        </>
      ),
    },
    {
      id: "never-connects",
      title: "The command ran but the router never shows as connected",
      body: (
        <>
          <p>Each setup command works only once and expires after 30 minutes. If it was used before or has expired, the router can&apos;t finish setting up.</p>
          {onNewCommand ? (
            <p>
              <button type="button" onClick={onNewCommand} className="font-semibold text-[var(--accent-text)] hover:underline">Generate a new command</button>
              {" "}and paste it again.
            </p>
          ) : (
            <p>Click <Menu>Generate a new command</Menu> above and paste it again.</p>
          )}
          <p>Also make sure the router wasn&apos;t reset or switched off while setup was running. If it was, run a new command once it&apos;s back on.</p>
        </>
      ),
    },
    {
      id: "already",
      title: <>It says <Cmd>already provisioned</Cmd> or <Cmd>no changes made</Cmd></>,
      body: (
        <>
          <p>This router is already set up for AirPairy, so nothing needed to change.</p>
          <p>
            If it was set up for a different AirPairy account (for example, a second-hand router), reset it first: <Menu>System → Reset Configuration</Menu>, tick <Menu>No Default Configuration</Menu>, click <Menu>Reset Configuration</Menu>, then run a new setup command once it restarts.
          </p>
        </>
      ),
    },
    {
      id: "offline-later",
      title: "The router connected once, then went offline",
      body: (
        <p>
          Check the venue still has power and internet. You don&apos;t need to run the setup again: the router reconnects to AirPairy by itself as soon as its internet comes back.
        </p>
      ),
    },
  ];

  return (
    <section aria-labelledby="setup-troubleshooting" className="overflow-hidden rounded-xl border border-[var(--border)] bg-[var(--surface)]">
      <div className="flex items-start gap-3 border-b border-[var(--border)] px-5 py-4">
        <Info className="mt-0.5 h-4 w-4 shrink-0 text-[var(--accent-text)]" />
        <div>
          <h3 id="setup-troubleshooting" className="text-sm font-semibold text-[var(--fg)]">Something went wrong?</h3>
          <p className="mt-0.5 text-xs text-[var(--fg-muted)]">Find the message you saw in the WinBox terminal.</p>
        </div>
      </div>
      <div className="divide-y divide-[var(--border)]">
        {issues.map((issue) => (
          <details key={issue.id} className="group">
            <summary className="flex cursor-pointer list-none items-center justify-between gap-4 px-5 py-3.5 text-sm font-medium text-[var(--fg)] hover:bg-[var(--subtle)] focus:outline-none focus-visible:bg-[var(--subtle)] [&::-webkit-details-marker]:hidden">
              <span>{issue.title}</span>
              <CaretDown className="h-4 w-4 shrink-0 text-[var(--fg-muted)] transition-transform group-open:rotate-180" />
            </summary>
            <div className="space-y-2.5 px-5 pb-4 pt-1 text-xs leading-6 text-[var(--fg-muted)]">{issue.body}</div>
          </details>
        ))}
      </div>
    </section>
  );
}
