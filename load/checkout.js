// End-to-end checkout flow under load: create payment -> load session -> confirm with the approved test card
// -> read the payment back. Runs against the docker-compose stack in CI (never against the free Render deploy).
//
//   k6 run -e BASE_URL=http://localhost:8080 -e API_KEY=sk_test_... load/checkout.js
//
// Thresholds come from a measured baseline (see load/README.md); override with -e P95_MS=... for experiments.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'sk_test_localdemo000000000000000000000000';
const P95_MS = Number(__ENV.P95_MS || 250);   // measured baseline p95: create 22 ms, get 22 ms, confirm 108 ms, flow 160 ms (see load/README.md)
const VUS = Number(__ENV.VUS || 5);
const DURATION = __ENV.DURATION || '30s';

export const options = {
  scenarios: {
    checkout: { executor: 'constant-vus', vus: VUS, duration: DURATION },
  },
  thresholds: {
    // Per-step latency gates; the whole-flow gate is the one CI fails on.
    'http_req_duration{step:create}': [`p(95)<${P95_MS}`],
    'http_req_duration{step:session}': [`p(95)<${P95_MS}`],
    'http_req_duration{step:confirm}': [`p(95)<${P95_MS * 2}`],   // includes the simulated bank latency (10-60 ms)
    'http_req_duration{step:get}': [`p(95)<${P95_MS}`],
    'checkout_flow_ms': [`p(95)<${P95_MS * 3}`],
    'http_req_failed': ['rate<0.01'],
    'checks': ['rate>0.99'],
  },
};

const flow = new Trend('checkout_flow_ms', true);
const captured = new Counter('payments_captured');

const auth = { headers: { Authorization: `Bearer ${API_KEY}`, 'Content-Type': 'application/json' } };
const json = { headers: { 'Content-Type': 'application/json' } };

export default function () {
  const started = Date.now();

  const create = http.post(`${BASE}/v1/payments`, JSON.stringify({
    amount_minor: 1000 + (__ITER % 50) * 100,
    currency: 'INR',
    customer: { email: `vu${__VU}@load.test` },
    metadata: { vu: String(__VU), iter: String(__ITER) },
  }), { ...auth, tags: { step: 'create' } });
  const created = check(create, { 'create 201': (r) => r.status === 201 });
  if (!created) { sleep(0.5); return; }
  const payment = create.json();
  const token = payment.checkout_url.split('/').pop();

  const session = http.get(`${BASE}/checkout/sessions/${token}`, { tags: { step: 'session' } });
  check(session, { 'session 200': (r) => r.status === 200 });

  const confirm = http.post(`${BASE}/checkout/sessions/${token}/confirm`, JSON.stringify({
    card_number: '4242424242424242', exp_month: 12, exp_year: 2030, cvc: '123',
  }), { ...json, tags: { step: 'confirm' } });
  const ok = check(confirm, { 'confirm 200': (r) => r.status === 200, 'captured': (r) => r.status === 200 && r.json('status') === 'captured' });
  if (ok) captured.add(1);

  const get = http.get(`${BASE}/v1/payments/${payment.id}`, { ...auth, tags: { step: 'get' } });
  check(get, { 'get 200': (r) => r.status === 200 });

  flow.add(Date.now() - started);
  sleep(0.2);
}

export function handleSummary(data) {
  const p = (m) => (data.metrics[m] ? Math.round(data.metrics[m].values['p(95)']) : null);
  const summary = {
    vus: VUS, duration: DURATION,
    p95_ms: {
      create: p('http_req_duration{step:create}'),
      session: p('http_req_duration{step:session}'),
      confirm: p('http_req_duration{step:confirm}'),
      get: p('http_req_duration{step:get}'),
      flow: p('checkout_flow_ms'),
    },
    requests: data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0,
    failed_rate: data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : null,
    captured: data.metrics.payments_captured ? data.metrics.payments_captured.values.count : 0,
  };
  return {
    stdout: '\n=== checkout load summary ===\n' + JSON.stringify(summary, null, 2) + '\n',
    'load/results/summary.json': JSON.stringify(summary, null, 2),
  };
}
