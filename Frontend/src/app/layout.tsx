import type { Metadata } from "next";
import "./globals.css";
import { NetworkActivity } from "@/components/LoadingUI";
import { AuthProvider } from "@/context/AuthContext";
import { ToastProvider } from "@/components/ui/Toast";

export const metadata: Metadata = {
  metadataBase: new URL(process.env.NEXT_PUBLIC_APP_URL || "https://qticket.dev"),
  title: {
    default: "AirPairy — Connecting WiFi. Simplifying Business.",
    template: "%s · AirPairy",
  },
  description: "AirPairy is a multi-tenant WiFi hotspot billing and management platform for independent WiFi businesses. A product by Thewton.",
  applicationName: "AirPairy",
  openGraph: {
    title: "AirPairy",
    description: "Connecting WiFi. Simplifying Business.",
    siteName: "AirPairy",
    type: "website",
  },
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
