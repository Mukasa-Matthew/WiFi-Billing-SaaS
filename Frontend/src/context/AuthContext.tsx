"use client";
import { resetQueryCache } from "@/lib/queryClient";

import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";

export interface UserSession {
  token: string;
  tenantId: number;
  name: string;
  email: string;
  role: "ROLE_SUPERADMIN" | "ROLE_ADMIN" | string;
  mustChangePassword: boolean;
}

interface AuthContextType {
  user: UserSession | null;
  loading: boolean;
  login: (data: UserSession) => void;
  markPasswordChanged: () => void;
  logout: (reason?: "idle") => void;
}

const IDLE_LIMIT_MS = 30 * 60 * 1000;
const ACTIVITY_KEY = "wb_last_activity";
const ACTIVITY_EVENTS = ["mousemove", "mousedown", "keydown", "scroll", "touchstart", "wheel"] as const;

const AuthContext = createContext<AuthContextType>({
  user: null,
  loading: true,
  login: () => {},
  markPasswordChanged: () => {},
  logout: () => {},
});

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const [user, setUser] = useState<UserSession | null>(null);
  const [loading, setLoading] = useState(true);
  const lastWriteRef = useRef(0);
  const router = useRouter();

  useEffect(() => {
    const id = window.setTimeout(() => {
      try {
        const rawUser = localStorage.getItem("wb_user");
        const token = localStorage.getItem("wb_token");
        if (rawUser && token) {
          setUser(JSON.parse(rawUser));
        }
      } catch {
        localStorage.removeItem("wb_user");
        localStorage.removeItem("wb_token");
      } finally {
        setLoading(false);
      }
    }, 0);
    return () => window.clearTimeout(id);
  }, []);

  // Requests read the token from localStorage while this tab remembers the
  // user in memory. If another tab signs out or signs in as someone else,
  // drop this tab's cached data and reload so both agree again. (The storage
  // event only fires in other tabs, so this never loops.)
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key !== null && event.key !== "wb_token" && event.key !== "wb_user") return;
      if (event.key === "wb_user" && event.newValue === event.oldValue) return;
      resetQueryCache();
      window.location.reload();
    };
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);

  const login = (data: UserSession) => {
    resetQueryCache();
    localStorage.removeItem("wb_impersonation");
    localStorage.setItem("wb_token", data.token);
    localStorage.setItem("wb_user", JSON.stringify(data));
    localStorage.setItem(ACTIVITY_KEY, String(Date.now()));
    setUser(data);

    if (data.mustChangePassword) {
      router.push("/change-password");
    } else if (data.role === "ROLE_SUPERADMIN") {
      router.push("/admin");
    } else {
      router.push("/dashboard");
    }
  };

  const markPasswordChanged = () => {
    if (!user) return;
    const updated = { ...user, mustChangePassword: false };
    localStorage.setItem("wb_user", JSON.stringify(updated));
    setUser(updated);
    router.replace(updated.role === "ROLE_SUPERADMIN" ? "/admin" : "/dashboard");
  };

  const logout = useCallback((reason?: "idle") => {
    resetQueryCache();
    localStorage.removeItem("wb_impersonation");
    localStorage.removeItem("wb_token");
    localStorage.removeItem("wb_user");
    localStorage.removeItem(ACTIVITY_KEY);
    setUser(null);
    router.push(reason === "idle" ? "/login?reason=idle" : "/login");
  }, [router]);

  // Idle timeout: any interaction bumps a shared timestamp in localStorage;
  // 30 minutes without one signs the user out. The timestamp is shared so
  // activity in any open tab counts, and the storage listener above makes
  // the sign-out reach every other tab too.
  useEffect(() => {
    if (!user) return;

    const record = () => {
      const now = Date.now();
      if (now - lastWriteRef.current < 10_000) return;
      lastWriteRef.current = now;
      localStorage.setItem(ACTIVITY_KEY, String(now));
    };

    const checkIdle = () => {
      const raw = localStorage.getItem(ACTIVITY_KEY);
      const last = raw === null ? Date.now() : Number(raw);
      if (Date.now() - last >= IDLE_LIMIT_MS) logout("idle");
    };

    if (localStorage.getItem(ACTIVITY_KEY) === null) {
      localStorage.setItem(ACTIVITY_KEY, String(Date.now()));
    }
    for (const event of ACTIVITY_EVENTS) {
      window.addEventListener(event, record, { passive: true });
    }
    const timer = window.setInterval(checkIdle, 15_000);
    window.addEventListener("focus", checkIdle);
    document.addEventListener("visibilitychange", checkIdle);
    checkIdle();
    return () => {
      for (const event of ACTIVITY_EVENTS) window.removeEventListener(event, record);
      window.clearInterval(timer);
      window.removeEventListener("focus", checkIdle);
      document.removeEventListener("visibilitychange", checkIdle);
    };
  }, [user, logout]);

  return (
    <AuthContext.Provider value={{ user, loading, login, markPasswordChanged, logout }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => useContext(AuthContext);
