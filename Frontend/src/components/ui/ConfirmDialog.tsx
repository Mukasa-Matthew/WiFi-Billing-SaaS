"use client";
import React, { useEffect, useRef, useState } from "react";
import { Warning } from "@/components/icons";
import { buttonGhost } from "@/components/BillingUI";

export interface ConfirmRequest {
  title: string;
  body: string;
  confirmLabel: string;
  onConfirm: () => Promise<void>;
}

export function useConfirmDialog() {
  const [req, setReq] = useState<ConfirmRequest | null>(null);
  const confirm = (r: ConfirmRequest) => setReq(r);
  const dialog = req ? <ConfirmDialog req={req} onClose={() => setReq(null)} /> : null;
  return { confirm, dialog };
}

function ConfirmDialog({ req, onClose }: { req: ConfirmRequest; onClose: () => void }) {
  const [busy, setBusy] = useState(false);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const previousFocus = document.activeElement as HTMLElement | null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    cancelRef.current?.focus();
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !busy) onClose();
      if (e.key !== "Tab" || !dialogRef.current) return;
      const focusable = Array.from(dialogRef.current.querySelectorAll<HTMLElement>('button:not([disabled]), [tabindex]:not([tabindex="-1"])'));
      if (!focusable.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    };
    document.addEventListener("keydown", key);
    return () => {
      document.removeEventListener("keydown", key);
      document.body.style.overflow = previousOverflow;
      previousFocus?.focus();
    };
  }, [busy, onClose]);

  const run = async () => {
    setBusy(true);
    try { await req.onConfirm(); onClose(); }
    finally { setBusy(false); }
  };

  return (
    <div className="fixed inset-0 z-[85] flex items-center justify-center p-4" role="presentation">
      <div className="absolute inset-0 bg-slate-950/55 backdrop-blur-[2px]" onClick={() => !busy && onClose()} />
      <div
        ref={dialogRef}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="confirm-title"
        aria-describedby="confirm-body"
        className="dialog-enter relative max-h-[calc(100dvh-2rem)] w-full max-w-sm overflow-y-auto rounded-xl border border-[var(--border)] bg-[var(--surface)] p-5 shadow-[var(--shadow-overlay)] sm:p-6"
      >
        <div className="flex items-start gap-3">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-red-50 text-red-600">
            <Warning className="h-4.5 w-4.5" />
          </div>
          <div>
            <h2 id="confirm-title" className="text-sm font-semibold text-[var(--fg)]">{req.title}</h2>
            <p id="confirm-body" className="mt-1 text-xs leading-5 text-[var(--fg-muted)]">{req.body}</p>
          </div>
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <button ref={cancelRef} onClick={onClose} disabled={busy} className={buttonGhost}>Cancel</button>
          <button onClick={run} disabled={busy}
            className="inline-flex min-h-10 items-center gap-1.5 rounded-lg bg-red-600 px-4 py-2.5 text-xs font-semibold text-white transition hover:bg-red-500 active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-50 disabled:active:scale-100">
            {busy ? `${req.confirmLabel}…` : req.confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
