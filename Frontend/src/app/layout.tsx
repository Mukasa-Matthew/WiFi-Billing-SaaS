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
  // Browser extensions inject attributes onto <html> and <body> before React
  // hydrates (jd-enabled, cz-shortcut-listen), which React reports as hydration
  // mismatches. suppressHydrationWarning applies only to the element it is on —
  // mismatches inside the app tree are still reported.
  return (
    <html lang="en" suppressHydrationWarning>
      <body className="bg-[#F5F7FA] text-slate-900 antialiased min-h-screen" suppressHydrationWarning>
        <AuthProvider><ToastProvider>{children}<NetworkActivity /></ToastProvider></AuthProvider>
      </body>
    </html>
  );
}
