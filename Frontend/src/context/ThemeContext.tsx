"use client";

import React, { createContext, useCallback, useContext, useEffect, useState } from "react";

export type ThemePreference = "light" | "dark" | "system";
type Resolved = "light" | "dark";

const STORAGE_KEY = "wb_theme";

/** Runs before paint so a dark-mode visitor never sees a white flash. Kept in
 *  sync with the reader below; both agree on the storage key and attribute. */
export const themeBootstrapScript = `(function(){try{
var p=localStorage.getItem('${STORAGE_KEY}')||'system';
var d=p==='dark'||(p==='system'&&matchMedia('(prefers-color-scheme: dark)').matches);
document.documentElement.setAttribute('data-theme',d?'dark':'light');
}catch(e){document.documentElement.setAttribute('data-theme','light');}})();`;

interface ThemeContextValue {
  preference: ThemePreference;
  resolved: Resolved;
  setPreference: (p: ThemePreference) => void;
}

const ThemeContext = createContext<ThemeContextValue>({
  preference: "system", resolved: "light", setPreference: () => {},
});

function systemIsDark() {
  return typeof window !== "undefined" && window.matchMedia("(prefers-color-scheme: dark)").matches;
}

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  /* Starts at "system" on both server and client so hydration matches; the
     bootstrap script has already painted the right theme by this point. */
  const [preference, setPref] = useState<ThemePreference>("system");
  const [resolved, setResolved] = useState<Resolved>("light");

  const apply = useCallback((pref: ThemePreference) => {
    const dark = pref === "dark" || (pref === "system" && systemIsDark());
    const next: Resolved = dark ? "dark" : "light";
    document.documentElement.setAttribute("data-theme", next);
    setResolved(next);
  }, []);

  useEffect(() => {
    let stored: ThemePreference = "system";
    try { stored = (localStorage.getItem(STORAGE_KEY) as ThemePreference) || "system"; } catch { /* private mode */ }
    setPref(stored);
    apply(stored);
  }, [apply]);

  /* Follow the OS while the preference is "system". */
  useEffect(() => {
    if (preference !== "system") return;
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => apply("system");
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, [preference, apply]);

  const setPreference = useCallback((p: ThemePreference) => {
    setPref(p);
    apply(p);
    try { localStorage.setItem(STORAGE_KEY, p); } catch { /* private mode */ }
  }, [apply]);

  return (
    <ThemeContext.Provider value={{ preference, resolved, setPreference }}>
      {children}
    </ThemeContext.Provider>
  );
}

export const useTheme = () => useContext(ThemeContext);
