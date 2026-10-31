"use client";

import React from "react";
import { flushSync } from "react-dom";
import { Sun, Moon, Desktop } from "@/components/icons";
import { useTheme, type ThemePreference } from "@/context/ThemeContext";

const OPTIONS: { value: ThemePreference; label: string; Icon: React.ElementType }[] = [
  { value: "light", label: "Light", Icon: Sun },
  { value: "system", label: "System", Icon: Desktop },
  { value: "dark", label: "Dark", Icon: Moon },
];

/**
 * Three explicit choices rather than one cycling button: the current mode is
 * always visible, and "System" stays reachable instead of being a hidden state.
 * Selection is carried by icon + aria-checked, never by colour alone.
 *
 * Choosing a theme reveals it in a circle from the button that was pressed,
 * where the browser supports view transitions and motion isn't reduced.
 */
export default function ThemeToggle({ compact = false }: { compact?: boolean }) {
  const { preference, setPreference } = useTheme();
  const index = OPTIONS.findIndex((o) => o.value === preference);
  const size = compact ? "h-8 w-8 max-sm:h-11 max-sm:w-11" : "h-11 w-11";

  const choose = (value: ThemePreference, event: React.MouseEvent<HTMLButtonElement>) => {
    if (value === preference) return;
    const animate = "startViewTransition" in document && !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (!animate) { setPreference(value); return; }
    const { left, top, width, height } = event.currentTarget.getBoundingClientRect();
    const x = left + width / 2;
    const y = top + height / 2;
    const radius = Math.hypot(Math.max(x, window.innerWidth - x), Math.max(y, window.innerHeight - y));
    const transition = document.startViewTransition(() => flushSync(() => setPreference(value)));
    transition.ready.then(() => {
      document.documentElement.animate(
        { clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${radius}px at ${x}px ${y}px)`] },
        { duration: 480, easing: "cubic-bezier(0.16, 1, 0.3, 1)", pseudoElement: "::view-transition-new(root)" },
      );
    }).catch(() => { /* skipped by the browser: the theme has still changed */ });
  };

  return (
    <div
      role="radiogroup"
      aria-label="Colour theme"
      className="relative inline-grid grid-cols-3 rounded-xl border border-[var(--border)] bg-[var(--subtle)] p-0.5"
    >
      <span
        aria-hidden
        className="absolute bottom-0.5 left-0.5 top-0.5 w-[calc((100%-0.25rem)/3)] rounded-[10px] bg-[var(--surface)] shadow-sm ring-1 ring-[var(--border)] transition-transform duration-300 ease-[var(--ease-spring)]"
        style={{ transform: `translateX(${Math.max(index, 0) * 100}%)` }}
      />
      {OPTIONS.map(({ value, label, Icon }) => {
        const active = preference === value;
        return (
          <button
            key={value}
            type="button"
            role="radio"
            aria-checked={active}
            aria-label={label}
            title={label}
            onClick={(event) => choose(value, event)}
            style={{ touchAction: "manipulation" }}
            className={`relative flex ${size} items-center justify-center rounded-[10px] transition-colors duration-200 ${
              active ? "text-[var(--accent-text)]" : "text-[var(--fg-muted)] hover:text-[var(--fg)]"
            }`}
          >
            <Icon size={compact ? 15 : 17} aria-hidden className={`transition-transform duration-300 ease-[var(--ease-spring)] ${active ? "scale-110" : "scale-100"}`} />
          </button>
        );
      })}
    </div>
  );
}
