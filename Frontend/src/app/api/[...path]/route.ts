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
export const OPTIONS = handler;
export const POST = handler;
export const PUT = handler;
export const DELETE = handler;
export const PATCH = handler;
