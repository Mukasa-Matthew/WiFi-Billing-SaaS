"use client";

import { ErrorState } from "@/components/ErrorState";

export default function AdminError({ error, retry }: { error: Error & { digest?: string }; retry: () => void }) {
  return (
    <ErrorState
      title="The platform view could not be loaded"
      body="No operation was completed. Check your connection and retry this page."
      onRetry={retry}
      reference={error.digest}
      homeHref="/admin"
      homeLabel="Back to command center"
    />
  );
}
