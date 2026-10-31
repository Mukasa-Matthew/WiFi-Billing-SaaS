"use client";
import React from "react";
import { Toaster, toast as sonner } from "sonner";
import { CheckCircle, CircleNotch, Info, Warning, XCircle } from "@/components/icons";
import { message } from "@/components/BillingUI";
import { useTheme } from "@/context/ThemeContext";

/**
 * App-wide notifications, backed by Sonner.
 *
 * `useToast()(kind, text)` is the original API and every existing call site
 * keeps working. The same kind + text is shown once (Sonner updates the toast
 * with a matching id instead of stacking a duplicate). `notify.promise` shows
 * a loading toast that only turns into success once the request resolves.
 */
type ToastKind = "success" | "error" | "warning" | "info";

const DURATION: Record<ToastKind, number> = { success: 4000, info: 4000, warning: 6000, error: 6000 };

function show(kind: ToastKind, text: string) {
  sonner[kind](text, { id: `${kind}:${text}`, duration: DURATION[kind] });
}

export const notify = {
  success: (text: string) => show("success", text),
  error: (text: string) => show("error", text),
  warning: (text: string) => show("warning", text),
  info: (text: string) => show("info", text),
  /**
   * Loading → success/error for one request. Errors default to the
   * user-facing text from message(); the original promise is returned so
   * callers can still await it and handle failures.
   */
  promise<T>(promise: Promise<T>, text: { loading: string; success: string | ((value: T) => string); error?: string | ((error: unknown) => string) }) {
    sonner.promise(promise, {
      loading: text.loading,
      success: text.success,
      error: (err: unknown) => (typeof text.error === "function" ? text.error(err) : text.error ?? message(err)),
    });
    return promise;
  },
};

export const useToast = () => show;

const icons = {
  success: <CheckCircle className="h-4 w-4 text-emerald-600" />,
  error: <XCircle className="h-4 w-4 text-red-600" />,
  warning: <Warning className="h-4 w-4 text-amber-600" />,
  info: <Info className="h-4 w-4 text-blue-600" />,
  loading: <CircleNotch className="h-4 w-4 text-[var(--fg-muted)] motion-reduce:animate-none" />,
};

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const { resolved } = useTheme();
  return (
    <>
      {children}
      <Toaster
        position="bottom-right"
        theme={resolved}
        closeButton
        visibleToasts={4}
        icons={icons}
        style={{
          fontFamily: "inherit",
          "--normal-bg": "var(--surface)",
          "--normal-border": "var(--border)",
          "--normal-text": "var(--fg)",
          "--border-radius": "0.5rem",
        } as React.CSSProperties}
        toastOptions={{ classNames: { toast: "shadow-[var(--shadow-overlay)]!", title: "text-xs! font-medium! leading-5!", closeButton: "bg-[var(--surface)]! border-[var(--border)]! text-[var(--fg-muted)]!" } }}
      />
    </>
  );
}
