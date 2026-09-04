"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { useAsyncEffect } from "@/lib/hooks";
import { api, ApiError, Page } from "@/lib/api";
import { short, when } from "@/lib/format";
import { Alert, Badge, Button, Card, Code, Empty, Input, Select, Table, Td } from "@/components/ui";

type Rule = { type: string; name: string; params: Record<string, unknown>; weight: number; enabled: boolean; overridden: boolean };
type Decision = { id: string; payment_id: string; score: number; decision: string; reasons: { rule: string; score: number; message: string }[]; evaluated_at: string };
type Block = { id: string; kind: string; value_hash: string; reason?: string; created_at: string };

export default function RiskPage() {
  const [rules, setRules] = useState<Rule[]>([]);
  const [decisions, setDecisions] = useState<Decision[]>([]);
  const [filter, setFilter] = useState("");
  const [blocks, setBlocks] = useState<Block[]>([]);
  const [kind, setKind] = useState("email");
  const [value, setValue] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [edits, setEdits] = useState<Record<string, string>>({});

  const load = useCallback(async () => {
    const [r, d, b] = await Promise.all([
      api<Rule[]>("/dashboard/risk/rules"),
      api<Page<Decision>>(`/dashboard/risk/decisions?limit=50${filter ? `&decision=${filter}` : ""}`),
      api<Block[]>("/dashboard/risk/blocklist"),
    ]);
    setRules(r);
    setDecisions(d.data);
    setBlocks(b);
  }, [filter]);

  useAsyncEffect(() => load().catch((e) => setError(e instanceof ApiError ? e.message : String(e))), [load]);

  async function run(fn: () => Promise<unknown>) {
    setError(null);
    try {
      await fn();
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? `${e.message} (${e.code})` : String(e));
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Risk</h1>
        <p className="text-sm text-zinc-500">Every checkout is scored 0–100 before the bank is contacted. Score ≥ 40 flags the payment for review; ≥ 80 blocks it (the shopper sees a generic decline; you see why).</p>
      </div>
      {error && <Alert kind="error">{error}</Alert>}

      <Card title="Rules (global defaults; edit to override for your account)">
        <Table head={["Rule", "Params (JSON)", "Weight", "Enabled", ""]}>
          {rules.map((r) => (
            <tr key={r.type}>
              <Td><Code>{r.type}</Code><div className="text-xs text-zinc-500">{r.name}{r.overridden && " · overridden"}</div></Td>
              <Td>
                <input
                  className="w-72 rounded border border-zinc-300 px-2 py-1 font-mono text-xs dark:border-zinc-700 dark:bg-zinc-950"
                  value={edits[r.type] ?? JSON.stringify(r.params)}
                  onChange={(e) => setEdits({ ...edits, [r.type]: e.target.value })}
                />
              </Td>
              <Td>{r.weight}</Td>
              <Td>
                <input type="checkbox" checked={r.enabled} onChange={(e) => run(() => api(`/dashboard/risk/rules/${r.type}`, { method: "PUT", body: { enabled: e.target.checked } }))} />
              </Td>
              <Td>
                <div className="flex gap-1">
                  <Button variant="ghost" onClick={() => run(async () => {
                    let params: unknown;
                    try { params = JSON.parse(edits[r.type] ?? JSON.stringify(r.params)); } catch { throw new ApiError(400, "client", "json", "Params must be valid JSON"); }
                    await api(`/dashboard/risk/rules/${r.type}`, { method: "PUT", body: { params } });
                  })}>Save</Button>
                  {r.overridden && <Button variant="ghost" onClick={() => run(() => api(`/dashboard/risk/rules/${r.type}`, { method: "DELETE" }))}>Reset</Button>}
                </div>
              </Td>
            </tr>
          ))}
        </Table>
      </Card>

      <Card title="Blocklist">
        <form
          className="mb-4 grid gap-3 sm:grid-cols-4"
          onSubmit={(e) => {
            e.preventDefault();
            void run(async () => {
              await api("/dashboard/risk/blocklist", { body: { kind, value, reason: reason || undefined } });
              setValue("");
              setReason("");
            });
          }}
        >
          <Select label="Kind" value={kind} onChange={(e) => setKind(e.target.value)}>
            <option value="email">email</option>
            <option value="card_fingerprint">card fingerprint (fp_…)</option>
          </Select>
          <Input label="Value" value={value} onChange={(e) => setValue(e.target.value)} required placeholder={kind === "email" ? "fraud@example.com" : "fp_… (from a payment's detail page)"} />
          <Input label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} />
          <div className="flex items-end"><Button type="submit">Block</Button></div>
        </form>
        {blocks.length === 0 ? (
          <Empty>Nothing blocked. Emails are stored hashed.</Empty>
        ) : (
          <Table head={["Kind", "Value (hashed)", "Reason", "Added", ""]}>
            {blocks.map((b) => (
              <tr key={b.id}>
                <Td>{b.kind}</Td>
                <Td mono>{b.value_hash}</Td>
                <Td>{b.reason ?? "—"}</Td>
                <Td className="text-zinc-500">{when(b.created_at)}</Td>
                <Td><Button variant="ghost" onClick={() => run(() => api(`/dashboard/risk/blocklist/${b.id}`, { method: "DELETE" }))}>Remove</Button></Td>
              </tr>
            ))}
          </Table>
        )}
      </Card>

      <Card
        title="Decisions"
        actions={
          <Select value={filter} onChange={(e) => setFilter(e.target.value)}>
            <option value="">all</option>
            <option value="allow">allow</option>
            <option value="review">review</option>
            <option value="block">block</option>
          </Select>
        }
      >
        {decisions.length === 0 ? (
          <Empty>No decisions yet.</Empty>
        ) : (
          <Table head={["When", "Payment", "Score", "Decision", "Reasons"]}>
            {decisions.map((d) => (
              <tr key={d.id}>
                <Td className="whitespace-nowrap text-zinc-500">{when(d.evaluated_at)}</Td>
                <Td mono><Link className="text-indigo-600 hover:underline" href={`/dashboard/payments/${d.payment_id}`}>{short(d.payment_id)}</Link></Td>
                <Td>{d.score}</Td>
                <Td><Badge value={d.decision} /></Td>
                <Td className="text-xs">{d.reasons.length === 0 ? <span className="text-zinc-400">none</span> : d.reasons.map((r, i) => <div key={i}><Code>{r.rule}</Code> +{r.score} {r.message}</div>)}</Td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
    </div>
  );
}
