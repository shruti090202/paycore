// Verifies a PayCore webhook signature. Mirror of the server's WebhookSignatures.verify:
//   PayCore-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>.<raw body>")>
// Verify the RAW body bytes before parsing JSON; any re-serialization would break the MAC (by design).
import { createHmac, timingSafeEqual } from "node:crypto";

export function verifyPayCoreSignature(secret: string, header: string | null, rawBody: string, nowSeconds = Math.floor(Date.now() / 1000), toleranceSeconds = 300): boolean {
  if (!header) return false;
  let t = -1;
  let v1: string | null = null;
  for (const part of header.split(",")) {
    const [k, v] = part.trim().split("=", 2);
    if (k === "t") t = Number(v);
    if (k === "v1") v1 = v;
  }
  if (!Number.isFinite(t) || t < 0 || !v1) return false;
  if (Math.abs(nowSeconds - t) > toleranceSeconds) return false;
  const expected = createHmac("sha256", secret).update(`${t}.${rawBody}`).digest("hex");
  const a = Buffer.from(expected, "utf8");
  const b = Buffer.from(v1, "utf8");
  return a.length === b.length && timingSafeEqual(a, b);
}
