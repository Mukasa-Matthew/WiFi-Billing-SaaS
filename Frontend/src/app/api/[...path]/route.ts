import { NextRequest, NextResponse } from "next/server";
import { mockRoute } from "@/lib/devMock";

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

  // Dev-only: serve fixtures instead of proxying, so the UI can be built
  // without the Spring Boot backend, FreeRADIUS and Postgres running.
  if (process.env.MOCK_API === "1") {
    const body = req.method === "POST" ? await req.json().catch(() => ({})) : {};
    const mocked = mockRoute(req.method, path, req.nextUrl.searchParams, body);
    if (mocked !== undefined) {
      if (typeof mocked === "string") {
        return new NextResponse(mocked, { headers: { "content-type": "text/html; charset=utf-8" } });
      }
      return NextResponse.json(mocked);
    }
    return NextResponse.json({ message: `No mock for ${req.method} /api/${path.join("/")}` }, { status: 404 });
  }

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

    return new NextResponse(res.body, {
      status: res.status,
      headers: resHeaders,
    });
  } catch (err: any) {
    return NextResponse.json(
      { error: "Backend connection failed", message: err.message },
      { status: 502 }
    );
  }
}

export const GET = handler;
export const POST = handler;
export const PUT = handler;
export const DELETE = handler;
export const PATCH = handler;
