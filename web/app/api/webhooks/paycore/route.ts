import { NextRequest } from "next/server";
import { verifyPayCoreSignature } from "@/lib/paycore-webhook";

export const runtime = "nodejs";

/**
 * The demo store's webhook receiver. Steps every integrator should copy:
 *  1. read the RAW body, 2. verify the signature with the endpoint secret, 3. only then parse and act,
 *  4. return 2xx quickly (do the real work asynchronously), 5. be idempotent on event id (redeliveries happen).
 */
export async function POST(req: NextRequest) {
  const secret = process.env.PAYCORE_DEMO_WEBHOOK_SECRET;
  if (!secret) return new Response("webhook secret not configured", { status: 503 });
  const raw = await req.text();
  const ok = verifyPayCoreSignature(secret, req.headers.get("PayCore-Signature"), raw);
  if (!ok) return new Response("invalid signature", { status: 400 });

  const event = JSON.parse(raw) as { id: string; type: string };
  // A real store would enqueue work here (fulfil the order on payment.captured, etc.), keyed by event.id so a
  // redelivery is a no-op. The demo only acknowledges; the dashboard shows the delivery log.
  console.log("[demo-store] verified webhook", event.type, event.id);
  return Response.json({ received: true });
}
