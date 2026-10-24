"use client";
import React, { createContext, useCallback, useContext, useRef, useState } from "react";
import { CheckCircle, Warning, XCircle, X } from "@/components/icons";

type ToastKind = "success" | "error" | "info";
interface Toast { id: number; kind: ToastKind; text: string }

const ToastContext = createContext<(kind: ToastKind, text: string) => void>(() => {});
export const useToast = () => useContext(ToastContext);

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(1);

  const push = useCallback((kind: ToastKind, text: string) => {
    const id = nextId.current++;
    setToasts((t) => [...t.slice(-4), { id, kind, text }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), kind === "error" ? 6000 : 4000);
  }, []);

  const icons = {
    success: <CheckCircle className="h-4 w-4 shrink-0 text-emerald-600" />,
    error: <XCircle className="h-4 w-4 shrink-0 text-red-600" />,
    info: <Warning className="h-4 w-4 shrink-0 text-amber-600" />,
  };

  return (
    <ToastContext.Provider value={push}>
      {children}
      <div aria-live="polite" aria-atomic="false" className="pointer-events-none fixed inset-x-4 bottom-4 z-[90] flex flex-col gap-2 sm:left-auto sm:w-80">
        {toasts.map((t) => (
          <div key={t.id} role={t.kind === "error" ? "alert" : "status"}
            className="toast-enter pointer-events-auto flex items-start gap-2.5 rounded-lg border border-[var(--border)] bg-[var(--surface)] px-4 py-3 shadow-[var(--shadow-overlay)]">
            {icons[t.kind]}
            <p className="flex-1 text-xs font-medium leading-5 text-[var(--fg)]">{t.text}</p>
            <button aria-label="Dismiss" onClick={() => setToasts((x) => x.filter((y) => y.id !== t.id))}
              className="-m-2 flex min-h-10 min-w-10 items-center justify-center rounded-lg text-[var(--fg-faint)] transition hover:bg-[var(--fill)] hover:text-[var(--fg-body)]">
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
