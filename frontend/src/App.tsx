import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, useAuth } from './context/AuthContext';
import { LoginPage } from './pages/LoginPage';
import { SuperAdminDashboard } from './pages/SuperAdminDashboard';
import { BusinessAdminDashboard } from './pages/BusinessAdminDashboard';
import { CaptivePortalPage } from './pages/CaptivePortalPage';

const queryClient = new QueryClient();

const ProtectedRoute: React.FC<{
  children: React.ReactNode;
  requireSuperAdmin?: boolean;
}> = ({ children, requireSuperAdmin = false }) => {
  const { user, isLoading } = useAuth();

  if (isLoading) {
    return (
      <div className="min-h-screen bg-slate-950 text-slate-300 flex items-center justify-center text-sm">
        Verifying session...
      </div>
    );
  }

  if (!user) {
    return <Navigate to="/login" replace />;
  }

  if (requireSuperAdmin && !user.superAdmin) {
    return <Navigate to="/business" replace />;
  }

  return <>{children}</>;
};

const RootRedirect: React.FC = () => {
  const { user, isLoading } = useAuth();
  if (isLoading) {
    return null;
  }
  if (!user) {
    return <Navigate to="/login" replace />;
  }
  return <Navigate to={user.superAdmin ? '/super-admin' : '/business'} replace />;
};

export const App: React.FC = () => {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<RootRedirect />} />
            <Route path="/login" element={<LoginPage />} />
            <Route
              path="/super-admin"
              element={
                <ProtectedRoute requireSuperAdmin>
                  <SuperAdminDashboard />
                </ProtectedRoute>
              }
            />
            <Route
              path="/business"
              element={
                <ProtectedRoute>
                  <BusinessAdminDashboard />
                </ProtectedRoute>
              }
            />
            {/* Centrally Hosted Dynamic Customer Captive Portal */}
            <Route path="/portal/hotspot" element={<CaptivePortalPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
};
