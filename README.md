# PayCore

A simulated payment gateway — a small Stripe/Razorpay — built to demonstrate how payment systems stay **correct**
(money never appears or vanishes), **reliable** (timeouts, retries, crashes, duplicates) and **operable** (every
number can be re-derived and audited). Merchants get API keys, create payments through a hosted checkout and
receive signed webhooks; every money movement lands in a double-entry ledger; a simulated acquiring bank stands in
for the card networks, complete with a daily settlement file that gets reconciled.

> **Test cards only.** PayCore never processes real card data. Only the [documented test card numbers](#test-cards)
> are accepted; anything else is rejected before it is stored or logged. Do not enter real card numbers anywhere.

**Live demo**
- Demo store: https://paycore-pearl.vercel.app/demo-store
- Merchant dashboard: https://paycore-pearl.vercel.app/login?demo=1 (one-click demo login, no signup)
- API reference (Swagger UI): https://paycore-api-gbvz.onrender.com/swagger-ui/index.html
- Health: https://paycore-api-gbvz.onrender.com/actuator/health

The API runs on a free instance that sleeps after 15 idle minutes; the first request can take up to a minute.

**Try it in five minutes** (everything is sandboxed: test cards only, a shared demo merchant, no real money)

1. Open the [demo store](https://paycore-pearl.vercel.app/demo-store), buy something with card `4242 4242 4242 4242`
   (any future expiry, any CVC). The success page shows the signed webhook the store received.
2. Buy again with `4000 0000 0000 0002` (declined) and `4000 0000 0000 5126` (bank timeout, resolved later by a job).
3. Open the [dashboard](https://paycore-pearl.vercel.app/login?demo=1) → **Payments** → your payment: timeline, bank
   attempts, risk score and the double-entry ledger lines. Refund part of it; try to refund more than was captured.
4. **Risk → Blocklist**: block the email you used in the store, buy again → refused before the bank is called.
5. **Simulator**: raise the bank's decline/timeout rate, run the `bank-status-check` and `settlement-generate` +
   `reconcile` jobs by hand, then look at **Reconciliation** and **Balance**.
6. **API keys** → create a key and use the [Swagger UI](https://paycore-api-gbvz.onrender.com/swagger-ui/index.html)
   or the curl calls under [API in one minute](#api-in-one-minute). Keys you create are yours to revoke.

The demo merchant is shared, so you will see other visitors' test payments next to yours. There is nothing to
break: every invariant (balanced ledger, legal state transitions, refunds ≤ captures) is enforced by the database,
and the nightly retention job prunes old operational rows. Do not enter real card numbers — they are rejected.

---

## What it does

| Area | Capabilities |
|---|---|
| Merchants & auth | Signup/login (bcrypt, short-lived HS256 JWT), API keys with `sk_test_` prefix stored only as SHA-256 hashes, shown once, revocable; seeded demo merchant |
| Money | `BIGINT` minor units + ISO currency, integer arithmetic only; fees = bps + fixed, capped at the captured amount |
| Payments | State machine `created → pending_bank → authorized → captured → partially_refunded → refunded` (+ `failed`, `canceled`) enforced in Java **and** by Postgres triggers/CHECKs; manual and automatic capture; partial capture; full and partial refunds that can never exceed what was captured |
| Ledger | Double-entry journal: every entry's postings sum to zero (deferred constraint trigger), append-only, balances derived from postings; flows for capture (with fee), refund, settlement, payout |
| Bank simulator | In-process acquirer with its own books: deterministic test cards (approve, decline codes, timeout-then-approved/declined, request lost), configurable random declines/timeouts/latency, daily settlement CSV with optional anomalies |
| The unknown state | Bank timeouts park the payment in `pending_bank` under a reference we generated **before** the call; a status-check job asks the bank later. A crash mid-call leaves the same recoverable state |
| Idempotency | `Idempotency-Key` on mutating calls: same key + same body replays the original response, different body → 422, in flight → 409 + `Retry-After`; concurrency settled by a primary key |
| Rate limiting | Per-API-key token bucket as one atomic Redis Lua script; `429` + `Retry-After` + `X-RateLimit-*`; per-merchant overrides; fails open |
| Webhooks | Transactional outbox → `FOR UPDATE SKIP LOCKED` dispatcher with leases → HMAC-SHA256 signatures with timestamp → exponential backoff with full jitter → dead-letter → manual replay; per-attempt log; SSRF guard on endpoint URLs |
| Risk | Rules engine scoring 0–100 with human-readable reasons: amount thresholds, card velocity (Redis), distinct cards per customer (Redis), blocklist (card fingerprint / hashed email), issuer signals; per-merchant rule overrides; block before the bank is contacted |
| Reconciliation | Bank settlement file matched against the gateway's own record of every bank call; discrepancies (`missing_in_ledger`, `missing_in_bank`, `amount_mismatch`, `duplicate`) become items for a human; only matched money is booked; payouts pay `min(owed, settled cash)` |
| Operations | One JSON error envelope, `X-Request-Id` on every response and log line, liveness health check that never touches the DB, retention job for the 0.5 GB database, k6 load gate in CI |
| Frontend | Merchant dashboard (payments, timeline, ledger, refunds, balances/payouts, API keys, webhooks + delivery log + replay, risk rules/blocklist/decisions, reconciliation, simulator), hosted checkout page, demo store with a signature-verifying webhook receiver |

## Architecture

```
  Browser ──► Vercel (Next.js 16)                                GitHub Actions cron
              ├─ /dashboard          JWT                           │ every 15 min: webhook-dispatch, bank-status-check
              ├─ /checkout/[token]   session token in URL          │ daily: settlement-generate, reconcile, payout-run, retention
              └─ /demo-store (+ /api/webhooks/paycore) ◄─ signed webhooks ─┐   ▼  POST /internal/jobs/* (X-Internal-Token)
                        │  server action, secret key                       │
                        ▼                                                  │
   Render free web service — one JVM, a modular monolith (Spring Boot 4 / Java 21)
   ┌────────────────────────────────────────────────────────────────────────────────┐
   │ merchant │ payments │ ledger │ risk │ webhooks (outbox+dispatcher) │ reconciliation │
   │ idempotency │ ratelimit │ banksim (the "acquirer", with its own books) │ jobs      │
   └──────────────────┬──────────────────────────────────────┬─────────────────────┘
                      ▼ pooled + TLS                          ▼ Lettuce + TLS, Lua scripts
              Neon Postgres — system of record          Upstash Redis — rate limits + velocity counters only
```

- **Modular monolith.** Packages under `com.paycore.<module>` with an ArchUnit test that forbids dependency cycles and
  any module depending on the `api` (controller) layer. Cross-module calls go through public services; an
  extension point (`auth.MerchantApiFilter`) lets `ratelimit` and `idempotency` plug into the merchant API chain
  without `auth` knowing them.
- **Three audiences, three credentials, three security filter chains**: `/v1/**` API key, `/dashboard/**` JWT,
  `/checkout/**` session token; `/internal/**` shared token for cron. Credentials are never interchangeable.
- **Postgres is the only system of record.** Redis holds nothing that cannot be lost.

## Key design decisions

**Double-entry ledger, invariants in the database.** Balances are sums over postings, never stored. Postings of a
journal entry must sum to zero — checked by a `DEFERRABLE INITIALLY DEFERRED` constraint trigger at COMMIT, so
legs can be inserted one at a time but an unbalanced entry can never become visible. Ledger tables reject
`UPDATE`/`DELETE`; corrections are new entries. `(kind, reference)` is unique, so "capture of pay_X" can be posted
at most once. Authorization creates no ledger entry — it is a promise, not a movement of funds.

**State machine in code and in the database.** Allowed transitions are rows in `payment_status_transitions`;
a `BEFORE UPDATE` trigger rejects anything else, and CHECK constraints tie status to the money columns
(`refunded_minor <= captured_minor <= amount_minor`, `status = 'refunded' ⇒ refunded = captured`). A test asserts
the Java enum and the table are identical. Bypassing the service with raw SQL cannot corrupt a payment.

**Two transactions around every bank call.** T1 locks the payment, records the reference we are about to send,
moves it to `pending_bank`, and COMMITs; the bank is called with no transaction open; T2 applies the outcome.
A timeout and a crash between T1 and T2 leave exactly the same state, so there is one recovery path
(`bank-status-check`, which looks the reference up at the bank). Refunds reserve their amount in T1
(`captured − refunded − pending`), which is why 200 concurrent refunds on one payment accept exactly the captured
amount.

**Transactional outbox + `SKIP LOCKED` instead of a broker.** Writing the event in the same transaction as the
state change removes both dual-write failure modes ("event without state" and "state without event"). Multiple
dispatchers claim disjoint rows with `FOR UPDATE SKIP LOCKED`; a lease (`next_attempt_at` pushed forward at claim
time) covers the HTTP call made outside the transaction. A broker would add a second system of record and a bill,
and would not solve the dual-write problem by itself.

**Idempotency as a state machine.** `INSERT (merchant, key) … in_progress` before doing the work; the primary key
decides who wins under concurrency. Completed + same request hash → verbatim replay; different hash → 422;
still in progress → 409 + `Retry-After`. Our own 5xx releases the key so the client can retry.

**Free-tier awareness as a design input.** Health checks never touch Postgres or Redis (they would keep Neon
compute awake and burn Upstash commands). The in-process scheduler polls only for a bounded window after real
activity, then stops so the database can suspend; GitHub cron is the guaranteed path. Rate limiting and velocity
are one Lua call each. The JVM is tuned for ~0.1 CPU and 512 MB (`SerialGC`, C1-only JIT, 60 % heap) — a decision
made from measurements, not folklore (see [Results](#results)).

**Fail open vs. fail closed, deliberately.** Rate limiting fails open (a Redis outage must not stop payments);
risk velocity rules fail *closed-ish* (an outage adds a small penalty and a reason, never silent allow, never a
block on its own).

**What the shopper is told.** Risk blocks and fraud declines return a generic "Your card was declined." The
merchant sees the real `failure_code` and the rule reasons in the dashboard.

## Test cards

| Number | Behaviour |
|---|---|
| `4242 4242 4242 4242` | Approved (Visa) |
| `5555 5555 5555 4444` | Approved (Mastercard) |
| `4000 0000 0000 9235` | Approved, flagged for risk review |
| `4000 0000 0000 0002` | Declined (`generic_decline`) |
| `4000 0000 0000 9995` | Declined (`insufficient_funds`) |
| `4000 0000 0000 0069` | Declined (`expired_card`) |
| `4000 0000 0000 0127` | Declined (`incorrect_cvc`) |
| `4100 0000 0000 0019` | Blocked by risk rules before the bank is contacted |
| `4000 0000 0000 5126` | Bank times out; status check later finds it approved |
| `4000 0000 0000 0341` | Bank times out; status check later finds it declined |
| `4000 0000 0000 0259` | Bank times out; request never received → failed after a grace period |
| `4000 0000 0000 3063` | Approved; refunds are declined |
| `4000 0000 0000 3220` | Approved; refunds time out, then succeed |

Any future expiry and any 3–4 digit CVC work.

## API in one minute

```bash
API=https://paycore-api-gbvz.onrender.com
KEY=sk_test_...          # Dashboard → API keys

# create a payment, get a hosted checkout URL
curl -X POST $API/v1/payments -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-1001" \
  -d '{"amount_minor":49900,"currency":"INR","description":"Order 1001","customer":{"email":"buyer@example.com"},
       "success_url":"https://shop.example/ok","cancel_url":"https://shop.example/cancel"}'

# ...the shopper pays on checkout_url; then:
curl $API/v1/payments/pay_...   -H "Authorization: Bearer $KEY"
curl -X POST $API/v1/payments/pay_.../refunds -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d '{"amount_minor":10000}'
curl $API/v1/payments/pay_.../ledger -H "Authorization: Bearer $KEY"
curl $API/v1/balance -H "Authorization: Bearer $KEY"
```

Webhooks are signed `PayCore-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>.<raw body>")>`.
Verify the raw body before parsing (see [`web/lib/paycore-webhook.ts`](web/lib/paycore-webhook.ts)); reject
timestamps older than 5 minutes. Events: `payment.created|authorized|captured|failed|canceled|refunded`,
`refund.created|succeeded|failed`, `ping`.

Errors always look like
`{"error":{"type":"invalid_request","code":"amount_exceeds_refundable","message":"…","param":"amount_minor","request_id":"req_…"}}`.

Full reference: Swagger UI at `/swagger-ui/index.html`.

## Repository layout

```
api/       Spring Boot 4 · Java 21 · Maven wrapper · Flyway · Spring Data JDBC · Testcontainers
web/       Next.js 16 · React 19 · TypeScript · Tailwind 4 (dashboard, checkout, demo store)
load/      k6 scenario + measured baseline (load/README.md)
scripts/   smoke.sh — end-to-end check against any running API
.github/   ci.yml (API tests, web build, k6 gate), jobs-frequent.yml, jobs-daily.yml
render.yaml, docker-compose.yml, DEPLOY.md, CONVENTIONS.md, PROGRESS.md
```

## Local setup

Requirements: JDK 21, Docker Desktop, Node 20+.

```bash
docker compose up -d postgres redis
cd api && ./mvnw verify                  # Windows: .\mvnw.cmd verify — unit + Testcontainers integration tests
```

Run everything in containers (API at http://localhost:8080, Swagger at `/swagger-ui/index.html`):
```bash
docker compose --profile full up --build
```

Web app against it:
```bash
cd web && cp .env.example .env.local     # NEXT_PUBLIC_API_BASE_URL=http://localhost:8080, demo key from compose
npm install && npm run dev               # http://localhost:3000
```
To receive webhooks in the local demo store, register `http://host.docker.internal:3000/api/webhooks/paycore` as an
endpoint (the `local` profile allows private URLs) and put its secret in `web/.env.local`.

## Testing

- **Unit** (`*Test`, no Spring): money arithmetic and rounding, fee policy, state machine edges, card validation,
  JWT, signatures, backoff, crypto, risk engine, CSV parsing, URL policy — 56 tests.
- **Integration** (`*IT`, real Postgres + Redis via Testcontainers, full HTTP stack) — 87 tests, including:
  - raw-SQL attacks on the ledger and payment tables that Postgres must refuse;
  - 200 parallel refunds on one payment → exactly the captured amount refunded, ledger nets to zero;
  - 300 parallel requests with one `Idempotency-Key` → exactly one payment;
  - 6 concurrent webhook dispatchers × 60 events → each delivered exactly once;
  - bank timeout → `pending_bank` → resolved by the status job (approved, declined, and lost variants);
  - reconciliation with every discrepancy kind injected deterministically.
- **Architecture**: ArchUnit rules (no cycles, no module → `api`).
- **Load**: `load/checkout.js` (k6) runs in CI against docker-compose with thresholds derived from a measured
  baseline. Never against the free deployment.
- **Frontend**: ESLint, `tsc --noEmit`, `next build` in CI.

## Results

Measured, not estimated. Environment: API container limited to 512 MB / 0.5 CPU (docker-compose), Postgres 16 and
Redis 7 in containers, 5 virtual users for 30 s after a 10 s warm-up. Details in [load/README.md](load/README.md).

| Metric | C1-only JIT (shipped) | Tiered JIT (rejected) |
|---|---|---|
| p95 create payment | 22 ms | 123 ms |
| p95 hosted-checkout confirm (incl. simulated bank 10–60 ms + risk) | 108 ms | 407 ms |
| p95 get payment | 22 ms | 102 ms |
| p95 whole checkout flow | 160 ms | 702 ms |
| checkouts completed in 30 s | 518 | 293 |
| failed requests | 0 | 0 |
| JVM RSS after the run | 283 MiB | 308 MiB |

During warm-up the tiered JVM's p95 flow was 3.9 s versus 0.53 s for C1-only — on a fractional CPU, C2 compilation
competes with request handling, and a service that spins down every 15 idle minutes is always in its first minute.

CI gate: p95 < 250 ms (create/session/get), < 500 ms (confirm), < 750 ms (flow), failures < 1 %.

Live deployment (Render free in Frankfurt, Neon, Upstash), measured 2026-09-12 from a client in India:

- `scripts/smoke.sh` against the live API: 17/17 checks pass (idempotent replay, checkout capture, refund, ledger,
  events, rate-limit headers from Upstash).
- Warm requests: health 230–560 ms; a dashboard list that hits Postgres 240–350 ms once Neon is awake, and
  **0.99 s for the first query after Neon suspended** (compute resume). Network round-trip India→Frankfurt is
  included in all of these.
- A true Render cold start was not observed during measurement: the 15-minute cron keeps the instance warm, so
  spin-downs happen only when a cron delay and a quiet period coincide. Expect ~30–60 s in that case (JVM start
  on a fractional CPU plus Neon resume).
- Monthly Neon CU-hours and Upstash command counts: to be read from the providers' dashboards after the first full
  month (not yet available).

## Limitations and honest notes

- The bank simulator settles authorized amounts; a partial capture therefore shows up in reconciliation as an
  `amount_mismatch` exception (which is what a real ops team would investigate). It also does not receive voids,
  so a canceled authorization is reported as `missing_in_ledger` — reconciliation catching it is the point.
- Fees are not returned on refund; a fully refunded payment leaves the merchant balance negative by the fee, as a
  real gateway would net off later.
- Refund and settlement flows are simulated instant transfers; there is no real bank, card network, or PCI scope.
- Merchant rate limits and risk thresholds are stored per merchant, but the simulator's knobs are global.
- The scheduled workflows depend on GitHub cron, which can be delayed and is disabled after 60 days without commits.

## License

MIT
