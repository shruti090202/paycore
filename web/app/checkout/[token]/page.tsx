"use client";

import { useParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { useAsyncEffect } from "@/lib/hooks";
import { api, ApiError } from "@/lib/api";
import { money } from "@/lib/format";
import { Alert, Button, Input } from "@/components/ui";

type Session = {
  payment_id: string; status: string; amount_minor: number; currency: string; merchant_name: string; description?: string;
  customer_email?: string; success_url?: string; cancel_url?: string; redirect_url?: string;
  failure?: { code: string; message: string }; test_cards: { number: string; brand: string; description: string }[];
};

function formatCard(v: string) {
  return v.replace(/\D/g, "").slice(0, 19).replace(/(.{4})/g, "$1 ").trim();
}

export default function CheckoutPage() {
  const { token } = useParams<{ token: string }>();
  const [s, setS] = useState<Session | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [card, setCard] = useState("4242 4242 4242 4242");
  const [exp, setExp] = useState("12/30");
  const [cvc, setCvc] = useState("123");
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [pending, setPending] = useState(false);
  const poll = useRef<number | null>(null);

  const load = useCallback(async () => {
    try {
      const sess = await api<Session>(`/checkout/sessions/${token}`, { auth: false });
      setS(sess);
      if (sess.redirect_url && ["authorized", "captured", "partially_refunded", "refunded"].includes(sess.status)) {
        window.location.assign(sess.redirect_url);
      }
      return sess;
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not load this checkout session.");
      return null;
    }
  }, [token]);

  useAsyncEffect(() => load(), [load]);

  useEffect(() => {
    if (!pending) return;
    poll.current = window.setInterval(async () => {
      const sess = await load();
      if (sess && sess.status !== "pending_bank") {
        setPending(false);
        if (poll.current) window.clearInterval(poll.current);
      }
    }, 2500);
    return () => { if (poll.current) window.clearInterval(poll.current); };
  }, [pending, load]);

  async function confirm(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const [mm, yy] = exp.split("/").map((x) => x.trim());
    try {
      const res = await fetch(`${process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080"}/checkout/sessions/${token}/confirm`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ card_number: card.replace(/\s/g, ""), exp_month: Number(mm), exp_year: Number(yy), cvc, cardholder_name: name || undefined }),
      });
      const body = await res.json();
      if (res.status === 200) {
        if (body.redirect_url) window.location.assign(body.redirect_url);
        else await load();
      } else if (res.status === 202) {
        setPending(true);
      } else {
        setError(body?.error?.message ?? "Payment failed.");
        await load();
      }
    } catch {
      setError("Network error. Please try again.");
    } finally {
      setBusy(false);
    }
  }

  async function cancel() {
    setBusy(true);
    try {
      const r = await api<{ redirect_url?: string }>(`/checkout/sessions/${token}/cancel`, { method: "POST", body: {}, auth: false });
      if (r.redirect_url) window.location.assign(r.redirect_url);
      else await load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  if (!s && !error) return <main className="p-10 text-sm text-zinc-500">Loading checkout…</main>;

  const closed = s && !["created", "pending_bank"].includes(s.status);

  return (
    <main className="mx-auto w-full max-w-lg px-6 py-10">
      <div className="mb-6 rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-900 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-100">
        <strong>Simulator.</strong> Only the test cards listed below are accepted. Never enter a real card number.
      </div>
      {s && (
        <div className="rounded-xl border border-zinc-200 bg-white p-6 shadow-sm dark:border-zinc-800 dark:bg-zinc-900">
          <p className="text-xs uppercase tracking-widest text-zinc-500">Pay {s.merchant_name}</p>
          <p className="mt-1 text-3xl font-semibold">{money(s.amount_minor, s.currency)}</p>
          {s.description && <p className="mt-1 text-sm text-zinc-500">{s.description}</p>}
          {s.customer_email && <p className="mt-1 text-xs text-zinc-500">Paying as {s.customer_email}</p>}

          {pending || s.status === "pending_bank" ? (
            <Alert kind="warn">We are confirming the payment with your bank… this page updates automatically.</Alert>
          ) : closed ? (
            <div className="mt-6 space-y-3">
              <Alert kind={s.status === "failed" || s.status === "canceled" ? "error" : "success"}>
                {s.status === "failed" ? (s.failure?.message ?? "Payment failed.") : `This checkout is ${s.status.replace(/_/g, " ")}.`}
              </Alert>
              {s.cancel_url && <a className="text-sm text-indigo-600 hover:underline" href={s.cancel_url}>← Return to {s.merchant_name}</a>}
            </div>
          ) : (
            <form onSubmit={confirm} className="mt-6 space-y-3">
              {error && <Alert kind="error">{error}</Alert>}
              <Input label="Card number" value={card} onChange={(e) => setCard(formatCard(e.target.value))} inputMode="numeric" autoComplete="off" required />
              <div className="grid grid-cols-2 gap-3">
                <Input label="Expiry (MM/YY)" value={exp} onChange={(e) => setExp(e.target.value)} placeholder="12/30" required />
                <Input label="CVC" value={cvc} onChange={(e) => setCvc(e.target.value.replace(/\D/g, "").slice(0, 4))} inputMode="numeric" required />
              </div>
              <Input label="Name on card (optional)" value={name} onChange={(e) => setName(e.target.value)} />
              <div className="flex items-center gap-3 pt-2">
                <Button type="submit" disabled={busy}>{busy ? "Processing…" : `Pay ${money(s.amount_minor, s.currency)}`}</Button>
                <Button variant="ghost" onClick={cancel} disabled={busy}>Cancel</Button>
              </div>
            </form>
          )}
        </div>
      )}
      {error && !s && <Alert kind="error">{error}</Alert>}

      {s && !closed && (
        <details className="mt-6 rounded-xl border border-zinc-200 bg-white p-4 text-sm dark:border-zinc-800 dark:bg-zinc-900" open>
          <summary className="cursor-pointer font-medium">Test cards</summary>
          <ul className="mt-3 space-y-1">
            {s.test_cards.map((c) => (
              <li key={c.number} className="flex items-center justify-between gap-3">
                <button type="button" className="font-mono text-xs text-indigo-600 hover:underline" onClick={() => setCard(formatCard(c.number))}>{formatCard(c.number)}</button>
                <span className="text-right text-xs text-zinc-500">{c.description}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </main>
  );
}
