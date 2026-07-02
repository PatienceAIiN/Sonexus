import { vi, type Mock } from "vitest";

export interface Route {
  method: string;
  path: string | RegExp;
  status?: number;
  body?: unknown;
  /** dynamic response */
  handler?: (init: RequestInit | undefined, url: string) => { status?: number; body?: unknown };
}

/** Installs a route-table fetch mock and returns the mock for call assertions. */
export function mockFetch(routes: Route[]): Mock {
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = (init?.method ?? "GET").toUpperCase();
    for (const r of routes) {
      const pathMatch =
        typeof r.path === "string" ? url.endsWith(r.path) || url.includes(r.path) : r.path.test(url);
      if (r.method.toUpperCase() === method && pathMatch) {
        const out = r.handler ? r.handler(init, url) : { status: r.status, body: r.body };
        const status = out.status ?? 200;
        return new Response(JSON.stringify(out.body ?? {}), {
          status,
          headers: { "Content-Type": "application/json" },
        });
      }
    }
    return new Response(JSON.stringify({ detail: `no mock for ${method} ${url}` }), { status: 404 });
  });
  vi.stubGlobal("fetch", fn);
  return fn;
}

export function callsTo(fetchMock: Mock, method: string, pathPart: string) {
  return fetchMock.mock.calls.filter(([input, init]) => {
    const m = ((init as RequestInit | undefined)?.method ?? "GET").toUpperCase();
    return m === method.toUpperCase() && String(input).includes(pathPart);
  });
}
