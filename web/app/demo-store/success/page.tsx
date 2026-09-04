import Link from "next/link";
import { merchantApi } from "@/lib/server-api";
import { money, when } from "@/lib/format";

export const dynamic = "force-dynamic";

type Payment = { id: string; status: string; amount_minor: number; currency: string; description?: string; card?: { brand: string; last4: string } };
type Event = { id: string; type: string; created: string; data: { object: { id?: string; payment_id?: string } } };
type Delivery = { id: string; status: string; attempts: number; last_status_code?: number; last_error?: string; delivered_at?: string; attempt_log?: { attempt_no: number; status_code?: number; error?: string; created_at: string }[] };

export default async function Success({ searchParams }: { searchParams: Promise<{ payment_id?: string }> }) {
  const { payment_id } = await searchParams;
  if (!payment_id) return <main className="p-10">Missing payment id.</main>;
  let payment: Payment | null = null;
  let rows: { event: Event; deliveries: Delivery[] }[] = [];
  let error: string | null = null;
  try {
    payment = await merchantApi<Payment>(`/v1/payments/${payment_id}`);
    const page = await merchantApi<{ data: Event[] }>("/v1/events?limit=50");
    const events = page.data.filter((e) => e.data?.object?.id === payment_id || e.data?.object?.payment_id === payment_id);
    rows = await Promise.all(events.map(async (event) => ({ event, deliveries: await merchantApi<Delivery[]>(`/v1/events/${event.id}/deliveries`) })));
  } catch (e) {
    error = e instanceof Error ? e.message : String(e);
  }

  return (
    <main className="mx-auto w-full max-w-2xl px-6 py-10">
      <p className="text-xs font-semibold uppercase tracking-widest text-indigo-600">Demo store</p>
      {error && <p className="mt-4 text-sm text-rose-600">{error}</p>}
      {payment && (
        <>
          <h1 className="mt-1 text-3xl font-bold">{payment.status === "captured" ? "Thank you — order paid." : `Payment ${payment.status.replace(/_/g, " ")}`}</h1>
          <p className="mt-2 text-zinc-600 dark:text-zinc-300">{payment.description} · {money(payment.amount_minor, payment.currency)}{payment.card && ` · ${payment.card.brand} •••• ${payment.card.last4}`}</p>
          <p className="mt-1 font-mono text-xs text-zinc-500">{payment.id}</p>
          {payment.status === "pending_bank" && <p className="mt-3 text-sm text-amber-700">The bank has not answered yet; PayCore will confirm the outcome shortly and this page will show it on reload.</p>}
        </>
      )}

      <section className="mt-8 rounded-xl border border-zinc-200 bg-white p-5 dark:border-zinc-800 dark:bg-zinc-900">
        <h2 className="font-semibold">Behind the scenes: events and webhook deliveries</h2>
        <p className="mt-1 text-sm text-zinc-500">
          Each event PayCore emitted for this payment, and its delivery to this store&apos;s endpoint <code>/api/webhooks/paycore</code>, which verifies the
          <code> PayCore-Signature</code> header before accepting. Deliveries happen asynchronously — reload to see them progress.
        </p>
        <ul className="mt-3 divide-y divide-zinc-100 text-sm dark:divide-zinc-800">
          {rows.length === 0 && <li className="py-2 text-zinc-500">No events yet.</li>}
          {rows.map(({ event, deliveries }) => (
            <li key={event.id} className="flex items-start justify-between gap-3 py-2">
              <div>
                <code className="text-xs">{event.type}</code>
                <div className="text-xs text-zinc-500">{when(event.created)}</div>
              </div>
              <div className="text-right text-xs">
                {deliveries.length === 0 && <span className="text-zinc-500">not yet queued</span>}
                {deliveries.map((d) => (
                  <div key={d.id}>
                    <span className={d.status === "delivered" ? "text-emerald-700" : d.status === "dead" ? "text-rose-700" : "text-amber-700"}>{d.status}</span>
                    {d.last_status_code && <span className="text-zinc-500"> · HTTP {d.last_status_code}</span>}
                    <span className="text-zinc-500"> · {d.attempts} attempt{d.attempts === 1 ? "" : "s"}</span>
                  </div>
                ))}
              </div>
            </li>
          ))}
        </ul>
      </section>

      <div className="mt-6 flex gap-4 text-sm">
        <Link className="text-indigo-600 hover:underline" href="/demo-store">← Shop again</Link>
        <Link className="text-indigo-600 hover:underline" href="/login?demo=1">Open the merchant dashboard</Link>
      </div>
    </main>
  );
}
