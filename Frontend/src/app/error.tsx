"use client";

import { ErrorState } from "@/components/ErrorState";

/** Errors on pages outside the dashboards (login, password reset, portal pages). */
export default function RootError({ error, retry }: { error: Error & { digest?: string }; retry: () => void }) {
  return (
    <main className="flex min-h-screen items-center justify-center px-4 py-12">
      <ErrorState
        title="Something went wrong"
        body="This page hit an unexpected problem. Nothing was changed. Try again, or go back to your workspace."
        onRetry={retry}
        reference={error.digest}
      />
    </main>
  );
}
