"use client";
import React, { useEffect, useRef } from "react";
import { X } from "lucide-react";

export function Dialog({ title, description, children, onClose, wide = false }: {
  title: string; description?: string; children: React.ReactNode; onClose: () => void; wide?: boolean;
}) {
  const closeRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    closeRef.current?.focus();
    const key = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", key);
    return () => document.removeEventListener("keydown", key);
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-[80] flex items-center justify-center p-4" role="presentation">
      <div className="absolute inset-0 bg-slate-900/50" onClick={onClose} />
      <div role="dialog" aria-modal="true" aria-label={title}
        className={`relative w-full ${wide ? "max-w-2xl" : "max-w-md"} rounded-xl border border-slate-200 bg-white p-6 shadow-lg`}>
        <div className="mb-5 flex items-start justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold text-slate-900">{title}</h2>
            {description && <p className="mt-1 text-xs text-slate-500">{description}</p>}
          </div>
          <button ref={closeRef} aria-label="Close dialog" onClick={onClose}
            className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700">
            <X className="h-4 w-4" />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}
