"use client";

import "./globals.css";
import { themeBootstrapScript } from "@/context/ThemeContext";
import { ErrorState } from "@/components/ErrorState";

/**
 * Last-resort boundary for failures in the root layout itself. It replaces
 * the whole document, so it brings its own <html>/<body>, styles and theme.
 */
export default function GlobalError({ error, retry }: { error: Error & { digest?: string }; retry: () => void }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <title>Something went wrong · AirPairy</title>
        <script dangerouslySetInnerHTML={{ __html: themeBootstrapScript }} />
      </head>
      <body className="bg-[var(--canvas)] text-[var(--fg)] antialiased min-h-screen">
        <main className="flex min-h-screen items-center justify-center px-4 py-12">
          <ErrorState
            title="AirPairy couldn't load"
            body="Something went wrong while starting the app. Nothing was changed. Try again, or reload the page in a moment."
            onRetry={retry}
            reference={error.digest}
          />
        </main>
      </body>
    </html>
  );
}
