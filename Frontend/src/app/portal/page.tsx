"use client";

/**
 * Captive portal — the page a customer lands on when they join the hotspot,
 * before they have internet access.
 *
 * Design constraints that shaped this file:
 *  - It renders on a phone, over a throttled pre-auth link, often outdoors.
 *    So: no component library, no web fonts, no images. Inline SVG + CSS only.
 *  - Every control is >=44px and inputs are >=16px so iOS does not zoom on focus.
 *  - Colour comes from [data-portal-theme] tokens in globals.css, never literals,
 *    so all three operator templates stay contrast-checked.
 */

import React, { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import api from "@/lib/api";
import {
  WifiHigh, Ticket, ShieldCheck, Lightning, Devices, ArrowRight,
  CheckCircle, WarningCircle, Phone, WhatsappLogo, ChatCircleText, X,
  SpinnerGap, CaretRight, Globe,
} from "@/components/icons";

/* ---------------------------------------------------------------- types --- */

interface PortalPackage {
  id: number; name: string; price: number; duration: number;
  rateLimit: string; deviceLimit: number; featured: boolean;
}
interface PortalDevice { mac: string; ip: string; router: string }
interface PortalConfig {
  tenantName: string; template: string; font: string | null; language: string | null;
  primaryColor: string | null; logoSize: number | null; hasLogo: boolean;
  showPackages: boolean; allowPurchases: boolean; showDeviceLimits: boolean;
  showPackageTime: boolean; showDeviceMac: boolean; buyForOther: boolean;
  showFeedback: boolean; supportPhone: string | null; whatsappPhone: string | null;
  supportEmail: string | null; redirectUrl: string | null; voucherCodeLength: number;
  device: PortalDevice; packages: PortalPackage[];
}
interface PortalSession {
  code: string; packageName: string; rateLimit: string; deviceLimit: number;
  secondsRemaining: number; bytesUsed: number; device: PortalDevice; redirectUrl: string | null;
}

/* ------------------------------------------------------------ i18n ------- */
/* Operators pick the language in Settings → Captive Portal. Keys stay English. */

interface Strings {
  tagline: string; haveCode: string; codeHint: string; connect: string; connecting: string;
  packages: string; buy: string; connected: string; timeLeft: string; plan: string;
  speed: string; devices: string; device: string; browse: string; support: string;
  call: string; whatsapp: string; feedback: string; payTitle: string; payPhone: string;
  payHint: string; payFor: string; payForHint: string; pay: string; cancel: string;
  sending: string; feedbackTitle: string; feedbackLabel: string; feedbackHint: string;
  send: string; rating: string; secured: string;
  deviceOne: string; deviceMany: string;
}

const COPY: Record<string, Strings> = {
  English: {
    tagline: "Connect to the internet", haveCode: "Enter your voucher code",
    codeHint: "The code is printed on your voucher or sent by SMS.",
    connect: "Get online", connecting: "Connecting you…", packages: "Or buy a package",
    buy: "Buy", connected: "You are online", timeLeft: "Time remaining", plan: "Package",
    speed: "Speed", devices: "Devices", device: "This device", browse: "Start browsing",
    support: "Need help?", call: "Call support", whatsapp: "WhatsApp", feedback: "Leave feedback",
    payTitle: "Buy", payPhone: "Mobile money number", payHint: "You will get a prompt on this phone to approve the payment.",
    payFor: "This is for another device (TV, laptop)", payForHint: "We will send the voucher code by SMS.",
    pay: "Request payment", cancel: "Cancel", sending: "Sending request…",
    feedbackTitle: "Leave feedback", feedbackLabel: "How is the connection?",
    feedbackHint: "Your operator reads every message.", send: "Send feedback",
    rating: "Rating", secured: "Secured by FreeRADIUS",
    deviceOne: "device", deviceMany: "devices",
  },
  Luganda: {
    tagline: "Yungako ku yintaneeti", haveCode: "Yingiza koodi yo",
    codeHint: "Koodi eri ku vawuca yo oba yaweerezebwa ku SMS.",
    connect: "Yungako", connecting: "Tukuyunga…", packages: "Oba gula pakeegi",
    buy: "Gula", connected: "Oli ku yintaneeti", timeLeft: "Ebbanga erisigadde", plan: "Pakeegi",
    speed: "Sipiidi", devices: "Ebyuma", device: "Ekyuma kino", browse: "Tandika okukozesa",
    support: "Weetaaga obuyambi?", call: "Kuba essimu", whatsapp: "WhatsApp", feedback: "Wandiika endowooza",
    payTitle: "Gula", payPhone: "Namba ya mobile money", payHint: "Ojja kufuna okusaba ku ssimu eno okukakasa.",
    payFor: "Kino kya kyuma kirala (TV, laptop)", payForHint: "Tujja kuweereza koodi ku SMS.",
    pay: "Saba okusasula", cancel: "Sazaamu", sending: "Tuweereza…",
    feedbackTitle: "Wandiika endowooza", feedbackLabel: "Yintaneeti eri etya?",
    feedbackHint: "Nannyini bizinensi asoma buli bubaka.", send: "Weereza",
    rating: "Ebbaluwa", secured: "Ekuumibwa FreeRADIUS",
    deviceOne: "ekyuma", deviceMany: "ebyuma",
  },
};

const stringsFor = (language: string | null): Strings =>
  COPY[language || "English"] ?? COPY.English;

const THEMES = ["midnight", "daylight", "ember"];

/* The query string never changes after load, so there is nothing to subscribe
   to; the server snapshot is empty because prerendering has no request URL. */
const subscribeNever = () => () => {};

function previewOverrides(search: string): Partial<PortalConfig> {
  const q = new URLSearchParams(search);
  const out: Partial<PortalConfig> = {};
  const theme = q.get("theme");
  if (theme && THEMES.includes(theme)) out.template = theme;
  if (q.get("font")) out.font = q.get("font");
  if (q.get("lang")) out.language = q.get("lang");
  if (q.get("name")) out.tenantName = q.get("name") as string;

  const flags: [string, keyof PortalConfig][] = [
    ["showPackages", "showPackages"], ["allowPurchases", "allowPurchases"],
    ["deviceLimits", "showDeviceLimits"], ["packageTime", "showPackageTime"],
    ["deviceMac", "showDeviceMac"], ["buyForOther", "buyForOther"],
    ["feedback", "showFeedback"],
  ];
  for (const [param, field] of flags) {
    const v = q.get(param);
    if (v === "1" || v === "0") (out as Record<string, unknown>)[field] = v === "1";
  }
  return out;
}

/* ------------------------------------------------------- formatting ------ */

const ugx = (value: number) => `UGX ${value.toLocaleString()}`;

function duration(seconds: number) {
  if (seconds % 2592000 === 0) { const n = seconds / 2592000; return `${n} month${n === 1 ? "" : "s"}`; }
  if (seconds % 604800 === 0) { const n = seconds / 604800; return `${n} week${n === 1 ? "" : "s"}`; }
  if (seconds % 86400 === 0) { const n = seconds / 86400; return `${n} day${n === 1 ? "" : "s"}`; }
  if (seconds % 3600 === 0) { const n = seconds / 3600; return `${n} hour${n === 1 ? "" : "s"}`; }
  return `${Math.round(seconds / 60)} min`;
}

function clock(seconds: number) {
  const s = Math.max(0, seconds);
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (d > 0) return `${d}d ${h}h ${m}m`;
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}`;
}

const speedLabel = (rateLimit: string) => rateLimit.split("/")[0].replace(/M$/, " Mbps");

/* -------------------------------------------------------- primitives ----- */

const surface =
  "rounded-[var(--p-radius)] border border-[var(--p-border)] bg-[var(--p-surface)] shadow-[var(--p-shadow)] backdrop-blur-xl";

/* Package rows sit on the same surface but need a resting and a hover state. */
const packageRow =
  `${surface} flex min-h-[64px] w-full items-center gap-3 p-4 text-left transition-[background-color,border-color,transform] ` +
  "hover:border-[var(--p-border-strong)] hover:bg-[var(--p-accent-soft)] motion-safe:hover:-translate-y-0.5 " +
  "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--p-ring)]";

/** 44px minimum height, accent fill, spinner swaps in without resizing. */
function PrimaryButton({ busy, busyLabel, children, ...rest }: {
  busy?: boolean; busyLabel?: string; children: React.ReactNode;
} & React.ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      {...rest}
      disabled={busy || rest.disabled}
      className="flex min-h-[52px] w-full items-center justify-center gap-2 rounded-xl bg-[var(--p-accent)] px-5 text-base font-semibold text-[var(--p-accent-ink)] transition-colors hover:bg-[var(--p-accent-hover)] focus-visible:outline-2 focus-visible:outline-offset-3 focus-visible:outline-[var(--p-ring)] disabled:cursor-not-allowed disabled:opacity-60"
      style={{ touchAction: "manipulation" }}
    >
      {busy
        ? <><SpinnerGap size={20} className="animate-spin" aria-hidden />{busyLabel}</>
        : children}
    </button>
  );
}

/* ------------------------------------------------------------- page ------ */

type Stage = "loading" | "enter" | "connecting" | "connected";

export default function CaptivePortalPage() {
  const [config, setConfig] = useState<PortalConfig | null>(null);
  const [stage, setStage] = useState<Stage>("loading");
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [session, setSession] = useState<PortalSession | null>(null);
  const [buying, setBuying] = useState<PortalPackage | null>(null);
  const [feedbackOpen, setFeedbackOpen] = useState(false);
  const codeRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    api.get<PortalConfig>("/api/portal/config")
      .then((r) => { setConfig(r.data); setStage("enter"); })
      .catch(() => setStage("enter"));
  }, []);

  /* Live countdown once the customer is online. */
  useEffect(() => {
    if (stage !== "connected") return;
    const timer = window.setInterval(() => {
      setSession((s) => (s && s.secondsRemaining > 0 ? { ...s, secondsRemaining: s.secondsRemaining - 1 } : s));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [stage]);

  /* Settings → Captive Portal iframes this page and passes the operator's
     unsaved form state as query params, so the preview updates as they edit
     rather than only after a save. */
  const search = useSyncExternalStore(subscribeNever, () => window.location.search, () => "");
  const overrides = useMemo(() => previewOverrides(search), [search]);

  const view: PortalConfig | null = config ? { ...config, ...overrides } : null;
  const t = stringsFor(view?.language ?? null);
  const theme = overrides.template || config?.template || "midnight";

  const redeem = useCallback(async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    setStage("connecting");
    try {
      const r = await api.post<{ ok: boolean; message?: string; session?: PortalSession }>(
        "/api/portal/redeem", { code: code.trim().toUpperCase() },
      );
      if (r.data.ok && r.data.session) {
        setSession(r.data.session);
        setStage("connected");
      } else {
        setError(r.data.message || "We could not connect you. Please try again.");
        setStage("enter");
        requestAnimationFrame(() => codeRef.current?.focus());
      }
    } catch {
      setError("We could not reach the hotspot. Move closer to the router and try again.");
      setStage("enter");
    }
  }, [code]);

  return (
    <div
      data-portal-theme={theme}
      className="relative flex min-h-dvh flex-col overflow-x-hidden bg-[var(--p-bg)] text-[var(--p-text)]"
      style={view?.font ? { fontFamily: `${view.font}, system-ui, sans-serif` } : undefined}
    >
      {/* Ambient wash. Pure CSS, no image payload, hidden from assistive tech. */}
      <div aria-hidden className="pointer-events-none absolute inset-0 overflow-hidden">
        <div className="absolute -left-24 -top-32 h-80 w-80 rounded-full opacity-25 blur-3xl lg:-left-20 lg:h-[34rem] lg:w-[34rem] lg:opacity-30"
          style={{ background: "var(--p-glow-1)" }} />
        <div className="absolute -bottom-40 -right-20 h-96 w-96 rounded-full opacity-20 blur-3xl lg:h-[32rem] lg:w-[32rem] lg:opacity-25"
          style={{ background: "var(--p-glow-2)" }} />
        <div className="absolute inset-0 hidden lg:block"
          style={{ background: "radial-gradient(60% 50% at 50% 45%, transparent 0%, var(--p-bg) 100%)" }} />
      </div>

      {/* One column on phones (DOM order is the reading order). From lg the same
          nodes are placed into two columns: identity and help on the left, the
          thing the customer came to do on the right. */}
      <main
        className="relative mx-auto grid w-full max-w-[27rem] flex-1 grid-cols-1 content-start gap-5
                   px-4 py-10 sm:max-w-[30rem] sm:px-6
                   lg:max-w-5xl lg:grid-cols-[minmax(0,1fr)_27rem] lg:content-center
                   lg:gap-x-16 lg:gap-y-7 lg:px-8 lg:py-16"
      >
        <Brand config={view} t={t} />

        {stage === "loading" && <SkeletonCard />}

        {stage === "connected" && session
          ? <ConnectedPanel session={session} config={view} t={t} />
          : stage !== "loading" && (
            <>
              <div className="lg:col-start-2 lg:row-start-1">
                <VoucherCard
                  t={t} code={code} setCode={setCode} error={error}
                  busy={stage === "connecting"} onSubmit={redeem} inputRef={codeRef}
                  length={view?.voucherCodeLength ?? 8}
                />
              </div>
              {view?.showPackages && view.packages.length > 0 && (
                <PackageList
                  t={t} config={view}
                  onBuy={view.allowPurchases ? setBuying : undefined}
                />
              )}
            </>
          )}

        <SupportFooter t={t} config={view} onFeedback={() => setFeedbackOpen(true)} />
      </main>

      {buying && (
        <PurchaseSheet
          t={t} plan={buying} config={view}
          onClose={() => setBuying(null)}
        />
      )}
      {feedbackOpen && <FeedbackSheet t={t} onClose={() => setFeedbackOpen(false)} />}
    </div>
  );
}

/* ----------------------------------------------------------- sections ---- */

function Brand({ config, t }: { config: PortalConfig | null; t: Strings }) {
  const size = config?.logoSize ?? 64;
  return (
    <header className="portal-rise flex flex-col items-center text-center lg:col-start-1 lg:row-start-1 lg:items-start lg:self-end lg:text-left">
      <div
        className="mb-4 flex shrink-0 items-center justify-center rounded-2xl border border-[var(--p-border)] bg-[var(--p-accent-soft)] lg:mb-6"
        style={{ width: size, height: size }}
      >
        <WifiHigh size={Math.round(size * 0.46)} className="text-[var(--p-accent)]" aria-hidden />
      </div>
      <h1 className="text-[1.6rem] font-bold leading-tight tracking-tight text-balance sm:text-[1.8rem] lg:text-[2.6rem] lg:leading-[1.08]">
        {config?.tenantName || "Hotspot"}
      </h1>
      <p className="mt-1.5 max-w-[32ch] text-[0.95rem] text-[var(--p-muted)] lg:mt-3 lg:text-[1.05rem]">{t.tagline}</p>
    </header>
  );
}

function SkeletonCard() {
  return (
    <div className={`${surface} p-6 lg:col-start-2 lg:row-start-1 lg:row-span-2 lg:self-start`} aria-busy="true" aria-label="Loading portal">
      <div className="h-4 w-32 rounded bg-[var(--p-field)]" />
      <div className="mt-4 h-14 w-full rounded-xl bg-[var(--p-field)]" />
      <div className="mt-3 h-[52px] w-full rounded-xl bg-[var(--p-field)]" />
    </div>
  );
}

function VoucherCard({ t, code, setCode, error, busy, onSubmit, inputRef, length }: {
  t: Strings; code: string; setCode: (v: string) => void; error: string | null;
  busy: boolean; onSubmit: (e: React.FormEvent) => void;
  inputRef: React.RefObject<HTMLInputElement | null>; length: number;
}) {
  return (
    <section className={`${surface} portal-rise p-5`} style={{ animationDelay: "40ms" }}>
      <div className="mb-4 flex items-center gap-2.5">
        <Ticket size={20} className="text-[var(--p-accent)]" aria-hidden />
        <h2 className="text-base font-semibold">{t.haveCode}</h2>
      </div>

      <form onSubmit={onSubmit} noValidate>
        <label htmlFor="voucher-code" className="sr-only">{t.haveCode}</label>
        <input
          id="voucher-code"
          ref={inputRef}
          value={code}
          onChange={(e) => setCode(e.target.value.toUpperCase())}
          placeholder={"X".repeat(Math.min(length, 10))}
          autoComplete="one-time-code"
          autoCapitalize="characters"
          autoCorrect="off"
          spellCheck={false}
          inputMode="text"
          enterKeyHint="go"
          maxLength={length + 4}
          aria-describedby={error ? "voucher-error" : "voucher-hint"}
          aria-invalid={error ? true : undefined}
          className={`h-16 w-full rounded-xl border bg-[var(--p-field)] text-center text-2xl font-bold tracking-[0.22em] text-[var(--p-text)] outline-none transition-colors placeholder:font-normal placeholder:tracking-[0.22em] placeholder:text-[var(--p-muted)] placeholder:opacity-45 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--p-ring)] ${
            error ? "border-[var(--p-danger)]" : "border-[var(--p-border-strong)]"
          }`}
        />

        {error
          ? (
            <p id="voucher-error" role="alert" aria-live="assertive"
              className="mt-3 flex items-start gap-2 text-sm leading-snug text-[var(--p-danger)]">
              <WarningCircle size={17} className="mt-0.5 shrink-0" aria-hidden />
              <span>{error}</span>
            </p>
          )
          : <p id="voucher-hint" className="mt-3 text-center text-sm text-[var(--p-muted)]">{t.codeHint}</p>}

        <div className="mt-4">
          <PrimaryButton type="submit" busy={busy} busyLabel={t.connecting} disabled={code.trim().length < 4}>
            {t.connect}
            <ArrowRight size={19} aria-hidden />
          </PrimaryButton>
        </div>
      </form>
    </section>
  );
}

function PackageList({ t, config, onBuy }: {
  t: Strings; config: PortalConfig; onBuy?: (p: PortalPackage) => void;
}) {
  return (
    <section className="portal-rise lg:col-start-2 lg:row-start-2" style={{ animationDelay: "80ms" }}>
      <h2 className="mb-3 px-1 text-sm font-semibold uppercase tracking-wider text-[var(--p-muted)]">
        {t.packages}
      </h2>
      <ul className="flex flex-col gap-2.5">
        {config.packages.map((p) => {
          const meta = [
            config.showPackageTime ? duration(p.duration) : null,
            speedLabel(p.rateLimit),
            config.showDeviceLimits ? `${p.deviceLimit} ${p.deviceLimit === 1 ? t.deviceOne : t.deviceMany}` : null,
          ].filter(Boolean);

          const body = (
            <>
              <span className="min-w-0 flex-1">
                <span className="flex items-center gap-2">
                  <span className="truncate text-[1.02rem] font-semibold">{p.name}</span>
                  {p.featured && (
                    <span className="shrink-0 rounded-full bg-[var(--p-accent-soft)] px-2 py-0.5 text-[0.68rem] font-bold uppercase tracking-wide text-[var(--p-accent)]">
                      Popular
                    </span>
                  )}
                </span>
                <span className="mt-1 block truncate text-[0.82rem] text-[var(--p-muted)]">
                  {meta.join(" · ")}
                </span>
              </span>
              <span className="flex shrink-0 items-center gap-1.5">
                <span className="text-right text-[1.02rem] font-bold tabular-nums">{ugx(p.price)}</span>
                {onBuy && <CaretRight size={16} className="text-[var(--p-muted)]" aria-hidden />}
              </span>
            </>
          );

          /* Only interactive when the operator allows online purchases — otherwise
             it is a price list, and a button that does nothing would be a lie. */
          return (
            <li key={p.id}>
              {onBuy ? (
                <button
                  type="button"
                  onClick={() => onBuy(p)}
                  aria-label={`${t.buy} ${p.name}, ${ugx(p.price)}`}
                  style={{ touchAction: "manipulation" }}
                  className={packageRow}
                >
                  {body}
                </button>
              ) : (
                <div className={`${surface} flex min-h-[64px] w-full items-center gap-3 p-4`}>{body}</div>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}

function ConnectedPanel({ session, config, t }: {
  session: PortalSession; config: PortalConfig | null; t: Strings;
}) {
  const rows = useMemo(() => [
    { icon: Ticket, label: t.plan, value: session.packageName },
    { icon: Lightning, label: t.speed, value: speedLabel(session.rateLimit) },
    { icon: Devices, label: t.devices, value: String(session.deviceLimit) },
    ...(config?.showDeviceMac ? [{ icon: Globe, label: t.device, value: session.device.mac }] : []),
  ], [session, config, t]);

  return (
    <section className={`${surface} portal-rise overflow-hidden lg:col-start-2 lg:row-start-1 lg:row-span-2 lg:self-start`}>
      <div className="flex flex-col items-center border-b border-[var(--p-border)] px-5 py-7 text-center">
        <CheckCircle size={46} className="text-[var(--p-success)]" aria-hidden />
        <h2 className="mt-3 text-xl font-bold">{t.connected}</h2>

        <p className="mt-5 text-xs font-semibold uppercase tracking-wider text-[var(--p-muted)]">
          {t.timeLeft}
        </p>
        {/* aria-live so screen readers hear the session start, but "off"-style
            cadence: only the minute-level value is announced, not every tick. */}
        <p className="mt-1 text-[2.4rem] font-bold leading-none tabular-nums" aria-live="polite" aria-atomic="true">
          {clock(session.secondsRemaining)}
        </p>
      </div>

      <dl className="divide-y divide-[var(--p-border)]">
        {rows.map(({ icon: Icon, label, value }) => (
          <div key={label} className="flex items-center justify-between gap-3 px-5 py-3.5">
            <dt className="flex items-center gap-2.5 text-sm text-[var(--p-muted)]">
              <Icon size={17} aria-hidden />{label}
            </dt>
            <dd className="truncate text-sm font-semibold tabular-nums">{value}</dd>
          </div>
        ))}
      </dl>

      {session.redirectUrl && (
        <div className="p-5 pt-4">
          <a
            href={session.redirectUrl}
            className="flex min-h-[52px] w-full items-center justify-center gap-2 rounded-xl bg-[var(--p-accent)] px-5 text-base font-semibold text-[var(--p-accent-ink)] transition-colors hover:bg-[var(--p-accent-hover)] focus-visible:outline-2 focus-visible:outline-offset-3 focus-visible:outline-[var(--p-ring)]"
          >
            {t.browse}<ArrowRight size={19} aria-hidden />
          </a>
        </div>
      )}
    </section>
  );
}

function SupportFooter({ t, config, onFeedback }: {
  t: Strings; config: PortalConfig | null; onFeedback: () => void;
}) {
  const tel = config?.supportPhone;
  const wa = config?.whatsappPhone?.replace(/[^\d]/g, "");
  /* Equal-width on phones so the row fills the card; content-width from lg so
     labels never break across two lines. */
  const link =
    "flex min-h-[44px] flex-1 items-center justify-center gap-2 whitespace-nowrap rounded-xl border border-[var(--p-border)] bg-[var(--p-surface)] px-3 text-sm font-semibold text-[var(--p-text)] transition-colors hover:border-[var(--p-border-strong)] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--p-ring)] lg:flex-none lg:px-4";

  return (
    <footer className="portal-rise mt-auto pt-3 lg:col-start-1 lg:row-start-2 lg:mt-0 lg:self-start lg:pt-0" style={{ animationDelay: "120ms" }}>
      {(tel || wa || config?.showFeedback) && (
        <>
          <p className="mb-2.5 text-center text-sm text-[var(--p-muted)] lg:text-left">{t.support}</p>
          <div className="flex flex-wrap gap-2.5 lg:max-w-md">
            {tel && <a href={`tel:${tel}`} className={link}><Phone size={17} aria-hidden />{t.call}</a>}
            {wa && <a href={`https://wa.me/${wa}`} target="_blank" rel="noopener noreferrer" className={link}>
              <WhatsappLogo size={17} aria-hidden />{t.whatsapp}
            </a>}
            {config?.showFeedback && (
              <button type="button" onClick={onFeedback} className={link} style={{ touchAction: "manipulation" }}>
                <ChatCircleText size={17} aria-hidden />{t.feedback}
              </button>
            )}
          </div>
        </>
      )}
      <p className="mt-5 flex items-center justify-center gap-1.5 text-xs text-[var(--p-muted)] lg:mt-6 lg:justify-start">
        <ShieldCheck size={14} aria-hidden />{t.secured}
      </p>
    </footer>
  );
}

