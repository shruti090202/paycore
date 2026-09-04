"use client";

import { useCallback, useState } from "react";
import { useAsyncEffect } from "@/lib/hooks";
import { api, ApiError } from "@/lib/api";
import { Alert, Button, Card, Code, Input, Table, Td } from "@/components/ui";

type Settings = { random_decline_rate: number; random_timeout_rate: number; min_latency_ms: number; max_latency_ms: number; settlement_anomaly_rate: number };
type TestCard = { number: string; brand: string; behaviour: string; decline_code?: string; description: string };

export default function SimulatorPage() {
  const [s, setS] = useState<Settings | null>(null);
  const [cards, setCards] = useState<TestCard[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setS(await api<Settings>("/dashboard/simulator"));
    setCards(await api<TestCard[]>("/dashboard/simulator/test-cards"));
  }, []);

  useAsyncEffect(() => load().catch((e) => setError(e instanceof ApiError ? e.message : String(e))), [load]);

  async function save(next: Settings) {
    setError(null);
    setNotice(null);
    try {
      setS(await api<Settings>("/dashboard/simulator", { method: "PUT", body: next }));
      setNotice("Saved. Settings are in-memory and reset when the API restarts.");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function job(name: string) {
    setError(null);
    setNotice(null);
    try {
      const r = await api<{ status: string; result: unknown }>(`/dashboard/simulator/jobs/${name}`, { method: "POST", body: {} });
      setNotice(`${name}: ${r.status} — ${JSON.stringify(r.result)}`);
    } catch (e) {
      setError(e instanceof ApiError ? `${e.message} (${e.code})` : String(e));
    }
  }

  if (!s) return <p className="text-sm text-zinc-500">Loading…</p>;
  const num = (k: keyof Settings) => (e: React.ChangeEvent<HTMLInputElement>) => setS({ ...s, [k]: Number(e.target.value) });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Bank simulator</h1>
        <p className="text-sm text-zinc-500">An in-process stand-in for an acquiring bank. Documented test cards behave deterministically; the knobs below add randomness to the approve-type cards for demos and load tests.</p>
      </div>
      {error && <Alert kind="error">{error}</Alert>}
      {notice && <Alert kind="info"><span className="font-mono text-xs">{notice}</span></Alert>}

      <Card title="Settings (shared by all merchants)">
        <form className="grid gap-3 sm:grid-cols-2" onSubmit={(e) => { e.preventDefault(); void save(s); }}>
          <Input label="Random decline rate (0–1)" type="number" step="0.01" min={0} max={1} value={s.random_decline_rate} onChange={num("random_decline_rate")} />
          <Input label="Random timeout rate (0–1)" type="number" step="0.01" min={0} max={1} value={s.random_timeout_rate} onChange={num("random_timeout_rate")} />
          <Input label="Min latency (ms)" type="number" min={0} max={10000} value={s.min_latency_ms} onChange={num("min_latency_ms")} />
          <Input label="Max latency (ms)" type="number" min={0} max={10000} value={s.max_latency_ms} onChange={num("max_latency_ms")} />
          <Input label="Settlement anomaly rate (0–1) — corrupts rows in the daily file" type="number" step="0.01" min={0} max={1} value={s.settlement_anomaly_rate} onChange={num("settlement_anomaly_rate")} />
          <div className="flex items-end gap-2">
            <Button type="submit">Save</Button>
            <Button variant="secondary" onClick={() => api<Settings>("/dashboard/simulator", { method: "DELETE" }).then(setS)}>Reset</Button>
          </div>
        </form>
      </Card>

      <Card title="Background jobs (demo triggers; in production a scheduled workflow calls these)">
        <div className="flex flex-wrap gap-2">
          {["webhook-dispatch", "bank-status-check", "settlement-generate", "reconcile", "payout-run", "retention-cleanup"].map((j) => (
            <Button key={j} variant="secondary" onClick={() => job(j)}>{j}</Button>
          ))}
        </div>
        <p className="mt-3 text-xs text-zinc-500"><Code>bank-status-check</Code> resolves payments stuck in <Code>pending_bank</Code> after a bank timeout (it waits 30 s before asking the bank, so a just-timed-out payment stays pending on the first click).</p>
      </Card>

      <Card title="Test cards (the only card numbers PayCore accepts)">
        <Table head={["Number", "Brand", "Behaviour", "Decline code"]}>
          {cards.map((c) => (
            <tr key={c.number}>
              <Td mono className="select-all">{c.number}</Td>
              <Td>{c.brand}</Td>
              <Td>{c.description}</Td>
              <Td mono>{c.decline_code ?? ""}</Td>
            </tr>
          ))}
        </Table>
        <p className="mt-3 text-xs text-zinc-500">Any expiry in the future and any 3–4 digit CVC work.</p>
      </Card>
    </div>
  );
}
