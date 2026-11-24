/* Revenue visibility is a per-device preference, so it lives in localStorage. */
const HIDE_KEY = "wb_hide_revenue";

export const hideStore = {
  subscribe(cb: () => void) {
    window.addEventListener("storage", cb);
    window.addEventListener(HIDE_KEY, cb);
    return () => { window.removeEventListener("storage", cb); window.removeEventListener(HIDE_KEY, cb); };
  },
  get() { try { return localStorage.getItem(HIDE_KEY) === "1"; } catch { return false; } },
  set(v: boolean) {
    try { localStorage.setItem(HIDE_KEY, v ? "1" : "0"); } catch { /* unavailable */ }
    window.dispatchEvent(new Event(HIDE_KEY));
  },
};
