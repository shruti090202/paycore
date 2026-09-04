// Server-only PayCore client for the demo store. Holds the merchant secret key: never import from client code.
import "server-only";

const API_BASE = process.env.PAYCORE_API_BASE_URL ?? process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const SECRET_KEY = process.env.PAYCORE_DEMO_SECRET_KEY ?? "";

export function demoConfigured(): boolean {
  return SECRET_KEY.startsWith("sk_test_");
}

export async function merchantApi<T>(path: string, init: { method?: string; body?: unknown; idempotencyKey?: string } = {}): Promise<T> {
  const headers: Record<string, string> = { Authorization: `Bearer ${SECRET_KEY}`, Accept: "application/json" };
  if (init.body !== undefined) headers["Content-Type"] = "application/json";
  if (init.idempotencyKey) headers["Idempotency-Key"] = init.idempotencyKey;
  const res = await fetch(`${API_BASE}${path}`, {
    method: init.method ?? (init.body !== undefined ? "POST" : "GET"),
    headers,
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
    cache: "no-store",
  });
  const json = await res.json().catch(() => null);
  if (!res.ok) {
    const e = json?.error;
    throw new Error(e ? `${e.code}: ${e.message}` : `HTTP ${res.status}`);
  }
  return json as T;
}
