"use client";

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

  const login = (data: UserSession) => {
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
