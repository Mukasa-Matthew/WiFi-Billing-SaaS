import { NextRequest, NextResponse } from "next/server";

function getBackendBaseUrl(): string {
  if (process.env.BACKEND_INTERNAL_URL) {
    return process.env.BACKEND_INTERNAL_URL.replace(/\/api.*$/, "");
  }
  return "http://host.docker.internal:8080";
}

async function handler(
  req: NextRequest,
  context: { params: Promise<{ path: string[] }> }
) {
  const { path } = await context.params;
  const baseUrl = getBackendBaseUrl();
  const url = new URL(`/api/${path.join("/")}`, baseUrl);
  url.search = req.nextUrl.search;

  const forwardHeaders = new Headers(req.headers);
  forwardHeaders.delete("host");

  try {
    const fetchOptions: RequestInit = {
      method: req.method,
      headers: forwardHeaders,
      cache: "no-store",
    };

    if (req.method !== "GET" && req.method !== "HEAD") {
      const bodyBlob = await req.blob();
      if (bodyBlob.size > 0) {
        fetchOptions.body = bodyBlob;
      }
    }

    const res = await fetch(url.toString(), fetchOptions);
    const resHeaders = new Headers(res.headers);
    // fetch() decodes a compressed upstream body, so its encoding and length
    // no longer describe the bytes we send. Uncompressed upstream responses
    // keep their Content-Length (router scripts rely on an exact passthrough).
    if (res.headers.has("content-encoding")) {
      resHeaders.delete("content-encoding");
      resHeaders.delete("content-length");
    }

    // Neither nginx nor Next compresses proxied API responses, and list
    // endpoints run to ~1 MB of JSON. Gzip JSON only: router scripts are
    // text/plain long-polls fetched by MikroTik and must pass through as-is.
    const isJson = (res.headers.get("content-type") || "").includes("application/json");
    const acceptsGzip = /\bgzip\b/.test(req.headers.get("accept-encoding") || "");
    if (res.body && isJson && acceptsGzip && req.method !== "HEAD") {
      resHeaders.delete("content-length");
      resHeaders.set("content-encoding", "gzip");
      resHeaders.append("vary", "Accept-Encoding");
      return new NextResponse(res.body.pipeThrough(new CompressionStream("gzip")), {
        status: res.status,
        headers: resHeaders,
      });
    }

    return new NextResponse(res.body, {
      status: res.status,
      headers: resHeaders,
    });
  } catch (err: unknown) {
    // The raw cause (e.g. "fetch failed", internal host/port) stays in the
    // server log; the client only gets a message fit for users.
    console.error(`[api proxy] ${req.method} /api/${path.join("/")} failed:`, err);
    return NextResponse.json(
      {
        status: 502,
        error: "Backend connection failed",
        message: "AirPairy is temporarily unavailable. Please try again in a moment.",
        path: `/api/${path.join("/")}`,
      },
      { status: 502 }
    );
  }
}

export const GET = handler;
export const OPTIONS = handler;
export const POST = handler;
export const PUT = handler;
export const DELETE = handler;
export const PATCH = handler;
