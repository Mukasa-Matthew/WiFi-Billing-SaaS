"use client";
import React, { useEffect, useId, useRef } from "react";
import { createPortal } from "react-dom";
import { X } from "@/components/icons";

export function Dialog({ title, description, children, onClose, wide = false }: {
  title: string; description?: string; children: React.ReactNode; onClose: () => void; wide?: boolean;
}) {
  const closeRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const descriptionId = useId();

  // Save the onClose callback in a ref to keep it stable across parent re-renders
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    const previousFocus = document.activeElement as HTMLElement | null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    closeRef.current?.focus();

    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape") onCloseRef.current();
      if (e.key !== "Tab" || !dialogRef.current) return;
      const focusable = Array.from(dialogRef.current.querySelectorAll<HTMLElement>('button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'));
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
  }, []); // Run only on mount and unmount

  if (typeof document === "undefined") return null;

  return createPortal(
    <div className="fixed inset-0 z-[80] flex items-end justify-center sm:items-center sm:p-4" role="presentation">
      <div className="absolute inset-0 bg-slate-950/55 backdrop-blur-[2px]" onClick={() => onCloseRef.current()} />
      <div ref={dialogRef} role="dialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={description ? descriptionId : undefined}
        className={`dialog-enter relative max-h-[92dvh] w-full overflow-y-auto ${wide ? "sm:max-w-2xl" : "sm:max-w-md"} rounded-t-2xl border border-[var(--border)] bg-[var(--surface)] p-5 pb-[max(1.25rem,env(safe-area-inset-bottom))] shadow-[var(--shadow-overlay)] sm:max-h-[calc(100dvh-2rem)] sm:rounded-xl sm:p-6`}>
        <div className="mb-5 flex items-start justify-between gap-3">
          <div>
            <h2 id={titleId} className="text-sm font-semibold text-[var(--fg)]">{title}</h2>
            {description && <p id={descriptionId} className="mt-1 text-xs text-[var(--fg-muted)]">{description}</p>}
          </div>
          <button ref={closeRef} aria-label="Close dialog" onClick={() => onCloseRef.current()}
            className="flex min-h-10 min-w-10 shrink-0 items-center justify-center rounded-lg p-1.5 max-sm:min-h-11 max-sm:min-w-11 text-[var(--fg-faint)] transition hover:bg-[var(--fill)] hover:text-[var(--fg-body)]">
            <X className="h-4 w-4" />
          </button>
        </div>
        {children}
      </div>
    </div>,
    document.body
  );
}
