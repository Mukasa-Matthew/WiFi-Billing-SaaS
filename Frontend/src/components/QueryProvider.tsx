"use client";

import React, { useState } from "react";
import { IconContext } from "@phosphor-icons/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { getQueryClient } from "@/lib/queryClient";

const ICON_DEFAULTS = { weight: "duotone", size: "1em" } as const;

export default function QueryProvider({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(() => getQueryClient());

  return (
    <QueryClientProvider client={queryClient}>
      <IconContext.Provider value={ICON_DEFAULTS}>{children}</IconContext.Provider>
    </QueryClientProvider>
  );
}
