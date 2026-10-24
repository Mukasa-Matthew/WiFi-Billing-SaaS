"use client";
import React, { createContext, useCallback, useContext, useRef, useState } from "react";
import { CheckCircle, Warning, XCircle, X } from "@phosphor-icons/react";

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
      <div aria-live="polite" className="pointer-events-none fixed bottom-4 right-4 z-[90] flex w-80 flex-col gap-2">
        {toasts.map((t) => (
          <div key={t.id} role="status"
            className="pointer-events-auto flex items-start gap-2.5 rounded-lg border border-slate-200 bg-white px-4 py-3 shadow-md">
            {icons[t.kind]}
            <p className="flex-1 text-xs font-medium leading-5 text-slate-800">{t.text}</p>
            <button aria-label="Dismiss" onClick={() => setToasts((x) => x.filter((y) => y.id !== t.id))}
              className="text-slate-400 hover:text-slate-700">
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
