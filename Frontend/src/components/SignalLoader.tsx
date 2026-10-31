import React from "react";

/**
 * AirPairy's loading mark: the logo "connecting". The device dot wakes, then
 * each signal arc draws outward and fades, like a device finding a network.
 * Under reduced motion the mark simply stays complete.
 */
export function SignalMark({ className = "h-4 w-4" }: { className?: string }) {
  return (
    <svg viewBox="0 0 64 64" fill="none" className={`shrink-0 ${className}`} aria-hidden>
      <path className="signal-arc signal-arc-outer" pathLength={1} d="M10 52C14 33 21 20 32 10c11 10 18 23 22 42" stroke="currentColor" strokeWidth="8" strokeLinecap="round" strokeLinejoin="round" />
      <path className="signal-arc signal-arc-inner" pathLength={1} d="M21.5 41c6.5-6 14.5-6 21 0" stroke="currentColor" strokeWidth="7" strokeLinecap="round" />
      <circle className="signal-dot" cx="32" cy="51" r="5.5" fill="currentColor" />
    </svg>
  );
}

/** Drop-in for the old spinner icon: accepts the same `size` and `className`. */
export function SignalSpinner({ size, className = "", style, ...rest }: { size?: number; className?: string; style?: React.CSSProperties; "aria-hidden"?: boolean | "true" | "false" }) {
  return <span {...rest} className={`inline-flex shrink-0 ${className}`} style={size ? { width: size, height: size, ...style } : style}><SignalMark className="h-full w-full" /></span>;
}

/** Full-area loader for route changes and first paint. */
export function SignalLoader({ label = "Connecting…", className = "" }: { label?: string; className?: string }) {
  return (
    <div role="status" aria-live="polite" className={`flex flex-col items-center gap-3 text-[var(--fg-muted)] ${className}`}>
      <span className="relative flex h-16 w-16 items-center justify-center text-[var(--accent-text)]">
        <span aria-hidden className="signal-halo absolute inset-0 rounded-full border border-current" />
        <span aria-hidden className="signal-halo signal-halo-late absolute inset-0 rounded-full border border-current" />
        <SignalMark className="h-9 w-9" />
      </span>
      <p className="text-sm font-medium">{label}</p>
    </div>
  );
}
