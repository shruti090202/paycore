import Link from "next/link";
import { API_BASE } from "@/lib/api";

const FEATURES: [string, string][] = [
  ["Double-entry ledger", "Every capture, refund, settlement and payout is a balanced journal entry; balances are derived, never stored. Enforced by Postgres constraint triggers."],
  ["Transactional outbox webhooks", "Events are written in the same transaction as the state change, claimed with SKIP LOCKED, signed with HMAC-SHA256, retried with jittered backoff, dead-lettered and replayable."],
  ["Idempotency keys", "Same key + same body replays the original response; different body is rejected; concurrent duplicates are settled by a primary key, not application locks."],
  ["The unknown bank state", "A bank timeout parks the payment in pending_bank with a reference we generated before the call; a job resolves it later. A crash mid-call leaves the same recoverable state."],
  ["Risk engine", "Configurable rules score every checkout 0–100 (amount, card velocity, distinct cards per customer, blocklist, issuer signals) with human-readable reasons."],
  ["Reconciliation", "The simulated bank's daily settlement file is matched against the gateway's own books; every discrepancy becomes an item for a human, and only matched money is booked."],
];

export default function Home() {
  return (
    <main className="mx-auto w-full max-w-5xl px-6 py-14">
      <div className="rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-100">
        <strong>Test cards only.</strong> PayCore is a simulator for education and portfolio use. It never processes real card
        data: only the documented test card numbers are accepted, and anything else is rejected before it is stored or logged.
      </div>

      <header className="mt-10">
        <p className="text-sm font-semibold uppercase tracking-widest text-indigo-600">PayCore</p>
        <h1 className="mt-2 text-4xl font-bold tracking-tight sm:text-5xl">A payment gateway, built to be understood.</h1>
        <p className="mt-4 max-w-2xl text-lg text-zinc-600 dark:text-zinc-300">
          Merchants get API keys, create payments through a hosted checkout and receive signed webhooks. A fake acquiring bank
          stands in for the card networks. Everything runs on free tiers.
        </p>
        <div className="mt-8 flex flex-wrap gap-3">
          <Link href="/demo-store" className="rounded-lg bg-indigo-600 px-5 py-2.5 text-sm font-medium text-white hover:bg-indigo-500">
            Try the demo store
          </Link>
          <Link href="/login?demo=1" className="rounded-lg border border-zinc-300 bg-white px-5 py-2.5 text-sm font-medium hover:bg-zinc-50 dark:border-zinc-700 dark:bg-zinc-900 dark:hover:bg-zinc-800">
            Open the merchant dashboard (demo login)
          </Link>
          <a href={`${API_BASE}/swagger-ui/index.html`} className="rounded-lg px-5 py-2.5 text-sm font-medium text-indigo-600 hover:underline" target="_blank" rel="noreferrer">
            API reference ↗
          </a>
        </div>
        <p className="mt-3 text-xs text-zinc-500">
          The API runs on a free instance that sleeps when idle; the first request after a quiet period can take up to a minute.
        </p>
      </header>

      <section className="mt-14 grid gap-4 sm:grid-cols-2">
        {FEATURES.map(([t, d]) => (
          <div key={t} className="rounded-xl border border-zinc-200 bg-white p-5 dark:border-zinc-800 dark:bg-zinc-900">
            <h3 className="font-semibold">{t}</h3>
            <p className="mt-2 text-sm text-zinc-600 dark:text-zinc-300">{d}</p>
          </div>
        ))}
      </section>

      <section className="mt-14 rounded-xl border border-zinc-200 bg-white p-5 text-sm dark:border-zinc-800 dark:bg-zinc-900">
        <h3 className="font-semibold">Stack</h3>
        <p className="mt-2 text-zinc-600 dark:text-zinc-300">
          Java 21 · Spring Boot 4 · Spring Data JDBC · Flyway · PostgreSQL (Neon) · Redis (Upstash, Lua) · Testcontainers · k6 ·
          Next.js · Tailwind · Docker · GitHub Actions · Render · Vercel
        </p>
      </section>
    </main>
  );
}
