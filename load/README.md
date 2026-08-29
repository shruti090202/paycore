# Load tests

`checkout.js` drives the full hosted-checkout flow (create payment -> load session -> confirm with the approved test
card -> read payment) against the docker-compose stack. It is **never** run against the free Render deployment.

```bash
RATE_LIMIT_CAPACITY=100000 RATE_LIMIT_REFILL_PER_SECOND=10000 docker compose --profile full up -d --build
k6 run -e VUS=5 -e DURATION=30s load/checkout.js
```

## Measured baseline (2026-09-12)

Environment: Windows 11 host, Docker Desktop, API container limited to **512 MB / 0.5 CPU** (mirrors Render free as
closely as compose allows), Postgres 16 and Redis 7 in containers, 5 VUs for 30 s after a 10 s warm-up.

| JVM flags | p95 create | p95 confirm* | p95 get | p95 whole flow | captures / 30 s | failures |
|---|---|---|---|---|---|---|
| SerialGC + `-XX:TieredStopAtLevel=1` (C1 only) — **shipped** | 22 ms | 108 ms | 22 ms | 160 ms | 518 | 0 |
| SerialGC, tiered (C1 + C2) | 123 ms | 407 ms | 102 ms | 702 ms | 293 | 0 |

\* confirm includes the simulated bank latency (10–60 ms) and the risk evaluation (2 Redis calls).

During the 10 s warm-up the tiered JVM's p95 flow was 3.9 s versus 0.53 s for C1-only: C2 compilation competes
with request handling for the fractional CPU. A service that spins down after 15 idle minutes is always in its
first minute, so the C1-only configuration is the right trade for this deployment. On a full core, tiered would
win in steady state.

## CI gate

`.github/workflows/ci.yml` runs the same script after the test job with `P95_MS=250`, i.e. p95 must stay under
250 ms for create/session/get, 500 ms for confirm and 750 ms for the whole flow (3–11x the measured baseline, to
absorb noisy shared runners without hiding a real regression). `http_req_failed` must stay below 1 %.
The summary is uploaded as a build artifact (`load-summary`).