/* -------------------------------------------------------------- sheets --- */

/** Bottom sheet: Escape closes, focus moves in on open and the scrim is a real button. */
function Sheet({ title, onClose, children }: {
  title: string; onClose: () => void; children: React.ReactNode;
}) {
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKey);
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    panelRef.current?.querySelector<HTMLElement>("input, button")?.focus();
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = previous;
    };
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center sm:items-center">
      <button aria-label="Close" onClick={onClose} className="absolute inset-0 bg-black/55 backdrop-blur-sm" />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="portal-sheet relative w-full max-w-[27rem] rounded-t-3xl border border-[var(--p-border)] bg-[var(--p-surface-solid)] p-5 pb-8 text-[var(--p-text)] shadow-2xl sm:rounded-3xl sm:pb-5"
      >
        <div className="mb-4 flex items-start justify-between gap-3">
          <h2 className="text-lg font-bold">{title}</h2>
          <button
            type="button" onClick={onClose} aria-label="Close"
            style={{ touchAction: "manipulation" }}
            className="-mr-1.5 -mt-1 flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-[var(--p-muted)] transition-colors hover:bg-[var(--p-field)] hover:text-[var(--p-text)] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--p-ring)]"
          >
            <X size={19} aria-hidden />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

const sheetField =
  "h-[52px] w-full rounded-xl border border-[var(--p-border-strong)] bg-[var(--p-field)] px-4 text-base text-[var(--p-text)] outline-none transition-colors placeholder:text-[var(--p-muted)] placeholder:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--p-ring)]";

