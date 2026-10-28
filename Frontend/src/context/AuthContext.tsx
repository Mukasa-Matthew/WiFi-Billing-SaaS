"use client";
import { resetQueryCache } from "@/lib/queryClient";

import React, { createContext, useContext, useEffect, useState } from "react";
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
  logout: () => void;
}

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
  const router = useRouter();

  useEffect(() => {
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
    localStorage.setItem("wb_token", data.token);
    localStorage.setItem("wb_user", JSON.stringify(data));
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

  const logout = () => {
    resetQueryCache();
    localStorage.removeItem("wb_token");
    localStorage.removeItem("wb_user");
    setUser(null);
    router.push("/login");
  };

  return (
    <AuthContext.Provider value={{ user, loading, login, markPasswordChanged, logout }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => useContext(AuthContext);
