import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import { NavigationProgress } from "@/components/LoadingUI";
import { AuthProvider } from "@/context/AuthContext";
import { ThemeProvider, themeBootstrapScript } from "@/context/ThemeContext";
import { ToastProvider } from "@/components/ui/Toast";
import QueryProvider from "@/components/QueryProvider";

// globals.css already resolves --font-geist-* for Tailwind's font-sans/mono;
// these load the faces so the UI stops falling back to Arial.
const geistSans = Geist({ subsets: ["latin"], variable: "--font-geist-sans", display: "swap" });
const geistMono = Geist_Mono({ subsets: ["latin"], variable: "--font-geist-mono", display: "swap" });

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
    <html lang="en" className={`${geistSans.variable} ${geistMono.variable}`} suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeBootstrapScript }} />
      </head>
      <body className="bg-[var(--canvas)] text-[var(--fg)] antialiased min-h-screen" suppressHydrationWarning>
        <QueryProvider><ThemeProvider><AuthProvider><ToastProvider>{children}<NavigationProgress /></ToastProvider></AuthProvider></ThemeProvider></QueryProvider>
      </body>
    </html>
  );
}
