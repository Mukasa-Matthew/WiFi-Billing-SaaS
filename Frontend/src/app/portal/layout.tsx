import type { Metadata, Viewport } from "next";

export const metadata: Metadata = {
  title: "Get online",
  description: "Connect to the hotspot",
};

/* Zoom stays enabled (never user-scalable: no) and the portal paints into the
   safe area, so the sheet CTA clears the iOS home indicator. */
export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
};

export default function PortalLayout({ children }: { children: React.ReactNode }) {
  return children;
}
