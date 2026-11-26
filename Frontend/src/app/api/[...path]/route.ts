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

    // Never gzip here. Next's built-in compression (on by default, and since
    // 16.4.6 applied to route handlers) already gzips responses for clients
    // that ask. Doing it here too sent browsers gzip inside gzip under one
    // Content-Encoding header: the sign-in reply could not be read, no token
    // was stored, and every dashboard sign-in looped back to /login.
    //
    // Only JSON may be compressed. Router scripts are text/plain long-polls
    // fetched by MikroTik and must pass through byte for byte, so anything
    // else is marked no-transform, which Next's compression respects.
    const isJson = (res.headers.get("content-type") || "").includes("application/json");
    if (!isJson) {
      const cacheControl = resHeaders.get("cache-control");
      if (!/\bno-transform\b/i.test(cacheControl || "")) {
        resHeaders.set("cache-control", cacheControl ? `${cacheControl}, no-transform` : "no-transform");
      }
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
