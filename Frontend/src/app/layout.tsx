import type { Metadata } from "next";
import "./globals.css";
import { NetworkActivity } from "@/components/LoadingUI";
import { AuthProvider } from "@/context/AuthContext";
import { ThemeProvider, themeBootstrapScript } from "@/context/ThemeContext";
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
  // Browser extensions inject attributes onto <html> and <body> before React
  // hydrates (jd-enabled, cz-shortcut-listen), which React reports as hydration
  // mismatches. suppressHydrationWarning applies only to the element it is on —
  // mismatches inside the app tree are still reported.
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeBootstrapScript }} />
      </head>
      <body className="bg-[var(--canvas)] text-[var(--fg)] antialiased min-h-screen" suppressHydrationWarning>
        <ThemeProvider><AuthProvider><ToastProvider>{children}<NetworkActivity /></ToastProvider></AuthProvider></ThemeProvider>
      </body>
    </html>
  );
}
