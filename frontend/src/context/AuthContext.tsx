import React, { createContext, useContext } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiRequest, SessionUser } from '../lib/api';

interface AuthContextValue {
  user: SessionUser | null;
  isLoading: boolean;
  login: (email: string, password: string) => Promise<SessionUser>;
  logout: () => Promise<void>;
  refreshSession: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const queryClient = useQueryClient();

  const { data: user, isLoading } = useQuery<SessionUser | null>({
    queryKey: ['auth', 'me'],
    queryFn: async () => {
      try {
        return await apiRequest<SessionUser>('/api/v1/auth/me');
      } catch {
        return null;
      }
    },
    retry: false,
    staleTime: 60_000,
  });

  const login = async (email: string, password: string): Promise<SessionUser> => {
    const loggedInUser = await apiRequest<SessionUser>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    });
    queryClient.setQueryData(['auth', 'me'], loggedInUser);
    return loggedInUser;
  };

  const logout = async (): Promise<void> => {
    await apiRequest('/api/v1/auth/logout', { method: 'POST' });
    queryClient.setQueryData(['auth', 'me'], null);
    queryClient.clear();
  };

  const refreshSession = async (): Promise<void> => {
    await queryClient.invalidateQueries({ queryKey: ['auth', 'me'] });
  };

  return (
    <AuthContext.Provider value={{ user: user ?? null, isLoading, login, logout, refreshSession }}>
      {children}
    </AuthContext.Provider>
  );
};

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return ctx;
}