function PurchaseSheet({ t, plan, config, onClose }: {
  t: Strings; plan: PortalPackage; config: PortalConfig | null; onClose: () => void;
}) {
  const [phone, setPhone] = useState("");
  const [forOther, setForOther] = useState(false);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const r = await api.post<{ ok: boolean; message?: string }>(
        "/api/portal/purchase", { packageId: plan.id, phone: phone.trim(), forOther },
      );
      if (r.data.ok) setDone(r.data.message || t.payHint);
      else setError(r.data.message || "The payment request failed. Please try again.");
    } catch {
      setError("We could not reach the payment service. Check your number and try again.");
    } finally { setBusy(false); }
  };

  return (
    <Sheet title={`${t.payTitle} ${plan.name}`} onClose={onClose}>
      {done ? (
        <div className="py-2 text-center">
          <CheckCircle size={44} className="mx-auto text-[var(--p-success)]" aria-hidden />
          <p className="mt-3 text-base font-semibold">{done}</p>
          <div className="mt-5">
            <PrimaryButton type="button" onClick={onClose}>{t.cancel === "Cancel" ? "Done" : t.cancel}</PrimaryButton>
          </div>
        </div>
      ) : (
        <form onSubmit={submit} noValidate>
          <div className="mb-4 flex items-center justify-between rounded-xl border border-[var(--p-border)] bg-[var(--p-accent-soft)] px-4 py-3">
            <span className="text-sm text-[var(--p-muted)]">
              {config?.showPackageTime ? duration(plan.duration) : plan.name} · {speedLabel(plan.rateLimit)}
            </span>
            <span className="text-lg font-bold tabular-nums">{ugx(plan.price)}</span>
          </div>

          <label htmlFor="pay-phone" className="mb-2 block text-sm font-semibold">{t.payPhone}</label>
          <input
            id="pay-phone" value={phone} onChange={(e) => setPhone(e.target.value)}
            type="tel" inputMode="tel" autoComplete="tel" enterKeyHint="go"
            placeholder="+256 7XX XXX XXX"
            aria-describedby={error ? "pay-error" : "pay-hint"}
            aria-invalid={error ? true : undefined}
            className={sheetField}
          />
          {error
            ? <p id="pay-error" role="alert" className="mt-2 flex items-start gap-1.5 text-sm text-[var(--p-danger)]">
                <WarningCircle size={16} className="mt-0.5 shrink-0" aria-hidden />{error}
              </p>
            : <p id="pay-hint" className="mt-2 text-sm text-[var(--p-muted)]">{t.payHint}</p>}

          {config?.buyForOther && (
            <label className="mt-4 flex cursor-pointer items-start gap-3 rounded-xl border border-[var(--p-border)] p-3.5">
              <input
                type="checkbox" checked={forOther} onChange={(e) => setForOther(e.target.checked)}
                className="mt-0.5 h-5 w-5 shrink-0 accent-[var(--p-accent)]"
              />
              <span>
                <span className="block text-sm font-semibold">{t.payFor}</span>
                <span className="mt-0.5 block text-sm text-[var(--p-muted)]">{t.payForHint}</span>
              </span>
            </label>
          )}

          <div className="mt-5">
            <PrimaryButton type="submit" busy={busy} busyLabel={t.sending} disabled={phone.trim().length < 9}>
              {t.pay} · {ugx(plan.price)}
            </PrimaryButton>
          </div>
        </form>
      )}
    </Sheet>
  );
}

