import type { Metadata } from "next";
import { ErrorState } from "@/components/ErrorState";

export const metadata: Metadata = { title: "Page not found" };

export default function NotFound() {
  return (
    <main className="flex min-h-screen items-center justify-center px-4 py-12">
      <ErrorState
        code="404"
        title="Page not found"
        body="The page you were looking for doesn't exist or has moved. Check the address, or head back to your workspace."
      />
    </main>
  );
}
