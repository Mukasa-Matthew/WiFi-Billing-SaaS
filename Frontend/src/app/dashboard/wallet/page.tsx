"use client";
import { BusyLabel, useSingleFlight } from "@/components/LoadingUI";
import React, { useCallback, useEffect, useState } from "react";
import api from "@/lib/api";
import { useResource, useApi, Table, Badge, ErrorNotice, FilterTabs, Pager, date, field, button, buttonGhost, panel, label, hint, message, PageHeader, Tabs, Skeleton, ugx } from "@/components/BillingUI";
import { RANGE_LABEL, presetRange, describeRange, type RangeKey } from "@/lib/paymentRange";
import { Dialog } from "@/components/ui/Dialog";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";
import { ArrowDown, ShieldCheck, EnvelopeSimple, Clock } from "@/components/icons";
import "./withdrawal-loader.css";

interface WalletSummary {
  businessName: string;
  available: number; pending: number; reserved: number;
  totalCollected: number; totalFees: number; totalWithdrawn: number;
  currency: string; openWithdrawals: number;
  limits: { min: number; max: number; approvalRequired: boolean; livePayouts: boolean; feeReservePercent: number; feeReserveMin: number };
  maxWithdrawable: number;
  platformFeePercent: number;
}
interface LedgerEntry {
  id: number; type: string; amount: number;
  availableAfter: number; pendingAfter: number; reservedAfter: number;
  externalRef: string; note: string | null; createdAt: string;
}
interface ActivityReport {
  from: string; to: string; openingBalance: number; moneyIn: number; moneyOut: number; closingBalance: number;
  entries: LedgerEntry[]; page: number; totalPages: number; totalElements: number;
}
interface Withdrawal {
  id: number; amount: number; fee: number; totalDeduction: number;
  phone: string; reason: string | null; status: string; failureReason: string | null; payoutRef: string;
  createdAt: string; completedAt: string | null;
}

/**
 * How each ledger entry reads to the owner, and which way it moves the available
 * balance (mirrors WalletService). A payout debit only clears money already on hold,
 * so it leaves the available balance alone.
 */
const LEDGER: Record<string, { name: string; effect: 1 | -1 | 0; hint?: string }> = {
  COLLECTION_CREDIT: { name: "Customer payment", effect: 1 },
  // Fees are record-only: the customer payment above is already credited net of them.
  FEE_DEBIT: { name: "Gateway fee", effect: 0, hint: "Already taken off the customer payment" },
  PLATFORM_FEE: { name: "Gateway fee", effect: 0, hint: "Already taken off the customer payment" },
  WITHDRAWAL_RESERVE: { name: "Held for withdrawal", effect: -1 },
  WITHDRAWAL_DEBIT: { name: "Withdrawal paid out", effect: 0, hint: "Already taken off when it was put on hold" },
  WITHDRAWAL_RELEASE: { name: "Returned to wallet", effect: 1 },
  ADJUSTMENT_CREDIT: { name: "Adjustment", effect: 1 },
  ADJUSTMENT_DEBIT: { name: "Adjustment", effect: -1 },
  EXTERNAL_WITHDRAWAL: { name: "Paid out by AirPairy", effect: -1 },
  SUBSCRIPTION_DEBIT: { name: "AirPairy subscription", effect: -1 },
  RESERVE_CORRECTION: { name: "Hold corrected", effect: 0, hint: "Doesn’t change your balance" },
};
const STATUS: Record<string, { label: string; tone: string }> = {
  COMPLETED: { label: "Paid", tone: "green" },
  PROCESSING: { label: "Sending", tone: "blue" },
  PENDING_APPROVAL: { label: "Awaiting review", tone: "amber" },
  REQUESTED: { label: "Requested", tone: "amber" },
  FAILED: { label: "Failed", tone: "red" },
  CANCELLED: { label: "Cancelled", tone: "slate" },
};
const statusOf = (s: string) => STATUS[s] ?? { label: s.replaceAll("_", " ").toLowerCase(), tone: "slate" };
const CANCELLABLE = ["REQUESTED", "PENDING_APPROVAL"];
const OPEN_STATUSES = ["REQUESTED", "PENDING_APPROVAL", "PROCESSING"];
// Mirrors WithdrawalService.feeReserve: held for the gateway fee, unused part returned.
const feeReserve = (amount: number, percent: number, min: number) => Math.max(min, Math.ceil((amount * percent) / 100));
/** Largest amount whose amount + fee reserve still fits in the available balance. */
const maxWithdrawable = (available: number, limit: number, percent: number, min: number) => {
  let a = Math.min(limit, Math.floor((available * 100) / (100 + percent)));
  while (a > 0 && a + feeReserve(a, percent, min) > available) a--;
  return Math.max(0, a);
};
/** Opaque per-attempt key: the server returns the original withdrawal for a repeat. */
const newIdempotencyKey = () =>
  globalThis.crypto?.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
