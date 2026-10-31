import React from "react";

/**
 * The AirPairy mark: a signal arch and a second, inner arc form an "A", with a
 * live dot for the device that pairs under them. It draws in currentColor so it
 * sits on any surface; only the dot keeps the brand signal colour.
 */
export function LogoMark({ className = "h-6 w-6", dot = "var(--sidebar-indicator)" }: { className?: string; dot?: string }) {
  return (
    <svg viewBox="0 0 64 64" fill="none" className={className} aria-hidden>
      <path d="M10 52C14 33 21 20 32 10c11 10 18 23 22 42" stroke="currentColor" strokeWidth="7.5" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M21.5 41c6.5-6 14.5-6 21 0" stroke="currentColor" strokeWidth="6.5" strokeLinecap="round" />
      <circle cx="32" cy="50" r="5" fill={dot} />
    </svg>
  );
}

/** The mark on its ink tile, for sidebars and sign-in screens. */
export function LogoTile({ size = "md" }: { size?: "sm" | "md" | "lg" }) {
  const box = size === "lg" ? "h-14 w-14 rounded-2xl" : size === "md" ? "h-11 w-11 rounded-xl" : "h-9 w-9 rounded-[10px]";
  const mark = size === "lg" ? "h-9 w-9" : size === "md" ? "h-7 w-7" : "h-[22px] w-[22px]";
  return (
    <span className={`inline-flex shrink-0 items-center justify-center bg-[#08282E] text-white ring-1 ring-inset ring-white/10 ${box}`}>
      <LogoMark className={mark} />
    </span>
  );
}

/** Tile plus wordmark. `inherit` lets the wordmark take its surface's colour. */
export function Logo({ size = "md", className = "" }: { size?: "sm" | "md" | "lg"; className?: string }) {
  const text = size === "lg" ? "text-[1.75rem]" : size === "md" ? "text-xl" : "text-base";
  return (
    <span className={`inline-flex items-center gap-2.5 ${className}`}>
      <LogoTile size={size} />
      <span className={`font-display font-bold leading-none tracking-[-0.03em] ${text}`}>AirPairy</span>
    </span>
  );
}
