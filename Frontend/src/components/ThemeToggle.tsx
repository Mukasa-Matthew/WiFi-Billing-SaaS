"use client";

import React from "react";
import { Sun, Moon, Desktop } from "@phosphor-icons/react";
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
 */
export default function ThemeToggle() {
  const { preference, setPreference } = useTheme();

  return (
    <div
      role="radiogroup"
      aria-label="Colour theme"
      className="inline-flex items-center gap-0.5 rounded-xl border border-[var(--border)] bg-[var(--subtle)] p-1"
    >
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
            onClick={() => setPreference(value)}
            style={{ touchAction: "manipulation" }}
            className={`flex h-11 w-11 items-center justify-center rounded-lg transition-colors duration-200 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600 ${
              active
                ? "bg-[var(--surface)] text-[var(--fg)] shadow-sm"
                : "text-[var(--fg-muted)] hover:text-[var(--fg)]"
            }`}
          >
            <Icon size={17} weight={active ? "fill" : "regular"} aria-hidden />
          </button>
        );
      })}
    </div>
  );
}