const shortRef = (ref: string) => `WD-${ref.replaceAll("-", "").slice(0, 8).toUpperCase()}`;
// Mirrors otp.resend-cooldown-seconds on the server: one code per minute.
const RESEND_COOLDOWN_S = 60;
const httpStatus = (err: unknown) => (err as { response?: { status?: number } })?.response?.status;

/** Six code boxes over one real input, so typing, paste and SMS/email autofill all work. */
function CodeBoxes({ value, onChange, disabled, invalid }: { value: string; onChange: (v: string) => void; disabled?: boolean; invalid?: boolean }) {
  const [focused, setFocused] = useState(false);
  return (
    <div className="withdrawal-code">
      <input id="withdraw-otp" inputMode="numeric" autoComplete="one-time-code" autoFocus maxLength={6} pattern="[0-9]{6}"
        aria-label="6-digit verification code" aria-invalid={invalid} disabled={disabled} value={value}
        onFocus={() => setFocused(true)} onBlur={() => setFocused(false)}
        onChange={(e) => onChange(e.target.value.replace(/\D/g, "").slice(0, 6))} />
      {Array.from({ length: 6 }, (_, i) => (
        <span key={i} aria-hidden="true" className={[
          "withdrawal-code-box",
          value[i] ? "is-filled" : "",
          focused && i === Math.min(value.length, 5) ? "is-active" : "",
          invalid ? "is-invalid" : "",
        ].join(" ")}>{value[i] ?? ""}</span>
      ))}
    </div>
  );
}

