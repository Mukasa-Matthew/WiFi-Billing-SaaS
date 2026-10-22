import type { Metadata } from "next";
import "./globals.css";
import { NetworkActivity } from "@/components/LoadingUI";
import { AuthProvider } from "@/context/AuthContext";
import { ToastProvider } from "@/components/ui/Toast";

export const metadata: Metadata = {
  title: "WiFi-Billing SaaS - Enterprise Hotspot Management",
  description: "Carrier-grade WiFi Hotspot, WireGuard Mesh & Micro-billing platform",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body className="bg-[#F5F7FA] text-slate-900 antialiased min-h-screen">
        <AuthProvider><ToastProvider>{children}<NetworkActivity /></ToastProvider></AuthProvider>
      </body>
    </html>
  );
}
