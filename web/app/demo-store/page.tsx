import Link from "next/link";
import { PRODUCTS } from "./products";
import { demoConfigured } from "@/lib/server-api";
import { money } from "@/lib/format";
import { buy } from "./actions";

export const dynamic = "force-dynamic";

export default function DemoStore() {
  const configured = demoConfigured();
  return (
    <main className="mx-auto w-full max-w-4xl px-6 py-10">
      <div className="flex items-baseline justify-between">
        <div>
          <p className="text-xs font-semibold uppercase tracking-widest text-indigo-600">Demo store</p>
          <h1 className="mt-1 text-3xl font-bold">Acme Goods</h1>
          <p className="mt-2 text-sm text-zinc-500">A fake shop integrated with PayCore the way a real merchant would: it creates a payment server-side with its secret key, redirects you to the hosted checkout, and receives a signed webhook when you pay.</p>
        </div>
        <Link href="/" className="text-sm text-indigo-600 hover:underline">PayCore ↗</Link>
      </div>
      {!configured && (
        <div className="mt-6 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900">
          The demo store is not configured on this deployment: set <code>PAYCORE_DEMO_SECRET_KEY</code> (and <code>PAYCORE_DEMO_WEBHOOK_SECRET</code>) in the web app&apos;s environment.
        </div>
      )}
      <div className="mt-8 grid gap-4 sm:grid-cols-2">
        {PRODUCTS.map((p) => (
          <form key={p.sku} action={buy} className="flex flex-col rounded-xl border border-zinc-200 bg-white p-5 dark:border-zinc-800 dark:bg-zinc-900">
            <input type="hidden" name="sku" value={p.sku} />
            <div className="text-4xl">{p.emoji}</div>
            <h2 className="mt-3 font-semibold">{p.name}</h2>
            <p className="text-sm text-zinc-500">{p.description}</p>
            <div className="mt-4 flex items-center justify-between">
              <span className="text-lg font-semibold">{money(p.price_minor, "INR")}</span>
              <button disabled={!configured} className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-500 disabled:opacity-50">Buy</button>
            </div>
          </form>
        ))}
      </div>
      <p className="mt-8 text-xs text-zinc-500">Use test card 4242 4242 4242 4242 to pay, 4000 0000 0000 0002 to be declined, 4000 0000 0000 5126 to see a bank timeout resolved later.</p>
    </main>
  );
}