function FeedbackSheet({ t, onClose }: { t: Strings; onClose: () => void }) {
  const [rating, setRating] = useState(0);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try { await api.post("/api/portal/feedback", { rating, message: text.trim() }); setDone(true); }
    catch { setDone(true); }
    finally { setBusy(false); }
  };

  return (
    <Sheet title={t.feedbackTitle} onClose={onClose}>
      {done ? (
        <div className="py-2 text-center">
          <CheckCircle size={44} className="mx-auto text-[var(--p-success)]" aria-hidden />
          <p className="mt-3 text-base font-semibold">{t.feedbackHint}</p>
          <div className="mt-5"><PrimaryButton type="button" onClick={onClose}>Done</PrimaryButton></div>
        </div>
      ) : (
        <form onSubmit={submit}>
          <fieldset className="mb-4">
            <legend className="mb-2 text-sm font-semibold">{t.feedbackLabel}</legend>
            {/* Radio group, not stars-as-buttons: keyboard and screen readers get
                a real "3 of 5" rather than five unlabelled icons. */}
            <div className="flex gap-2" role="radiogroup" aria-label={t.rating}>
              {[1, 2, 3, 4, 5].map((n) => (
                <button
                  key={n} type="button" role="radio" aria-checked={rating === n}
                  aria-label={`${n} / 5`} onClick={() => setRating(n)}
                  style={{ touchAction: "manipulation" }}
                  className={`h-12 flex-1 rounded-xl border text-base font-bold tabular-nums transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--p-ring)] ${
                    rating === n
                      ? "border-[var(--p-accent)] bg-[var(--p-accent)] text-[var(--p-accent-ink)]"
                      : "border-[var(--p-border-strong)] bg-[var(--p-field)] text-[var(--p-muted)]"
                  }`}
                >
                  {n}
                </button>
              ))}
            </div>
          </fieldset>

          <label htmlFor="feedback-text" className="mb-2 block text-sm font-semibold">{t.feedbackTitle}</label>
          <textarea
            id="feedback-text" value={text} onChange={(e) => setText(e.target.value)}
            rows={4} maxLength={500} placeholder="…"
            className={`${sheetField} h-auto resize-none py-3 leading-relaxed`}
          />
          <p className="mt-2 text-sm text-[var(--p-muted)]">{t.feedbackHint}</p>

          <div className="mt-5">
            <PrimaryButton type="submit" busy={busy} busyLabel={t.sending} disabled={!rating && !text.trim()}>
              {t.send}
            </PrimaryButton>
          </div>
        </form>
      )}
    </Sheet>
  );
}
