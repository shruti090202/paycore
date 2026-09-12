"use server";

import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { randomUUID } from "node:crypto";
import { PRODUCTS } from "./products";
import { merchantApi } from "@/lib/server-api";

/** Creates the payment server-side (secret key never reaches the browser) and sends the shopper to checkout. */
export async function buy(formData: FormData) {
  const sku = String(formData.get("sku") ?? "");
  const product = PRODUCTS.find((p) => p.sku === sku);
  if (!product) throw new Error("unknown product");
  const typed = String(formData.get("email") ?? "").trim();
  const email = typed.includes("@") ? typed : "shopper@acme-goods.test";

  const h = await headers();
  const proto = h.get("x-forwarded-proto") ?? "http";
  const host = h.get("x-forwarded-host") ?? h.get("host") ?? "localhost:3000";
  const origin = `${proto}://${host}`;
  const orderId = `order_${randomUUID().slice(0, 8)}`;

  const payment = await merchantApi<{ id: string; checkout_url: string }>("/v1/payments", {
    body: {
      amount_minor: product.price_minor,
      currency: "INR",
      description: `${product.name} (${orderId})`,
      customer: { email, ref: "cust_demo" },
      success_url: `${origin}/demo-store/success`,
      cancel_url: `${origin}/demo-store`,
      metadata: { order_id: orderId, sku: product.sku },
    },
    // One order = one payment, even if the form is submitted twice.
    idempotencyKey: orderId,
  });
  redirect(payment.checkout_url);
}
