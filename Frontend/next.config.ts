import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  generateBuildId: async () => {
    return process.env.NEXT_PUBLIC_DEPLOY_ID || process.env.GIT_COMMIT_SHA || "production-release";
  },
  cacheComponents: true,
  async headers() {
    // Production only. In dev, Turbopack reuses asset filenames across rebuilds,
    // so an immutable year-long cache pins the browser to stale CSS/JS until a
    // manual hard reload. Next warns about this at boot for the same reason.
    if (process.env.NODE_ENV !== "production") return [];
    return [
      {
        source: "/_next/static/:path*",
        headers: [
          { key: "Cache-Control", value: "public, max-age=31536000, immutable" },
        ],
      },
    ];
  },
  partialPrefetching: true,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