export default function WalletPage() {
  const [summary, setSummary] = useState<WalletSummary | null>(null);
  const { data: withdrawals, loading, error: wErr, load: loadWithdrawals } = useResource<Withdrawal>("/api/wallet/withdrawals", true);
  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState("");
  const [phone, setPhone] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState("");
  const [otp, setOtp] = useState("");
  const [otpSent, setOtpSent] = useState(false);
  const [otpBusy, setOtpBusy] = useState(false);
  const [step, setStep] = useState<"details" | "verify">("details");
  const [view, setView] = useState<"withdrawals" | "activity">("withdrawals");
  const [actRange, setActRange] = useState<Exclude<RangeKey, "custom">>("month");
  const [actPage, setActPage] = useState(0);
  const actDates = presetRange(actRange);
  // Fetched only once the tab is opened.
  const act = useApi<ActivityReport>(view === "activity"
    ? `/api/wallet/activity/report?from=${actDates.from}&to=${actDates.to}&page=${actPage}&size=25` : null, 0, { keepPrevious: true });
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [otpNotice, setOtpNotice] = useState("");
  const [cooldown, setCooldown] = useState(0);
  const [submittedFor, setSubmittedFor] = useState("");
  const toast = useToast();
  const { confirm, dialog } = useConfirmDialog();
  // One key per withdrawal attempt: a retry after a dropped response reuses it, so it
  // can never create a second payout; it is replaced once the request succeeds.
  const [idempotencyKey, setIdempotencyKey] = useState("");
  const feePercent = summary?.limits.feeReservePercent ?? 10;
  const feeMin = summary?.limits.feeReserveMin ?? 1000;
  const available = Math.max(0, summary?.available ?? 0);
  const maxAmount = summary?.maxWithdrawable ?? maxWithdrawable(available, summary?.limits.max ?? 0, feePercent, feeMin);
  const requested = Number(amount) || 0;
  const requestedFee = requested > 0 ? feeReserve(requested, feePercent, feeMin) : 0;
  const overBalance = requested > 0 && requested + requestedFee > available;
  const underMinimum = requested > 0 && requested < (summary?.limits.min ?? 0);
  const canWithdraw = !!summary && maxAmount >= summary.limits.min;
  const openWithdraw = () => { setIdempotencyKey(newIdempotencyKey()); setFormError(""); setOtpNotice(""); setOtp(""); setOtpSent(false); setStep("details"); setOpen(true); };
  const goBack = () => { setFormError(""); setStep("details"); };

  useEffect(() => {
    if (cooldown <= 0) return;
    const t = window.setTimeout(() => setCooldown((c) => c - 1), 1000);
    return () => window.clearTimeout(t);
  }, [cooldown]);

  const loadSummary = useCallback(async () => {
    try { setSummary((await api.get<WalletSummary>("/api/wallet")).data); } catch { /* keep stale */ }
  }, []);
  useEffect(() => { const initial = window.setTimeout(loadSummary, 0); const timer = window.setInterval(loadSummary, 30000); return () => { window.clearTimeout(initial); window.clearInterval(timer); }; }, [loadSummary]);
  const refreshAll = () => { void loadSummary(); void act.load(); loadWithdrawals(); };

  const singleFlight = useSingleFlight();
  /** `resend` forces a new code; otherwise a code already sent in this dialog is reused. */
  const sendOtp = singleFlight(async (resend: boolean) => {
    if (!resend && otpSent) { setFormError(""); setStep("verify"); return; }
    setOtpBusy(true); setFormError(""); setOtpNotice("");
    try {
      const { data } = await api.post<{ sentTo?: string | null }>("/api/wallet/withdrawals/otp");
      setSentTo(data?.sentTo ?? null);
      setOtp(""); setSubmittedFor(""); setOtpSent(true); setCooldown(RESEND_COOLDOWN_S); setStep("verify");
      if (resend) setOtpNotice("A new code is on its way. Earlier codes no longer work.");
    } catch (err) {
      if (httpStatus(err) === 429) {
        // A code went out under a minute ago (e.g. the dialog was reopened): it is still
        // valid, so take the owner to the code entry instead of blocking them here.
        setOtpSent(true); setCooldown((c) => Math.max(c, RESEND_COOLDOWN_S)); setStep("verify");
        setOtpNotice("We emailed you a code less than a minute ago. Enter that code, or request a new one when the timer ends.");
      } else setFormError(message(err));
    } finally { setOtpBusy(false); }
  });

  const submit = singleFlight(async (code: string) => {
    setSubmittedFor(code);
    setBusy(true); setFormError("");
    try {
      await api.post("/api/wallet/withdrawals", { amount: requested, phone, reason: reason.trim(), otp: code },
        { headers: { "Idempotency-Key": idempotencyKey } });
      setIdempotencyKey("");
      setOpen(false); setAmount(""); setPhone(""); setReason(""); setOtp(""); setOtpSent(false); setOtpNotice("");
      toast("success", "Withdrawal request submitted for processing.");
      refreshAll();
    } catch (err) { setFormError(message(err)); setOtp(""); }
    finally { setBusy(false); }
  });
  const onCodeChange = (v: string) => {
    setOtp(v);
    if (v.length < 6) { if (formError) setFormError(""); return; }
    // Submit once all six digits are in (typed, pasted or autofilled), once per value.
    if (submittedFor !== v && !busy) void submit(v);
  };

  const cancel = (id: number) => confirm({
    title: "Cancel withdrawal request?",
    body: "The reserved funds will be released back to your available wallet balance after the server confirms cancellation.",
    confirmLabel: "Cancel withdrawal",
    onConfirm: async () => {
      try { await api.post(`/api/wallet/withdrawals/${id}/cancel`); toast("success", "Withdrawal request cancelled."); refreshAll(); }
      catch (err) { toast("error", message(err)); }
    },
  });

  const inProgress = (withdrawals ?? []).filter((w) => OPEN_STATUSES.includes(w.status));
  const firstLoad = !summary;

  return (
    <div className="space-y-6">
      {dialog}
      <PageHeader title="Wallet" description="Payments your customers make through AirPairy, ready to withdraw to mobile money." />

      <ErrorNotice text={wErr || act.error} />

      {/* Balance */}
      <section className={`${panel} overflow-hidden`} aria-label="Balance">
        <div className="grid lg:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
          <div className="flex flex-col justify-between gap-6 p-6 sm:p-7">
            <div>
              <p className="text-[13px] font-medium text-[var(--fg-muted)]">Available balance</p>
              <p className="num mt-2 text-[2.25rem] font-semibold leading-none tracking-tight text-[var(--fg)] sm:text-[2.6rem]">
                {firstLoad ? <Skeleton className="h-10 w-56" /> : ugx(summary.available)}
              </p>
              <p className="mt-3 text-sm text-[var(--fg-muted)]">
                {firstLoad ? <Skeleton className="h-4 w-72" /> : canWithdraw
                  ? <>You can withdraw up to <span className="num font-semibold text-[var(--fg)]">{ugx(maxAmount)}</span> now.</>
                  : <>You need at least <span className="num font-semibold text-[var(--fg)]">{ugx((summary.limits.min) + feeReserve(summary.limits.min, feePercent, feeMin))}</span> to make a withdrawal.</>}
              </p>
            </div>
            <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
              <button className={`${button} w-full shrink-0 whitespace-nowrap sm:w-auto`} onClick={openWithdraw} disabled={!canWithdraw}>
                <ArrowDown size={15} /> Withdraw
              </button>
              <p className="text-xs leading-relaxed text-[var(--fg-faint)]">
                Up to {feePercent}% (min {ugx(feeMin)}) is held per withdrawal for the gateway fee. Whatever isn’t used comes back.
                {summary?.limits.approvalRequired && " Withdrawals are reviewed before they’re sent."}
              </p>
            </div>
          </div>

          <dl className="grid divide-y divide-[var(--border)] border-t border-[var(--border)] bg-[var(--subtle)] sm:grid-cols-3 sm:divide-x sm:divide-y-0 lg:grid-cols-1 lg:divide-x-0 lg:divide-y lg:border-l lg:border-t-0">
            {[
              { label: "On hold", value: summary?.reserved, note: inProgress.length ? `${inProgress.length} withdrawal${inProgress.length > 1 ? "s" : ""} in progress` : "Nothing waiting to be sent" },
              { label: "Earned all time", value: summary?.totalCollected, note: `After ${ugx(summary?.totalFees)} in gateway fees` },
              { label: "Withdrawn all time", value: summary?.totalWithdrawn, note: "Sent to mobile money" },
            ].map((m) => (
              <div key={m.label} className="px-6 py-4">
                <dt className="text-xs font-medium text-[var(--fg-muted)]">{m.label}</dt>
                <dd className="num mt-1 text-lg font-semibold text-[var(--fg)]">{firstLoad ? <Skeleton className="h-6 w-28" /> : ugx(m.value)}</dd>
                <dd className="mt-0.5 text-xs text-[var(--fg-faint)]">{m.note}</dd>
              </div>
            ))}
          </dl>
        </div>
      </section>

      {/* Payouts still on their way */}
      {inProgress.length > 0 && (
        <section className={`${panel} divide-y divide-[var(--border)]`} aria-label="Withdrawals in progress">
          {inProgress.map((w) => {
            const st = statusOf(w.status);
            return (
              <div key={w.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-3.5">
                <span className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-blue-50 text-blue-600 dark:bg-blue-500/10 dark:text-blue-300"><Clock size={16} /></span>
                <div className="min-w-0 flex-1">
                  <p className="text-sm text-[var(--fg)]"><span className="num font-semibold">{ugx(w.amount)}</span> to <span className="num">{w.phone}</span></p>
                  <p className="text-xs text-[var(--fg-muted)]">Requested {date(w.createdAt)} · {shortRef(w.payoutRef)}</p>
                </div>
                <Badge tone={st.tone} dot>{st.label}</Badge>
                {CANCELLABLE.includes(w.status) && (
                  <button onClick={() => cancel(w.id)} className="min-h-9 rounded-lg px-3 text-xs font-semibold text-red-600 transition hover:bg-red-50 dark:hover:bg-red-500/10">Cancel</button>
                )}
              </div>
            );
          })}
        </section>
      )}

      {/* History */}
      <section className={`${panel} overflow-hidden`}>
        <div className="px-5 pt-3">
          <Tabs label="Wallet history" value={view} onChange={setView} tabs={[
            { key: "withdrawals", label: "Withdrawals", count: withdrawals?.length },
            { key: "activity", label: "Statement" },
          ]} />
        </div>
        {view === "withdrawals" ? (
          <Table
            bare
            loading={loading}
            rows={withdrawals}
            rowKey={(w: Withdrawal) => w.id}
            searchPlaceholder="Search withdrawals"
            emptyTitle="No withdrawals yet"
            emptyDescription="When you withdraw, each request and its status will show here."
            columns={[
              { label: "Date", render: (w: Withdrawal) => (
                <span className="whitespace-nowrap">
                  <span className="text-[var(--fg)]">{date(w.createdAt)}</span>
                  <span className="block font-mono text-[11px] text-[var(--fg-faint)]">{shortRef(w.payoutRef)}</span>
                  {/* Phones drop the Status column, so the badge rides under the date. */}
                  <span className="mt-1.5 block sm:hidden"><Badge tone={statusOf(w.status).tone} dot>{statusOf(w.status).label}</Badge></span>
                </span>
              )},
              { label: "Sent to", hideBelow: "md", render: (w: Withdrawal) => <span className="num whitespace-nowrap">{w.phone}</span> },
              { label: "Reason", hideBelow: "lg", render: (w: Withdrawal) => <span className="text-[var(--fg-muted)]">{w.reason || "—"}</span> },
              { label: "Status", hideBelow: "sm", render: (w: Withdrawal) => {
                const st = statusOf(w.status);
                return (
                  <span className="block min-w-28">
                    <Badge tone={st.tone} dot>{st.label}</Badge>
                    {w.status === "COMPLETED" && w.completedAt && <span className="mt-1 block text-[11px] text-[var(--fg-faint)]">{date(w.completedAt)}</span>}
                    {w.failureReason && <span className="mt-1 block max-w-36 whitespace-normal text-[11px] leading-snug text-red-600 sm:max-w-56">{w.failureReason}</span>}
                  </span>
                );
              }},
              { label: "Fee", align: "right", hideBelow: "lg", render: (w: Withdrawal) => <span className="num text-[var(--fg-muted)]">{w.fee ? ugx(w.fee) : "—"}</span> },
              { label: "Amount", align: "right", render: (w: Withdrawal) => <span className="num font-semibold text-[var(--fg)]">{ugx(w.amount)}</span> },
            ]}
          />
        ) : (
          <>
            <div className="flex flex-col gap-3 border-b border-[var(--border)] px-5 py-4 lg:flex-row lg:items-center lg:justify-between">
              <FilterTabs value={actRange} onChange={(k) => { setActRange(k as Exclude<RangeKey, "custom">); setActPage(0); }}
                options={(["today", "7d", "month", "lastMonth"] as const).map((key) => ({ key, label: RANGE_LABEL[key] }))} />
              <span className="text-sm font-medium text-[var(--fg-muted)]">{describeRange(actDates)}</span>
            </div>
            {/* Opening + in − out = closing, always (computed from recorded balances). */}
            <dl className="grid grid-cols-2 divide-[var(--border)] border-b border-[var(--border)] bg-[var(--subtle)] sm:grid-cols-4 sm:divide-x">
              {[
                { label: "Opening balance", value: act.data?.openingBalance, cls: "text-[var(--fg)]", sign: "" },
                { label: "Money in", value: act.data?.moneyIn, cls: "text-emerald-700 dark:text-emerald-400", sign: "+" },
                { label: "Money out", value: act.data?.moneyOut, cls: "text-[var(--fg)]", sign: "−" },
                { label: "Closing balance", value: act.data?.closingBalance, cls: "text-[var(--fg)]", sign: "" },
              ].map((m) => (
                <div key={m.label} className="px-5 py-3">
                  <dt className="text-xs font-medium text-[var(--fg-muted)]">{m.label}</dt>
                  <dd className={`num mt-0.5 text-base font-semibold ${m.cls}`}>
                    {act.data ? `${m.value ? m.sign : ""}${ugx(m.value)}` : <Skeleton className="h-5 w-24" />}
                  </dd>
                </div>
              ))}
            </dl>
            <Table
              bare
              searchable={false}
              paginated={false}
              loading={act.loading && !act.data}
              rows={act.data?.entries ?? []}
              rowKey={(e: LedgerEntry) => e.id}
              emptyTitle="No activity in this period"
              emptyDescription="Customer payments, withdrawals and fees show here as they happen."
              columns={[
                { label: "Date", render: (e: LedgerEntry) => <span className="whitespace-nowrap text-[var(--fg-muted)]">{date(e.createdAt)}</span> },
                { label: "Description", render: (e: LedgerEntry) => (
                  <span>
                    <span className="font-medium text-[var(--fg)]">{LEDGER[e.type]?.name ?? e.type.replaceAll("_", " ").toLowerCase()}</span>
                    {(LEDGER[e.type]?.hint || e.note) && <span className="block text-xs text-[var(--fg-faint)]">{LEDGER[e.type]?.hint ?? e.note}</span>}
                  </span>
                )},
                { label: "Amount", align: "right", render: (e: LedgerEntry) => {
                  const effect = LEDGER[e.type]?.effect ?? 0;
                  return (
                    <span className={`num whitespace-nowrap font-semibold ${effect > 0 ? "text-emerald-700 dark:text-emerald-400" : effect < 0 ? "text-[var(--fg)]" : "text-[var(--fg-faint)]"}`}>
                      {effect > 0 ? "+" : effect < 0 ? "−" : ""}{ugx(e.amount)}
                    </span>
                  );
                }},
                { label: "Balance", align: "right", hideBelow: "sm", render: (e: LedgerEntry) => <span className="num whitespace-nowrap text-[var(--fg-muted)]">{ugx(e.availableAfter)}</span> },
              ]}
            />
            {act.data && <Pager page={act.data.page} totalPages={act.data.totalPages} totalElements={act.data.totalElements} onPage={setActPage} busy={act.refreshing} />}
          </>
        )}
      </section>

      {open && (
        <Dialog title={step === "details" ? "Withdraw earnings" : "Verify withdrawal"}
          description={step === "details" ? "Enter your withdrawal details." : "Confirm it’s you before we send the money."}
          onClose={() => { if (!busy && !otpBusy) setOpen(false); }}>
          <form onSubmit={(e) => { e.preventDefault(); if (step === "details") void sendOtp(false); else if (otp.length === 6) void submit(otp); }}>
            <ol className="withdrawal-steps" aria-label={`Step ${step === "details" ? 1 : 2} of 2`}>
              <li className={`withdrawal-step ${step === "details" ? "withdrawal-step-active" : "withdrawal-step-done"}`} aria-current={step === "details" ? "step" : undefined}>
                <span>{step === "details" ? "1" : "✓"}</span> Details
              </li>
              <li className="withdrawal-step-line" aria-hidden="true" />
              <li className={`withdrawal-step ${step === "verify" ? "withdrawal-step-active" : ""}`} aria-current={step === "verify" ? "step" : undefined}>
                <span>2</span> Verify
              </li>
            </ol>
            {step === "details" && formError && <div className="mt-4" role="alert"><ErrorNotice text={formError} /></div>}
            {step === "details" && <div className="withdrawal-step-content">
            <div className="mt-4 grid grid-cols-2 gap-3">
              <div className="rounded-xl bg-[var(--subtle)] px-4 py-3">
                <p className="text-[11px] font-semibold uppercase tracking-wider text-[var(--fg-faint)]">Available balance</p>
                <p className="mt-0.5 text-lg font-bold text-[var(--fg)]">{ugx(summary?.available)}</p>
              </div>
              <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3">
                <p className="text-[11px] font-semibold uppercase tracking-wider text-emerald-800">Available to withdraw</p>
                <p className="mt-0.5 text-lg font-bold text-emerald-800">{ugx(maxAmount)}</p>
                {canWithdraw && <button type="button" className="mt-0.5 text-xs font-semibold text-emerald-800 underline" onClick={() => setAmount(String(maxAmount))}>Use maximum</button>}
              </div>
            </div>
            <p className="mt-2 text-xs text-[var(--fg-muted)]">
              Fee reserve: up to {feePercent}% (minimum {ugx(feeMin)}). Unused funds return to your wallet.
            </p>

            <div className="mt-5">
              <label className={label} htmlFor="withdraw-amount">Amount (UGX)</label>
              <input id="withdraw-amount" className={field} type="number" required min={summary?.limits.min} max={maxAmount}
                value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="e.g. 50000"
                aria-invalid={overBalance || underMinimum} aria-describedby="withdraw-amount-help" />
              {overBalance ? (
                <p id="withdraw-amount-help" role="alert" className="mt-1.5 text-xs text-red-700">
                  Amount and fee reserve exceed your balance. Maximum: {ugx(maxAmount)}.
                </p>
              ) : underMinimum ? (
                <p id="withdraw-amount-help" role="alert" className="mt-1.5 text-xs text-red-700">The minimum withdrawal is {ugx(summary?.limits.min)}.</p>
              ) : (
                <p id="withdraw-amount-help" className={hint}>Between {ugx(summary?.limits.min)} and {ugx(maxAmount)}.</p>
              )}
            </div>
            <div className="mt-4">
              <label className={label} htmlFor="withdraw-phone">Mobile money number</label>
              <input id="withdraw-phone" className={field} type="tel" required value={phone} onChange={(e) => setPhone(e.target.value)}
                placeholder="07XX XXX XXX" />
            </div>
            <div className="mt-4">
              <label className={label} htmlFor="withdraw-reason">Reason</label>
              <input id="withdraw-reason" className={field} type="text" required minLength={3} maxLength={255} value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="e.g. Business expenses" />
            </div>
            {requested >= (summary?.limits.min ?? Infinity) && !overBalance && (
              <dl className="mt-4 space-y-1.5 rounded-xl border border-[var(--border-soft)] bg-[var(--subtle)] px-4 py-3 text-xs">
                <div className="flex justify-between"><dt className="font-semibold text-[var(--fg)]">Sent to your mobile money</dt><dd className="font-bold text-emerald-700">{ugx(requested)}</dd></div>
                <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">Held for the gateway fee</dt><dd className="text-[var(--fg-muted)]">up to {ugx(requestedFee)}</dd></div>
                <div className="flex justify-between"><dt className="text-[var(--fg-muted)]">Taken from your wallet now</dt><dd className="text-[var(--fg-muted)]">{ugx(requested + requestedFee)}</dd></div>
                <div className="flex justify-between border-t border-[var(--border-soft)] pt-1.5"><dt className="text-[var(--fg-muted)]">Left in your wallet</dt><dd className="font-semibold text-[var(--fg)]">{ugx(available - requested - requestedFee)}</dd></div>
              </dl>
            )}
            </div>}
            {step === "verify" && <div className="withdrawal-step-content">
              <div className="flex flex-col items-center text-center">
                <span className="grid h-12 w-12 place-items-center rounded-full bg-blue-50 text-blue-600 ring-8 ring-blue-50/50 dark:bg-blue-500/10 dark:text-blue-300 dark:ring-blue-500/5">
                  <EnvelopeSimple size={22} />
                </span>
                <h3 className="mt-4 text-base font-bold text-[var(--fg)]">Check your email</h3>
                <p className="mt-1 max-w-xs text-sm text-[var(--fg-muted)]">
                  Enter the 6-digit code we sent to {sentTo ? <strong className="font-semibold text-[var(--fg)]">{sentTo}</strong> : "your account email"}.
                </p>
              </div>

              <div className="mt-5">
                <CodeBoxes value={otp} onChange={onCodeChange} disabled={busy} invalid={!!formError} />
                <div className="mt-3 min-h-5 text-center text-xs" aria-live="polite">
                  {busy ? <span className="text-[var(--fg-muted)]">Verifying…</span>
                    : formError ? <span role="alert" className="font-medium text-red-600">{formError}</span>
                    : otpNotice ? <span className="text-[var(--fg-muted)]">{otpNotice}</span>
                    : <span className="text-[var(--fg-faint)]">The code expires in 10 minutes. Not in your inbox? Check spam.</span>}
                </div>
                <p className="mt-2 text-center text-xs text-[var(--fg-muted)]">
                  Didn’t get it?{" "}
                  {cooldown > 0
                    ? <span className="tabular-nums text-[var(--fg-faint)]">Resend in {Math.floor(cooldown / 60)}:{String(cooldown % 60).padStart(2, "0")}</span>
                    : <button type="button" className="font-semibold text-blue-600 hover:underline disabled:opacity-50 dark:text-blue-400"
                        disabled={otpBusy || busy} onClick={() => void sendOtp(true)}>
                        {otpBusy ? "Sending…" : "Resend code"}
                      </button>}
                </p>
              </div>

              <div className="mt-6 overflow-hidden rounded-xl border border-[var(--border-soft)]">
                <div className="flex items-center justify-between bg-[var(--subtle)] px-4 py-2.5">
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-[var(--fg-faint)]">Withdrawal summary</span>
                  <button type="button" className="text-xs font-semibold text-blue-600 hover:underline disabled:opacity-50 dark:text-blue-400" disabled={busy} onClick={goBack}>Edit</button>
                </div>
                <dl className="divide-y divide-[var(--border-soft)] px-4 text-sm">
                  <div className="flex justify-between gap-4 py-2.5"><dt className="text-[var(--fg-muted)]">You receive</dt><dd className="font-bold text-emerald-700 dark:text-emerald-400">{ugx(requested)}</dd></div>
                  <div className="flex justify-between gap-4 py-2.5"><dt className="text-[var(--fg-muted)]">Mobile money</dt><dd className="font-mono font-semibold text-[var(--fg)]">{phone}</dd></div>
                  <div className="flex justify-between gap-4 py-2.5"><dt className="text-[var(--fg-muted)]">Reason</dt><dd className="truncate font-medium text-[var(--fg)]">{reason.trim()}</dd></div>
                  <div className="flex justify-between gap-4 py-2.5"><dt className="text-[var(--fg-muted)]">Held for gateway fee</dt><dd className="text-[var(--fg)]">up to {ugx(requestedFee)}</dd></div>
                  <div className="flex justify-between gap-4 py-2.5"><dt className="font-semibold text-[var(--fg)]">Taken from wallet</dt><dd className="font-semibold text-[var(--fg)]">{ugx(requested + requestedFee)}</dd></div>
                </dl>
              </div>
              <p className="mt-3 flex items-start gap-2 text-xs text-[var(--fg-faint)]">
                <ShieldCheck size={14} className="mt-px shrink-0" />
                AirPairy will never ask you for this code by phone or message. Only enter it here.
              </p>
            </div>}
            {summary?.limits.approvalRequired && (
              <p className="mt-4 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-700">
                Withdrawals are reviewed by the platform before funds are sent.
              </p>
            )}
            <div className="mt-6 flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
              <button type="button" className={buttonGhost} disabled={busy || otpBusy} onClick={() => step === "verify" ? goBack() : setOpen(false)}>{step === "verify" ? "Back" : "Cancel"}</button>
              {step === "details" ? (
                <button type="submit" className={button} disabled={otpBusy || overBalance || underMinimum || requested <= 0 || reason.trim().length < 3}>
                  <BusyLabel busy={otpBusy} label="Sending code…">{otpSent ? "Continue" : "Send verification code"}</BusyLabel>
                </button>
              ) : (
                <button type="submit" className={button} disabled={busy || otpBusy || otp.length !== 6}>
                  <BusyLabel busy={busy} label="Confirming…">Confirm withdrawal</BusyLabel>
                </button>
              )}
            </div>
          </form>
        </Dialog>
      )}
    </div>
  );
}
