"use client";

import { ErrorState } from "@/components/ErrorState";

export default function DashboardError({ error, retry }: { error: Error & { digest?: string }; retry: () => void }) {
  return (
    <ErrorState
      title="This page could not be loaded"
      body="Your data has not been changed. Check your connection and try loading this page again."
      onRetry={retry}
      reference={error.digest}
      homeHref="/dashboard"
      homeLabel="Back to dashboard"
    />
  );
}
